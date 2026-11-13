package io.pooly.app

import android.car.Car
import android.car.CarInfoManager
import android.car.hardware.property.CarPropertyManager
import android.car.VehiclePropertyIds
import android.car.VehicleGear
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Build
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import org.json.JSONObject

/** Vehicle signals and scoped seat personalization. Occupancy is matched to the active pickup by the app, not a biometric identity. */
class PleosVehicle(context: Context, private val event: (JSONObject) -> Unit) {
    private val sdk = Vehicle(context.applicationContext)
    private val ui = Handler(Looper.getMainLooper())
    private var closed = false
    private var journeyActive = false
    private var checking = false
    private val seats = JSONObject()
    private val doors = JSONObject()
    private val seatListeners = linkedMapOf<SeatArea, SeatOccupancyListener>()
    private val doorListeners = linkedMapOf<DoorArea, DoorPositionListener>()
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    val vehicleKey: String = context.getSharedPreferences("pooly_vehicle", Context.MODE_PRIVATE).let { prefs ->
        prefs.getString("key", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("key", it).apply() }
    }
    private val personalizer = SeatPersonalizer(sdk, vehicleKey, event)
    fun personalize(seat: String, profile: JSONObject, current: () -> Boolean) = personalizer.apply(seat, profile, current)

    // This verified emulator exposes VACANT=1, OCCUPIED=2 while SDK 2.3.2 maps
    // only integer 1 to true. Do not apply this compatibility rule to real cars
    // or to an unverified emulator/SDK combination.
    private val invertEmulatorSeat = Build.HARDWARE in setOf("ranchu", "goldfish") &&
        Build.FINGERPRINT.startsWith("Pleos/") && Build.FINGERPRINT.contains("release_connect_v2.1.0") &&
        BuildConfig.VEHICLE_SDK_VERSION == "2.3.2"
    private val poll = object : Runnable {
        override fun run() {
            if (closed || !journeyActive) return
            readSignals()
            if (seatListeners.isEmpty() || doorListeners.isEmpty()) subscribe()
            ui.postDelayed(this, 2000)
        }
    }
    init {
        sdk.initialize()
        // Read the configured driver seat rather than assuming left-hand drive.
        try {
            car = Car.createCar(context.applicationContext, object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    try {
                        properties = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                        val id = (car?.getCarManager(Car.INFO_SERVICE) as? CarInfoManager)?.driverSeat
                        val seat = SeatValue.values().firstOrNull { it.id == id }
                        event(JSONObject().put("vehicleDriverSeat", seat?.name ?: JSONObject.NULL))
                    } catch (_: Exception) { event(JSONObject().put("vehicleDriverSeat", JSONObject.NULL)) }
                }
                override fun onServiceDisconnected(name: ComponentName?) { event(JSONObject().put("vehicleDriverSeat", JSONObject.NULL)) }
            })
            car?.connect()
        } catch (_: Exception) { event(JSONObject().put("vehicleDriverSeat", JSONObject.NULL)) }
    }
    private fun readable(access: AccessType) = access in listOf(AccessType.READ, AccessType.READ_WRITE)
    fun setJourneyActive(active: Boolean) {
        ui.post {
            journeyActive = active
            ui.removeCallbacks(poll)
            if (active) { refresh(); ui.post(poll) }
        }
    }
    fun refresh() {
        if (closed) return
        try {
            sdk.getOdometer().checkCurrentDistanceDrivenCapabilities(onComplete = { config ->
                if (!closed && readable(config.accessType)) sdk.getOdometer().getCurrentDistanceDriven(onComplete = { value ->
                    if (!closed && value != null && value.isFinite()) event(JSONObject().put("vehicleOdometerRaw", value.toDouble()).put("vehicle", "connected"))
                }, onFailed = { unavailable() })
            }, onFailed = { unavailable() })
            ui.post { subscribe() }
        } catch (_: Exception) { unavailable() }
    }
    private fun subscribe() {
        if (closed || checking) return
        checking = true
        try {
            sdk.getSeat().checkSeatOccupancyCapabilities(onComplete = { config, areas -> ui.post {
                checking = false
                if (closed) return@post
                if (!readable(config.accessType)) { event(JSONObject().put("vehicleSeatMonitoring", "unsupported")); return@post }
                areas.forEach { area ->
                    if (!seatListeners.containsKey(area)) {
                        val listener = object : SeatOccupancyListener {
                            override fun onSeatOccupancyUpdated(value: Boolean?) { seat(area, value) }
                            override fun onFailed(error: Exception) { seat(area, null) }
                        }
                        seatListeners[area] = listener
                        sdk.getSeat().registerSeatOccupancy(area, listener)
                    }
                }
                event(JSONObject().put("vehicleSeatMonitoring", if (areas.isEmpty()) "unsupported" else "connected"))
                readSignals()
            } }, onFailed = { ui.post { checking = false; event(JSONObject().put("vehicleSeatMonitoring", "unavailable")) } })
            sdk.getDoor().checkDoorPositionCapabilities(onComplete = { config, areas -> ui.post {
                if (closed || !readable(config.accessType)) return@post
                areas.keys.forEach { area ->
                    if (!doorListeners.containsKey(area)) {
                        val listener = object : DoorPositionListener {
                            override fun onPositionUpdated(value: Int?) { door(area, value) }
                            override fun onFailed(error: Exception) { door(area, null) }
                        }
                        doorListeners[area] = listener
                        sdk.getDoor().registerDoorPosition(area, listener)
                    }
                }
                readSignals()
            } }, onFailed = { unavailable() })
        } catch (_: Exception) { checking = false; unavailable() }
    }
    private fun readSignals() {
        if (closed) return
        try {
            val speed = properties?.getProperty<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)
            val gear = properties?.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0)
            if (speed?.status == 0 && gear?.status == 0 && speed.value.isFinite()) {
                event(JSONObject().put("vehicleMotion", JSONObject().put("speedMps",speed.value.toDouble())
                    .put("parked",gear.value == VehicleGear.GEAR_PARK).put("updatedAt",System.currentTimeMillis())))
            }
        } catch (_: Exception) { event(JSONObject().put("vehicleMotion",JSONObject.NULL)) }
        seatListeners.keys.toList().forEach { area ->
            val requestedAt=System.currentTimeMillis()
            try { sdk.getSeat().getCurrentSeatOccupancy(area, onComplete = { seat(area, it, requestedAt) }, onFailed = { seat(area, null, requestedAt) }) } catch (_: Exception) { seat(area, null, requestedAt) }
        }
        doorListeners.keys.toList().forEach { area ->
            val requestedAt=System.currentTimeMillis()
            try { sdk.getDoor().getCurrentDoorPosition(area, onComplete = { door(area, it, requestedAt) }, onFailed = { door(area, null, requestedAt) }) } catch (_: Exception) { door(area, null, requestedAt) }
        }
    }
    private fun seat(area: SeatArea, sdkValue: Boolean?, requestedAt: Long? = null) { ui.post {
        if (closed || area.values.size != 1) return@post
        val value = sdkValue?.let { if (invertEmulatorSeat) !it else it }
        val key = area.values.single().name
        val old = seats.optJSONObject(key)
        // Do not let a getter started before a newer callback invent an unoccupied/occupied edge.
        if (requestedAt != null && old != null && old.optLong("updatedAt") > requestedAt) return@post
        val changed = old != null && !old.isNull("occupied") && value != null && old.getBoolean("occupied") != value
        val row = JSONObject().put("occupied", value ?: JSONObject.NULL).put("sdkValue", sdkValue ?: JSONObject.NULL)
            .put("mapping", if (invertEmulatorSeat) "pleos-2.1.0-sdk-2.3.2" else "sdk-boolean")
            .put("updatedAt", System.currentTimeMillis())
        if (changed) row.put("previous", old!!.getBoolean("occupied")).put("changedAt", System.currentTimeMillis())
        else if (old != null && value != null) { if (old.has("previous")) row.put("previous", old.get("previous")); if (old.has("changedAt")) row.put("changedAt", old.get("changedAt")) }
        seats.put(key, row)
        event(JSONObject().put("vehicleSeats", JSONObject(seats.toString())))
    } }
    private fun door(area: DoorArea, value: Int?, requestedAt: Long? = null) { ui.post {
        if (closed || area.values.size != 1) return@post
        val key = area.values.single().name
        val old = doors.optJSONObject(key)
        if (requestedAt != null && old != null && old.optLong("updatedAt") > requestedAt) return@post
        val open = value?.let { it > 0 }
        val row = JSONObject().put("open", open ?: JSONObject.NULL).put("updatedAt", System.currentTimeMillis())
        if (old != null && open != null && !old.isNull("open")) {
            for (field in listOf("lastOpenedAt", "lastClosedAt")) if (old.has(field)) row.put(field, old.get(field))
            if (old.getBoolean("open") != open) row.put(if (open) "lastOpenedAt" else "lastClosedAt", System.currentTimeMillis())
        }
        doors.put(key, row)
        event(JSONObject().put("vehicleDoors", JSONObject(doors.toString())))
    } }
    private fun unavailable() { if (!closed) event(JSONObject().put("vehicle", "partial")) }
    fun close() {
        closed = true; ui.removeCallbacks(poll)
        seatListeners.forEach { (area, listener) -> try { sdk.getSeat().unregisterSeatOccupancy(area, listener) } catch (_: Exception) {} }
        doorListeners.forEach { (area, listener) -> try { sdk.getDoor().unregisterDoorPosition(area, listener) } catch (_: Exception) {} }
        try { car?.disconnect(); sdk.release() } catch (_: Exception) {}
    }
}
