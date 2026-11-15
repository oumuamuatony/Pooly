package io.pooly.app

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Direct search on the demo emulator, with a key provisioned in app-private storage. */
object DemoPlaceSearch {
    fun fallback(context: Context, path: String, response: JSONObject): JSONObject {
        if (!BuildConfig.DEBUG || Build.HARDWARE !in setOf("ranchu", "goldfish") ||
            response.optInt("status") != 200 || !path.startsWith("/api/places?")) return response
        val data = response.optJSONObject("data") ?: return response
        if (data.optString("provider") != "saved") return response
        val query = Uri.parse(path).getQueryParameter("q")?.trim()?.take(100).orEmpty()
        if (query.isEmpty()) return response
        val key = runCatching { context.filesDir.resolve("vworld-demo-key").readText().trim() }.getOrNull()
        if (key.isNullOrBlank()) return response

        val category = if (Regex("(동|리|읍|면)\\s*\\d").containsMatchIn(query)) "PARCEL" else "ROAD"
        val places = runCatching { search(query, key, "PLACE", null) }.getOrNull()
        val addresses = runCatching { search(query, key, "ADDRESS", category) }.getOrNull()
        if (places == null && addresses == null) return response
        val results = (places.orEmpty() + addresses.orEmpty()).distinctBy {
            Triple(it.optString("name"), it.optDouble("lat"), it.optDouble("lon"))
        }.take(20)
        if (results.isEmpty() && (places == null || addresses == null)) return response
        return JSONObject().put("status", 200).put("data",
            JSONObject().put("provider", "vworld").put("results", JSONArray(results)))
    }

    private fun search(query: String, key: String, type: String, category: String?): List<JSONObject> {
        val url = Uri.parse("https://api.vworld.kr/req/search").buildUpon()
        val params = mapOf("service" to "search", "request" to "search", "version" to "2.0",
            "format" to "json", "crs" to "EPSG:4326", "query" to query, "type" to type,
            "size" to "20", "key" to key, "domain" to PoolyApi.BASE)
        params.forEach { (name, value) -> url.appendQueryParameter(name, value) }
        category?.let { url.appendQueryParameter("category", it) }
        val connection = URL(url.toString()).openConnection() as HttpURLConnection
        val response = try {
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            connection.instanceFollowRedirects = false
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONObject("response")
        } finally {
            connection.disconnect()
        }
        if (response.optString("status") == "NOT_FOUND") return emptyList()
        check(response.optString("status") == "OK")
        val items = response.optJSONObject("result")?.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val point = item.optJSONObject("point") ?: return@mapNotNull null
            val lat = point.optDouble("y")
            val lon = point.optDouble("x")
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            val address = item.optJSONObject("address")
            val road = address?.optString("road").orEmpty()
            val parcel = address?.optString("parcel").orEmpty()
            val name = item.optString("title").ifBlank { road.ifBlank { parcel } }
            if (name.isBlank()) return@mapNotNull null
            JSONObject().put("id", "vworld-" + item.optString("id").ifBlank { "$type-$index" })
                .put("name", name).put("address", road.ifBlank { parcel }).put("lat", lat).put("lon", lon)
                .put("source", "vworld").put("kind", if (type == "PLACE") "place" else "address")
        }
    }
}
