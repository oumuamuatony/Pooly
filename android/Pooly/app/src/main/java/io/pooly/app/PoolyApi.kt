package io.pooly.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The native bridge talks only to the deployed Pooly server (BuildConfig.API_BASE_URL). */
object PoolyApi {
    val BASE: String = BuildConfig.API_BASE_URL

    fun allowedBase(value: String): String {
        require(value.trimEnd('/') == BASE) { "Pooly 서버 주소만 사용할 수 있어요." }
        return BASE
    }
    fun request(base: String, path: String, method: String, body: JSONObject? = null, token: String = "", timeoutMs: Int = 7000): JSONObject {
        require(path.startsWith("/api/") && !path.contains("..") && !path.contains("\\") && !path.contains("#") && !path.contains("\n") && path.length < 1600)
        require(method in setOf("GET", "POST", "PATCH"))
        val connection = URL(allowedBase(base) + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs; connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json")
            if (token.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $token")
            if (method != "GET") { connection.doOutput = true; connection.outputStream.use { it.write((body ?: JSONObject()).toString().toByteArray()) } }
            val status = connection.responseCode
            val source = if (status >= 400) connection.errorStream else connection.inputStream
            val text = source?.bufferedReader()?.use { it.readText() } ?: "{}"
            return JSONObject().put("status", status).put("data", JSONObject(text))
        } finally { connection.disconnect() }
    }
}
