package io.pickuptogether.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue

/** First-run SDK diagnostic. Appointment and pickup features are not implemented yet. */
class SdkCheckActivity : Activity() {
    private val blue = Color.rgb(0, 44, 95)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var requestId = 0
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var retry: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(56), dp(36), dp(56), dp(36))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.WHITE, Color.rgb(246, 243, 242))
            )
        }
        content.addView(text("픽업 약속", 20, blue, true))
        content.addView(text("개발 환경 연결 확인", 34, Color.rgb(38, 38, 38), true).apply {
            setPadding(0, dp(16), 0, dp(12))
        })
        content.addView(text("Pleos 에뮬레이터에서 차량 데이터를 읽어 연결을 확인합니다.", 19))
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(22))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(20).toFloat()
            }
            elevation = dp(3).toFloat()
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(28)
                bottomMargin = dp(24)
            }
            addView(text("Vehicle SDK 2.3.2", 20, blue, true))
            status = text("연결 준비 중", 27, Color.rgb(38, 38, 38), true)
            addView(status.apply { setPadding(0, dp(16), 0, dp(12)) })
            details = text("운전석 도어 위치를 조회합니다.", 18)
            addView(details)
        })
        retry = Button(this).apply {
            text = "차량 데이터 다시 읽기"
            textSize = 21f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(blue)
                cornerRadius = dp(15).toFloat()
            }
            minHeight = dp(64)
            setOnClickListener { readVehicle() }
        }
        content.addView(retry, LinearLayout.LayoutParams(-1, dp(64)))
        content.addView(text("개발용 확인 화면 · 약속/픽업 기능은 다음 단계에서 연결합니다.", 16).apply {
            setPadding(0, dp(20), 0, 0)
        })
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(-1, -1))
        })
        readVehicle()
    }

    private fun readVehicle() {
        val current = ++requestId
        retry.isEnabled = false
        status.text = "차량 데이터 확인 중…"
        details.text = "Pleos Vehicle 서비스에 연결하고 있습니다."
        mainHandler.postDelayed({
            if (current == requestId && !isDestroyed && !isFinishing && !retry.isEnabled) {
                requestId++
                status.text = "연결 응답을 받지 못했어요"
                details.text = "Pleos 에뮬레이터의 부팅 완료 여부를 확인하고 다시 시도해 주세요."
                retry.isEnabled = true
                Log.w(TAG, "VEHICLE_READ_TIMEOUT")
            }
        }, 15_000)

        try {
            val sdk = vehicle ?: Vehicle(this).also {
                vehicle = it
                it.initialize()
            }
            sdk.getDoor().getCurrentDoorPosition(
                area = DoorArea(DoorValue.ROW_1_LEFT),
                onComplete = { position: Int? ->
                    update(current) {
                        if (position == null) {
                            status.text = "연결됐지만 값이 없어요"
                            details.text = "현재 에뮬레이터가 운전석 도어 위치 값을 제공하지 않았습니다."
                            Log.w(TAG, "VEHICLE_READ_EMPTY")
                        } else {
                            status.text = "Pleos 연결 성공"
                            details.text = "운전석 도어 위치 값: $position\nVehicle API의 실제 응답을 받았습니다."
                            Log.i(TAG, "VEHICLE_READ_SUCCESS doorPosition=$position")
                        }
                    }
                },
                onFailed = { error: Exception -> showError(current, error) }
            )
        } catch (error: Exception) {
            showError(current, error)
        }
    }

    private fun showError(current: Int, error: Exception) = update(current) {
        status.text = "연결 상태를 확인해 주세요"
        details.text = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        Log.e(TAG, "VEHICLE_READ_FAILED", error)
    }

    private fun update(current: Int, action: () -> Unit) {
        mainHandler.post {
            if (current == requestId && !isDestroyed && !isFinishing) {
                action()
                retry.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        requestId++
        mainHandler.removeCallbacksAndMessages(null)
        vehicle?.release()
        vehicle = null
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Int, color: Int = Color.DKGRAY, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    companion object { private const val TAG = "PleosSetupCheck" }
}
