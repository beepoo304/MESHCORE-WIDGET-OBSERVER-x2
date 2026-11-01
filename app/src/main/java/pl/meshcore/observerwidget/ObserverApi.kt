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

    fun fetch(host: String, id: String, timeoutMs: Int): FetchResult {
        val url = try {
            URL("${Config.baseUrl(host)}/api/observers/${URLEncoder.encode(id, "UTF-8")}")
        } catch (e: Exception) {
            return FetchResult.Fail(host, "Zły adres API")
        }
        return try {
            val c = url.openConnection() as HttpURLConnection
            try {
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.requestMethod = "GET"
                c.setRequestProperty("Accept", "application/json")
                val code = c.responseCode
                when {
                    code == 404 -> FetchResult.Fail(host, "Observer nieznany na serwerze (404)")
                    code !in 200..299 -> FetchResult.Fail(host, "HTTP $code")
                    else -> parse(host, c.inputStream.bufferedReader().use { it.readText() })
                }
            } finally {
                c.disconnect()
            }
        } catch (e: SocketTimeoutException) {
            FetchResult.Fail(host, "Timeout")
        } catch (e: IOException) {
            FetchResult.Fail(host, "Brak połączenia")
        } catch (e: Exception) {
            FetchResult.Fail(host, "Błąd: ${e.javaClass.simpleName}")
        }
    }

    fun parse(host: String, body: String, nowMs: Long = System.currentTimeMillis()): FetchResult {
        val o = try {
            JSONObject(body)
        } catch (e: JSONException) {
            return FetchResult.Fail(host, "Niepoprawny JSON")
        }
        fun str(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { key ->
                if (!o.has(key) || o.isNull(key)) null
                else o.optString(key).trim().takeIf { it.isNotEmpty() }
            }

        val id = str("id", "observer_id", "observerId", "public_key", "publicKey", "hash").orEmpty()
        if (id.isBlank()) return FetchResult.Fail(host, "Brak identyfikatora Observera w JSON")

        val last = str(
            "last_packet_at", "lastPacketAt", "last_packet", "lastPacket",
            "last_seen", "lastSeen", "last_heard_at", "lastHeardAt"
        )?.let { parseTime(it) }
        return FetchResult.Ok(
            ObserverSnapshot(
                host = host,
                id = id,
                name = str("name", "display_name", "displayName"),
                model = str("model", "device", "device_type", "deviceType"),
                firmware = str("firmware", "fw"),
                totalPackets = o.optLong("packet_count", o.optLong("packetCount", o.optLong("total_packets", 0L))),
                packetsPerHour = o.optLong("packetsLastHour", o.optLong("packets_per_hour", o.optLong("packetsPerHour", 0L))),
                lastPacketMs = last,
                fetchedAtMs = nowMs
            )
        )
    }

    /** Akceptuje ISO-8601 z strefą oraz "yyyy-MM-dd HH:mm:ss" (traktowane jako UTC). */
    fun parseTime(raw: String): Long? {
        val t = raw.trim()
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
