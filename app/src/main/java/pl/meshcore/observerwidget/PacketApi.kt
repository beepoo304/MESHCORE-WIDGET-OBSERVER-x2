package pl.meshcore.observerwidget

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

data class LivePacket(
    val timeMs: Long?,
    val type: String,
    val sender: String,
    val hash: String,
    val snr: Double?,
    val rssi: Double?
)

sealed class PacketsResult {
    data class Ok(val host: String, val packets: List<LivePacket>) : PacketsResult()
    data class Fail(val host: String, val reason: String) : PacketsResult()
}

/** GET {host}/api/packets?observer={hash}&limit=250 (CoreScope: {"packets":[...],"total":n}). */
object PacketApi {
    const val LIMIT = 250

    private val TYPE_NAMES = mapOf(
        0 to "REQ", 1 to "RESPONSE", 2 to "TXT_MSG", 3 to "ACK", 4 to "ADVERT", 5 to "GRP_TXT",
        6 to "GRP_DATA", 7 to "ANON_REQ", 8 to "PATH", 9 to "TRACE", 10 to "MULTIPART",
        11 to "CONTROL", 15 to "RAW_CUSTOM"
    )

    /** Próbuje ID wielkimi literami; gdy lista jest pusta, także małymi (serwery rozróżniają wielkość liter). */
    fun fetch(host: String, id: String, timeoutMs: Int): PacketsResult {
        var last: PacketsResult = PacketsResult.Fail(host, "Brak identyfikatora")
        for (variant in Config.idVariants(id)) {
            last = fetchOne(host, variant, timeoutMs)
            if (last !is PacketsResult.Ok || last.packets.isNotEmpty()) return last
        }
        return last
    }

    private fun fetchOne(host: String, id: String, timeoutMs: Int): PacketsResult {
        val url = try {
            URL("${Config.baseUrl(host)}/api/packets?observer=${URLEncoder.encode(id, "UTF-8")}&limit=$LIMIT")
        } catch (e: Exception) {
            return PacketsResult.Fail(host, "Zły adres API")
        }
        return try {
            val c = url.openConnection() as HttpURLConnection
            try {
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.requestMethod = "GET"
                c.setRequestProperty("Accept", "application/json")
                val code = c.responseCode
                if (code !in 200..299) PacketsResult.Fail(host, "HTTP $code")
                else parse(host, c.inputStream.bufferedReader().use { it.readText() })
            } finally {
                c.disconnect()
            }
        } catch (e: SocketTimeoutException) {
            PacketsResult.Fail(host, "Timeout")
        } catch (e: IOException) {
            PacketsResult.Fail(host, "Brak połączenia")
        } catch (e: Exception) {
            PacketsResult.Fail(host, "Błąd: ${e.javaClass.simpleName}")
        }
    }

    fun parse(host: String, body: String): PacketsResult {
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            return PacketsResult.Fail(host, "Niepoprawny JSON")
        }
        val arr = root.optJSONArray("packets") ?: return PacketsResult.Fail(host, "Brak pola packets")
        val out = ArrayList<LivePacket>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val ts = listOf("timestamp", "first_seen")
                .firstNotNullOfOrNull { k -> if (p.isNull(k)) null else ObserverApi.parseTime(p.optString(k)) }
            val typeNum = if (p.isNull("payload_type")) -1 else p.optInt("payload_type", -1)
            val decoded = try {
                if (p.isNull("decoded_json")) null else JSONObject(p.optString("decoded_json"))
            } catch (_: JSONException) {
                null
            }
            fun d(k: String) = decoded?.takeIf { !it.isNull(k) }?.optString(k)?.trim()?.takeIf { it.isNotEmpty() }
            val sender = d("name") ?: d("pubKey")?.take(8) ?: d("srcPubKey")?.take(8) ?: "—"
            fun num(k: String): Double? = if (!p.has(k) || p.isNull(k)) null else p.optDouble(k).takeIf { !it.isNaN() }
            out.add(
                LivePacket(
                    timeMs = ts,
                    type = TYPE_NAMES[typeNum] ?: if (typeNum >= 0) "TYPE $typeNum" else "—",
                    sender = sender,
                    hash = p.optString("hash", "").take(8),
                    snr = num("snr"),
                    rssi = num("rssi")
                )
            )
        }
        out.sortByDescending { it.timeMs ?: Long.MIN_VALUE }
        return PacketsResult.Ok(host, out.take(LIMIT))
    }
}
