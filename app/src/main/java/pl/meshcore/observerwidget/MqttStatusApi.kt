package pl.meshcore.observerwidget

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object MqttStatusApi {
    fun fetch(host: String, timeoutMs: Int, nowMs: Long = System.currentTimeMillis()): Health {
        if (host.isBlank()) return Health.UNKNOWN
        val url = try { URL("${Config.baseUrl(host)}/api/mqtt/status") } catch (_: Exception) { return Health.UNKNOWN }
        return try {
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
                connection.requestMethod = "GET"
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Cache-Control", "no-cache, no-store")
                if (connection.responseCode !in 200..299) return Health.UNKNOWN
                parse(host, connection.inputStream.bufferedReader().use { it.readText() }, nowMs)
            } finally { connection.disconnect() }
        } catch (_: Exception) { Health.UNKNOWN }
    }

    fun parse(host: String, body: String, nowMs: Long = System.currentTimeMillis()): Health {
        val root = try { JSONObject(body) } catch (_: Exception) { return Health.UNKNOWN }
        val sample = root.optString("sampleAt").takeIf { it.isNotBlank() } ?: return Health.UNKNOWN
        val sampleMs = ObserverApi.parseTime(sample) ?: return Health.UNKNOWN
        if (sampleMs > nowMs + 30_000L || nowMs - sampleMs > 5 * 60_000L) return Health.UNKNOWN
        val watchdog = root.optLong("watchdogLastTickUnix", 0L)
        if (watchdog > 0L && (watchdog * 1000L > nowMs + 30_000L || nowMs - watchdog * 1000L > 3 * 60_000L)) return Health.UNKNOWN
        val sources = root.optJSONArray("sources") ?: return Health.UNKNOWN
        if (sources.length() == 0) return Health.UNKNOWN
        val source = selectSource(host, sources) ?: return Health.UNKNOWN
        if (!source.has("connected") || source.isNull("connected")) return Health.UNKNOWN
        return when (val value = source.opt("connected")) {
            is Boolean -> if (value) Health.GOOD else Health.BAD
            else -> when (value.toString().lowercase()) {
                "true", "1", "yes" -> Health.GOOD
                "false", "0", "no" -> Health.BAD
                else -> Health.UNKNOWN
            }
        }
    }

    private fun selectSource(host: String, sources: JSONArray): JSONObject? {
        if (sources.length() == 1) return sources.optJSONObject(0)
        val target = Config.displayHost(host).lowercase()
        val matches = (0 until sources.length()).mapNotNull { sources.optJSONObject(it) }.filter { source ->
            val broker = source.optString("broker").lowercase()
            val name = source.optString("name").lowercase()
            target.isNotBlank() && (broker.contains(target) || name.contains(target))
        }
        return matches.singleOrNull()
    }
}
