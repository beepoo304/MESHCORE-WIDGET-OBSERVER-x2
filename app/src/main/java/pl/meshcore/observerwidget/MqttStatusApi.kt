package pl.meshcore.observerwidget

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stan życia brokera jako strony agregacyjnej (CoreScope): GET {host}/api/mqtt/status.
 *
 * GOOD    – strona odpowiada, status jest świeży i co najmniej jedno źródło MQTT jest połączone
 *           i dostarcza pakiety.
 * BAD     – strona nie odpowiada / zwraca błąd HTTP, status jest zamrożony (stary sampleAt / watchdog),
 *           albo żadne źródło MQTT nie jest połączone lub od dawna nie było pakietu.
 * UNKNOWN – tylko gdy nie da się ocenić (brak hosta, niezrozumiała odpowiedź).
 *
 * Wcześniej każdy błąd sieci i każdy broker z wieloma źródłami (np. analyzer.marwoj.net) dawał UNKNOWN,
 * przez co kolor tytułu MQTT nie odzwierciedlał stanu strony.
 */
object MqttStatusApi {
    const val STALE_SAMPLE_MS = 5 * 60_000L
    const val STALE_WATCHDOG_MS = 3 * 60_000L
    const val STALE_PACKETS_MS = 10 * 60_000L

    /** Wynik pomocniczy: [networkError] = nie udało się w ogóle połączyć (np. telefon offline). */
    data class Probe(val health: Health, val networkError: Boolean)

    fun fetch(host: String, timeoutMs: Int, nowMs: Long = System.currentTimeMillis()): Health =
        probe(host, timeoutMs, nowMs).health

    fun probe(host: String, timeoutMs: Int, nowMs: Long = System.currentTimeMillis()): Probe {
        if (host.isBlank()) return Probe(Health.UNKNOWN, false)
        val url = try { URL("${Config.baseUrl(host)}/api/mqtt/status") } catch (_: Exception) {
            return Probe(Health.UNKNOWN, false)
        }
        return try {
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = timeoutMs.coerceAtLeast(1)
                connection.readTimeout = timeoutMs.coerceAtLeast(1)
                connection.requestMethod = "GET"
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Cache-Control", "no-cache, no-store")
                val code = connection.responseCode
                if (code !in 200..299) return Probe(Health.BAD, false)
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val serverNow = connection.getHeaderFieldDate("Date", 0L).takeIf { it > 0L } ?: nowMs
                Probe(parse(host, body, serverNow), false)
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            Probe(Health.BAD, true)
        } catch (_: Exception) {
            Probe(Health.UNKNOWN, false)
        }
    }

    /** [nowMs] najlepiej w czasie serwera (nagłówek Date), żeby zły zegar telefonu nie fałszował wyniku. */
    fun parse(host: String, body: String, nowMs: Long = System.currentTimeMillis()): Health {
        val root = try { JSONObject(body) } catch (_: Exception) { return Health.UNKNOWN }

        root.optString("sampleAt").takeIf { it.isNotBlank() }?.let { ObserverApi.parseTime(it) }?.let { sampleMs ->
            if (nowMs - sampleMs > STALE_SAMPLE_MS) return Health.BAD
        }
        val watchdog = root.optLong("watchdogLastTickUnix", 0L)
        if (watchdog > 0L && nowMs - watchdog * 1000L > STALE_WATCHDOG_MS) return Health.BAD

        val sources = root.optJSONArray("sources") ?: return Health.GOOD // strona żyje, brak szczegółów
        if (sources.length() == 0) return Health.BAD
        val all = (0 until sources.length()).mapNotNull { sources.optJSONObject(it) }
        val target = Config.displayHost(host).lowercase()
        val matching = all.filter { s ->
            target.isNotBlank() && (s.optString("broker").lowercase().contains(target) ||
                s.optString("name").lowercase().contains(target))
        }
        val considered = matching.ifEmpty { all }
        val alive = considered.any { s -> isConnected(s) && packetsFresh(s, nowMs) }
        return if (alive) Health.GOOD else Health.BAD
    }

    private fun isConnected(s: JSONObject): Boolean {
        if (!s.has("connected") || s.isNull("connected")) return true
        return when (val v = s.opt("connected")) {
            is Boolean -> v
            else -> v.toString().lowercase() in setOf("true", "1", "yes")
        }
    }

    private fun packetsFresh(s: JSONObject, nowMs: Long): Boolean {
        val last = s.optLong("lastPacketUnix", 0L)
        return last <= 0L || nowMs - last * 1000L <= STALE_PACKETS_MS
    }
}
