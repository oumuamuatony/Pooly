package io.pickuptogether.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** The native bridge may only address the configured development API. */
object LocalApi {
    const val POOLY_CLOUD = "https://yeonil.pythonanywhere.com"

    fun allowedBase(value: String): String {
        if (value == POOLY_CLOUD) return value
        val u = URI(value)
        require(u.scheme == "http" && u.host in setOf("127.0.0.1", "localhost") && u.port in 1024..65535 && u.rawPath in listOf("", "/") && u.rawQuery == null && u.userInfo == null)
        return "http://${u.host}:${u.port}"
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
