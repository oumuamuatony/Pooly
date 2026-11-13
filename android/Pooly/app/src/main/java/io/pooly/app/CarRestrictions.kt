package io.pooly.app

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder

/** Loaded only on automotive devices, so the same APK also runs on a phone. */
class CarRestrictions(context: Context, private val changed: (Boolean) -> Unit) {
    private var manager: CarUxRestrictionsManager? = null
    private val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { changed(it.isRequiresDistractionOptimization) }
    private var car: Car? = null
    init { car = Car.createCar(context, object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                manager = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                manager?.registerListener(listener)
                changed(manager?.currentCarUxRestrictions?.isRequiresDistractionOptimization ?: true)
            } catch (_: Exception) { changed(true) }
        }
        override fun onServiceDisconnected(name: ComponentName?) { changed(true) }
    }); car?.connect() }
    fun close() { try { manager?.unregisterListener(); car?.disconnect() } catch (_: Exception) {} }
}
