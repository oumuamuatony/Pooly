package io.pooly.app

import android.content.Context
import android.media.AudioAttributes
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import org.json.JSONObject
import java.util.Locale

/** Explicit, on-device speech only. No project secret and no continuous listening. */
class PleosVoice(context: Context, private val event: (JSONObject) -> Unit) {
    private val tts = TextToSpeech(context.applicationContext, ai.pleos.playground.tts.constant.Mode.ON_DEVICE)
    private val stt = SpeechToText(context.applicationContext, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)
    private var ttsReady = false
    private var sttReady = false
    private var listening = false
    private val ttsListener = object : ai.pleos.playground.tts.listener.EventListener {
        override fun onStart() = report("tts", "speaking")
        override fun onReady() = report("tts", "ready")
        override fun onDone() = report("tts", "ready")
        override fun onStop() = report("tts", "ready")
        override fun onError(errorType: ai.pleos.playground.tts.constant.ErrorType) = error("tts", errorType.name)
        override fun onUpdatedRms(rms: Double) {}
    }
    private val sttListener = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) {
            if (isFinal && listening) { listening = false; event(JSONObject().put("speechText", text.take(500)).put("stt", "ready")) }
        }
        override fun onUpdatedEpdData(start: Long, end: Long) {}
        override fun onStartedRecognition() = report("stt", "listening")
        override fun onEndedRecognition() = report("stt", "ready")
        override fun onReady() = report("stt", "ready")
        override fun onError(errorType: ai.pleos.playground.stt.constant.ErrorType) { listening = false; error("stt", errorType.name) }
        override fun onUpdatedRms(rms: Double) {}
    }
    init {
        try {
            tts.initialize(object : ai.pleos.playground.tts.listener.OnInitializeListener {
                override fun onSuccess() {
                    tts.addEventListener(ttsListener); ttsReady = true; tts.setLocale(Locale.KOREA)
                    tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    report("tts", "ready")
                }
                override fun onFailure(errorType: ai.pleos.playground.tts.constant.ErrorType) = error("tts", errorType.name)
            })
        } catch (e: Exception) { android.util.Log.w("PoolyVoice", "TTS init ${e.javaClass.simpleName}: ${e.message?.take(160)}"); error("tts", "UNAVAILABLE") }
        try {
            stt.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
                override fun onSuccess() { stt.addListener(sttListener); sttReady = true; stt.setLocale(Locale.KOREA); report("stt", "ready") }
                override fun onFailure(errorType: ai.pleos.playground.stt.constant.ErrorType) = error("stt", errorType.name)
            })
        } catch (_: Exception) { error("stt", "UNAVAILABLE") }
    }
    private fun report(key: String, value: String) { event(JSONObject().put(key, value)) }
    private fun error(key: String, code: String) { event(JSONObject().put(key, "unavailable").put(key + "Error", code)) }
    fun speak(text: String) { check(ttsReady) { "차량 음성 안내 서비스에 연결하지 못했어요." }; require(text.isNotBlank() && text.length <= 500); tts.speak(text, isFinal = true) }
    fun listen() { check(sttReady) { "차량 음성 인식 서비스를 사용할 수 없어요." }; tts.stop(); listening = true; stt.request() }
    fun stop() { listening = false; try { stt.stop(); tts.stop() } catch (_: Exception) {} }
    fun close() {
        stop()
        try { stt.removeListener(sttListener); stt.release() } catch (_: Exception) {}
        try { tts.removeEventListener(ttsListener); tts.release() } catch (_: Exception) {}
    }
}
