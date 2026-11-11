package io.pickuptogether.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.ExecutorService

/** Emulator-only controls for the disposable demo identity, inside the car app. */
class EmulatorTestController(
    private val activity: Activity,
    private val io: ExecutorService,
    private val reload: () -> Unit,
    private val navigate: () -> Unit
) {
    companion object {
        fun available() = BuildConfig.DEMO_MODE && Build.HARDWARE in setOf("ranchu", "goldfish")
    }
    private val ui = Handler(Looper.getMainLooper())
    private val prefs = activity.getSharedPreferences("together_session", Context.MODE_PRIVATE)
    private var dialog: AlertDialog? = null
    private var statusView: TextView? = null
    private var closed = false
    @Volatile private var lastState: JSONObject? = null
    @Volatile private var message = "앱에서 출발하면 위치가 재생되고 가상 친구가 응답해요."

    private fun request(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        check(available())
        val result = LocalApi.request(BuildConfig.API_BASE_URL, path, method, body, timeoutMs = 3500)
        check(result.getInt("status") in 200..299) { result.getJSONObject("data").optString("error", "테스트 서버 연결을 확인해 주세요.") }
        return result.getJSONObject("data")
    }
    private fun control(command: String, data: JSONObject = JSONObject()) = request("/api/demo/control", "POST", data.put("command", command))
    private fun post(message: String, success: (() -> Unit)? = null, task: () -> Unit) {
        io.execute {
            try { task(); this.message = message; ui.post { if (!closed) { success?.invoke(); refreshStatus() } } }
            catch (error: Exception) { this.message = error.message?.take(150) ?: "테스트 서버에 연결하지 못했어요."; ui.post { if (!closed) { refreshStatus(); Toast.makeText(activity, this.message, Toast.LENGTH_LONG).show() } } }
        }
    }
    fun command(command: String?) {
        if (!available()) return
        when (command) {
            "testSettings" -> show()
            "newTest" -> newScenario()
            "pauseTest" -> pause()
            "resumeTest" -> resume()
        }
    }
    fun newScenario() {
        prefs.edit().putBoolean("emulatorTestEnabled", false).apply()
        post("새 테스트가 준비됐어요. 앱에서 출발하기를 눌러 주세요.", { reload(); show() }) {
            control("reset", JSONObject().put("accepted", true))
            lastState = control("connect")
            prefs.edit().putBoolean("emulatorTestEnabled", true).putBoolean("emulatorAutoFriends", true).putBoolean("emulatorAutoMotion", true).putBoolean("emulatorPaused", false).apply()
        }
    }
    private fun pause() {
        prefs.edit().putBoolean("emulatorPaused", true).apply()
        post("위치 재생을 멈췄어요.") { lastState = control("pause") }
    }
    private fun resume() {
        prefs.edit().putBoolean("emulatorPaused", false).apply()
        post("위치 재생을 재개했어요.") { lastState = control("play") }
    }
    /** Called on the existing background worker; never blocks the vehicle UI. */
    fun sync() {
        if (!available() || !prefs.getBoolean("emulatorTestEnabled", false)) return
        try {
            val state = request("/api/demo/state")
            lastState = state
            val appointment = state.getJSONObject("appointment")
            val trip = appointment.getJSONObject("trip")
            val phase = trip.getString("phase")
            if (prefs.getBoolean("emulatorAutoFriends", true)) {
                val actors = state.getJSONObject("actors")
                val members = appointment.getJSONArray("members")
                val active = trip.optJSONArray("active_pickups")
                val current = active?.optString(trip.optInt("index"))
                for (actor in listOf("jiyeon", "hyunjun")) {
                    val id = actors.getJSONObject(actor).getString("member_id")
                    val member = (0 until members.length()).map { members.getJSONObject(it) }.firstOrNull { it.getString("id") == id } ?: continue
                    if (member.optString("status") == "pending" && phase == "idle") {
                        control("actor", JSONObject().put("actor", actor).put("action", "join")); continue
                    }
                    if (member.optString("status") != "accepted") continue
                    if (phase == "finished" && member.isNull("home_at")) control("actor", JSONObject().put("actor", actor).put("action", "home"))
                    if (!member.isNull("boarded_at")) continue
                    val ready = when {
                        current == id && phase in listOf("near", "arrived") -> "waiting"
                        phase in listOf("driving", "near", "arrived") -> "walking"
                        else -> null
                    }
                    if (ready != null && member.optString("ready") != ready) control("actor", JSONObject().put("actor", actor).put("action", ready))
                    if (current == id && phase == "arrived" && member.isNull("boarding_confirmed_at")) control("actor", JSONObject().put("actor", actor).put("action", "board_confirm"))
                }
            }
            if (prefs.getBoolean("emulatorAutoMotion", true) && !prefs.getBoolean("emulatorPaused", false) && state.optBoolean("connected") && !state.optBoolean("running") && phase in listOf("driving", "near", "destination")) {
                val coordinates = state.getJSONObject("road").getJSONArray("legs").getJSONObject(state.getInt("leg")).getJSONArray("coordinates")
                val end = coordinates.getJSONArray(coordinates.length() - 1)
                val point = state.getJSONObject("position")
                val atEnd = kotlin.math.abs(point.getDouble("lat") - end.getDouble(1)) < .00002 && kotlin.math.abs(point.getDouble("lon") - end.getDouble(0)) < .00002
                if (!atEnd) control("play")
            }
            ui.post { if (!closed) refreshStatus() }
        } catch (error: Exception) {
            message = error.message?.take(150) ?: "테스트 서버 연결을 확인해 주세요."
        }
    }
    private fun refreshStatus() {
        val state = lastState
        val phase = state?.optJSONObject("appointment")?.optJSONObject("trip")?.optString("phase")
        val label = mapOf("idle" to "출발 전", "driving" to "픽업 이동", "near" to "픽업 근처", "arrived" to "도착 확인됨", "destination" to "목적지 이동", "parked" to "주차 완료", "finished" to "이동 종료")[phase] ?: "연결 대기"
        statusView?.text = "$label · ${if (state?.optBoolean("running") == true) "위치 재생 중" else "위치 정지"}\n$message"
    }
    fun show() {
        check(available())
        if (dialog?.isShowing == true || activity.isFinishing) return
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(12)) }
        fun text(value: String, size: Float = 17f) = TextView(activity).apply { text = value; textSize = size; setTextColor(Color.rgb(38, 52, 68)); setPadding(0, dp(8), 0, dp(8)); column.addView(this) }
        fun button(label: String, action: () -> Unit) = Button(activity).apply { text = label; textSize = 17f; isAllCaps = false; minHeight = dp(50); setOnClickListener { action() }; column.addView(this, LinearLayout.LayoutParams(-1, -2)) }
        text("지금 켜진 Pleos 에뮬레이터에서 시험해요.\n새 약속에 가상 친구 두 명을 넣고, 출발·픽업 장소 도착은 앱에서 진행해요. 승차는 공식 콘솔의 문·좌석 변화로 확인할 수 있어요.")
        statusView = text("", 16f)
        button("새 테스트 약속 만들기") { dialog?.dismiss(); newScenario() }
        fun toggle(label: String, key: String) { column.addView(Switch(activity).apply { text = label; textSize = 17f; isChecked = prefs.getBoolean(key, true); setPadding(0, dp(10), 0, dp(10)); setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() } }) }
        toggle("출발하면 위치 자동 재생", "emulatorAutoMotion")
        toggle("가상 친구 자동 응답", "emulatorAutoFriends")
        button("위치 재생 일시정지") { pause() }
        button("위치 재생 재개") { resume() }
        val speeds = arrayOf("1배 · 천천히", "2배 · 보통", "4배 · 빠르게")
        button("재생 속도 선택") { AlertDialog.Builder(activity).setTitle("위치 재생 속도").setItems(speeds) { _, index -> post("재생 속도를 변경했어요.") { lastState = control("speed", JSONObject().put("value", listOf(1, 2, 4)[index])) } }.show() }
        button("가상 친구 상태 직접 바꾸기") {
            val actors = arrayOf("지연", "현준")
            AlertDialog.Builder(activity).setTitle("가상 친구 선택").setItems(actors) { _, index ->
                val actions = listOf("preparing", "ready", "walking", "waiting", "board_confirm", "home")
                val labels = arrayOf("준비 중", "준비 완료", "픽업 장소로 이동", "픽업 장소 도착", "친구 탑승 확인", "귀가 확인")
                AlertDialog.Builder(activity).setTitle(actors[index]).setItems(labels) { _, action ->
                    prefs.edit().putBoolean("emulatorAutoFriends", false).apply()
                    post("친구 상태를 변경했어요. 자동 응답은 껐어요.") { lastState = control("actor", JSONObject().put("actor", listOf("jiyeon", "hyunjun")[index]).put("action", actions[action])) }
                    dialog?.dismiss()
                }.show()
            }.show()
        }
        button("현재 구간 내비 안내") { dialog?.dismiss(); navigate() }
        button("테스트 자동 진행 끄기") {
            prefs.edit().putBoolean("emulatorTestEnabled", false).putBoolean("emulatorPaused", true).apply()
            post("자동 진행을 껐어요. 앱의 일반 버튼으로 계속 시험할 수 있어요.") { lastState = control("pause") }
        }
        text("승차 감지 시험: 맥북 브라우저에서 http://127.0.0.1:8088/ 을 열어 두세요.\nEmulation Controls → Convenience에서 동승석을 Empty로 둔 뒤, 픽업 장소 도착 상태에서 해당 문 Open → 좌석 Occupied → 문 Close 순서로 변경하세요. 약 2초 후 승차 안내가 표시돼요. 수동 탑승 완료도 사용할 수 있어요.", 15f)
        val scroll = ScrollView(activity).apply { addView(column) }
        dialog = AlertDialog.Builder(activity).setTitle("에뮬레이터 테스트").setView(scroll).setPositiveButton("앱으로 돌아가기") { _, _ -> }.create().also { current ->
            current.setOnDismissListener { if (dialog === current) { dialog = null; statusView = null } }
            current.show(); current.window?.setLayout(dp(640), (activity.resources.displayMetrics.heightPixels * .88).toInt())
        }
        refreshStatus()
    }
    fun close() { closed = true; dialog?.dismiss(); ui.removeCallbacksAndMessages(null) }
}
