package pl.meshcore.observerwidget

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Klient API CoreScope: GET {host}/api/observers/{pełny hash}.
 * Struktura odpowiedzi ustalona z kodu źródłowego CoreScope (ObserverResp):
 * id, name, model, firmware, packet_count, packetsLastHour, last_packet_at, last_seen.
 */
object ObserverApi {

    /** Próbuje ID wielkimi literami, a przy 404 także małymi (serwery rozróżniają wielkość liter). */
    fun fetch(host: String, id: String, timeoutMs: Int): FetchResult {
        var last: FetchResult = FetchResult.Fail(host, tr("Brak identyfikatora", "No identifier"))
        for (variant in Config.idVariants(id)) {
            last = fetchOne(host, variant, timeoutMs)
            if (last is FetchResult.Ok || !(last is FetchResult.Fail && last.reason.contains("404"))) return last
        }
        return last
    }

    private fun fetchOne(host: String, id: String, timeoutMs: Int): FetchResult {
        val url = try {
            URL("${Config.baseUrl(host)}/api/observers/${URLEncoder.encode(id, "UTF-8")}")
        } catch (e: Exception) {
            return FetchResult.Fail(host, tr("Zły adres API", "Invalid API address"))
        }
        return try {
            val c = url.openConnection() as HttpURLConnection
            try {
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.requestMethod = "GET"
                c.useCaches = false
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("Cache-Control", "no-cache, no-store")
                c.setRequestProperty("Pragma", "no-cache")
                val code = c.responseCode
                when {
                    code == 404 -> FetchResult.Fail(host, tr("Observer nieznany na serwerze (404)", "Observer unknown to server (404)"))
                    code !in 200..299 -> FetchResult.Fail(host, "HTTP $code")
                    else -> {
                        val body = c.inputStream.bufferedReader().use { it.readText() }
                        val localNow = System.currentTimeMillis()
                        when (val r = parse(host, body, localNow)) {
                            is FetchResult.Ok -> FetchResult.Ok(
                                r.snapshot.copy(serverOffsetMs = clockOffset(c.getHeaderFieldDate("Date", 0L), localNow))
                            )
                            else -> r
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
        } catch (e: SocketTimeoutException) {
            FetchResult.Fail(host, "Timeout")
        } catch (e: IOException) {
            FetchResult.Fail(host, tr("Brak połączenia", "No connection"))
        } catch (e: Exception) {
            FetchResult.Fail(host, tr("Błąd: ${e.javaClass.simpleName}", "Error: ${e.javaClass.simpleName}"))
        }
    }

    fun parse(host: String, body: String, nowMs: Long = System.currentTimeMillis()): FetchResult {
        val o = try {
            JSONObject(body)
        } catch (e: JSONException) {
            return FetchResult.Fail(host, tr("Niepoprawny JSON", "Invalid JSON"))
        }
        fun str(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { key ->
                if (!o.has(key) || o.isNull(key)) null
                else o.optString(key).trim().takeIf { it.isNotEmpty() }
            }

        val id = str("id", "observer_id", "observerId", "public_key", "publicKey", "hash").orEmpty()
        if (id.isBlank()) return FetchResult.Fail(host, tr("Brak identyfikatora Observera w JSON", "No Observer identifier in JSON"))

        val lastRaw = str(
            "last_packet_at", "lastPacketAt", "last_packet", "lastPacket",
            "last_seen", "lastSeen", "last_heard_at", "lastHeardAt"
        )
        val last = lastRaw?.let { parseTime(it) }
        return FetchResult.Ok(
            ObserverSnapshot(
                host = host,
                id = id,
                name = str("name", "display_name", "displayName"),
                model = str("model", "device", "device_type", "deviceType"),
                firmware = str("firmware", "fw"),
                totalPackets = longValue(o, "packet_count", "packetCount", "total_packets", "totalPackets", "packets_total"),
                packetsPerHour = longValue(o, "packetsLastHour", "packets_per_hour", "packetsPerHour", "packets_hour"),
                lastPacketMs = last,
                fetchedAtMs = nowMs
            )
        )
    }

    /**
     * Nagłówek Date ma dokładność 1 s, więc drobne różnice ignorujemy (zostawiamy zegar telefonu),
     * a korygujemy dopiero wyraźnie przestawiony zegar.
     */
    fun clockOffset(serverDateMs: Long, localNowMs: Long): Long {
        if (serverDateMs <= 0L) return 0L
        val offset = serverDateMs - localNowMs
        return if (kotlin.math.abs(offset) < 5_000L) 0L else offset
    }

    private fun longValue(o: JSONObject, vararg keys: String): Long {
        for (key in keys) {
            if (!o.has(key) || o.isNull(key)) continue
            val value = o.opt(key)
            if (value is Number) return value.toLong()
            value?.toString()?.toLongOrNull()?.let { return it }
        }
        return 0L
    }

    /** Akceptuje epoch w sekundach/milisekundach oraz ISO-8601 i yyyy-MM-dd HH:mm:ss (UTC). */
    fun parseTime(raw: String): Long? {
        val t = raw.trim()
        t.toLongOrNull()?.let { value ->
            return if (value in 1_000_000_000L..99_999_999_999L) value * 1000L
            else if (value >= 100_000_000_000L) value
            else null
        }
        return try {
            Instant.parse(t).toEpochMilli()
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(t).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try {
                    LocalDateTime.parse(t.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
