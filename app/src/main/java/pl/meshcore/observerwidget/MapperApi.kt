package pl.meshcore.observerwidget

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Klient publicznego API mapera MeshCore South (https://meshcoresouth.pl/mapper/).
 *
 * Mapper (companion z GPS) wysyła pingi odkrywania; repeater odpowiada, a pomiar trafia
 * do publicznej bazy: GET api.php?action=points&since={id}&limit=20000.
 *
 * Punkt to tablica: [id, unix_s, lat, lon, rpt, snr, usnr, rssi, user, acc, nf, prof]
 *  - rpt  – 16-znakowy ID repeatera (pierwsze 16 znaków klucza publicznego, małe litery);
 *            punkt z rpt == ID naszego Observera = Observer odebrał ping mapowania i odpowiedział,
 *  - snr  – SNR odbioru: jak companion mapera słyszy repeatera (dB),
 *  - usnr – SNR u repeatera: jak repeater (nasz Observer) słyszy companiona (dB),
 *  - rssi – RSSI odbioru u companiona (dBm),
 *  - user – ID konta mapującego; nicki są w mapie „users” odpowiedzi.
 */
object MapperApi {

    /** Przerwa między pingami dłuższa niż ta wartość = koniec sesji mapowania (pingi idą co 20–120 s). */
    const val SESSION_GAP_MS = 5 * 60_000L

    /** Po takim czasie od ostatniego pinga ikona MAPPER znika z widgetu (szybciej niż koniec sesji). */
    const val ACTIVE_MS = 3 * 60_000L

    /** Jak długo trzymamy pomiary w pamięci (na potrzeby panelu w aplikacji – karta widać 24 h). */
    const val KEEP_MS = 24 * 60 * 60_000L

    data class PollResult(
        /** Najnowszy ID punktu w bazie (globalnie) – punkt startowy kolejnego odpytania. */
        val lastId: Long,
        val rev: Int,
        val more: Boolean,
        val points: List<MapperPing>,
        /** ID konta mapującego → nick. */
        val users: Map<Long, String>
    )

    /** Odpytanie o punkty nowsze niż [sinceId]; null, gdy serwer nie odpowie lub odpowie błędem. */
    fun poll(sinceId: Long, timeoutMs: Int): PollResult? {
        val url = try {
            URL("https://meshcoresouth.pl/mapper/api.php?action=points&since=$sinceId&limit=20000")
        } catch (_: Exception) {
            return null
        }
        return try {
            val c = url.openConnection() as HttpURLConnection
            try {
                c.connectTimeout = timeoutMs.coerceAtLeast(1)
                c.readTimeout = timeoutMs.coerceAtLeast(1)
                c.requestMethod = "GET"
                c.useCaches = false
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("Cache-Control", "no-cache, no-store")
                val code = c.responseCode
                if (code !in 200..299) null else parse(c.inputStream.bufferedReader().use { it.readText() })
            } finally {
                c.disconnect()
            }
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    fun parse(body: String): PollResult? {
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return null
        }
        if (!root.optBoolean("ok", false)) return null

        val users = mutableMapOf<Long, String>()
        root.optJSONObject("users")?.let { u ->
            for (key in u.keys()) {
                val id = key.toLongOrNull() ?: continue
                val nick = u.optString(key).trim()
                if (nick.isNotEmpty()) users[id] = nick
            }
        }

        val arr = root.optJSONArray("points") ?: JSONArray()
        val points = mutableListOf<MapperPing>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONArray(i) ?: continue
            points.add(
                MapperPing(
                    id = p.optLong(0),
                    tsMs = p.optLong(1) * 1000L,
                    lat = num(p, 2),
                    lon = num(p, 3),
                    rptId = if (p.isNull(4)) null else p.optString(4).trim().lowercase().ifEmpty { null },
                    snrDb = num(p, 5),
                    observerSnrDb = num(p, 6),
                    rssiDbm = num(p, 7),
                    userId = p.optLong(8),
                    accM = num(p, 9),
                    noiseFloorDb = num(p, 10)
                )
            )
        }
        return PollResult(
            lastId = root.optLong("last", 0L),
            rev = root.optInt("rev", 0),
            more = root.optBoolean("more", false),
            points = points,
            users = users
        )
    }

    private fun num(p: JSONArray, index: Int): Double? {
        if (p.isNull(index)) return null
        val d = p.optDouble(index, Double.NaN)
        return if (d.isNaN()) null else d
    }

    /** ID repeatera/obserwatora w bazie mapera: pierwsze 16 znaków klucza publicznego; puste dla krótszych. */
    fun shortId(observerId: String): String =
        observerId.trim().lowercase().let { if (it.length >= 16) it.take(16) else "" }

    /**
     * Pozycja repeatera/Observera z bazy mapera: GET api.php?action=repeaters.
     * Zwraca [lat, lon]; null = serwer nie odpowiada, odpowiedział błędem albo
     * Observera nie ma w bazie (różnicujemy to w [parseRepeaterPosition] flagą found).
     */
    fun repeaterPosition(shortId: String, timeoutMs: Int): RepeaterPos? {
        if (shortId.length != 16) return null
        val url = try {
            URL("https://meshcoresouth.pl/mapper/api.php?action=repeaters")
        } catch (_: Exception) {
            return null
        }
        return try {
            val c = url.openConnection() as HttpURLConnection
            try {
                c.connectTimeout = timeoutMs.coerceAtLeast(1)
                c.readTimeout = timeoutMs.coerceAtLeast(1)
                c.requestMethod = "GET"
                c.useCaches = false
                c.setRequestProperty("Accept", "application/json")
                val code = c.responseCode
                if (code !in 200..299) null else parseRepeaterPosition(c.inputStream.bufferedReader().use { it.readText() }, shortId)
            } finally {
                c.disconnect()
            }
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Wynik szukania pozycji: found=false = serwer odpowiedział, ale nie ma takiego repeatera w bazie. */
    data class RepeaterPos(val lat: Double, val lon: Double, val found: Boolean)

    fun parseRepeaterPosition(body: String, shortId: String): RepeaterPos? {
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return null
        }
        if (!root.optBoolean("ok", false)) return null
        val arr = root.optJSONArray("repeaters") ?: return null
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            if (!r.optString("id").trim().lowercase().startsWith(shortId)) continue
            val lat = r.optDouble("lat", Double.NaN)
            val lon = r.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return RepeaterPos(0.0, 0.0, found = false)
            return RepeaterPos(lat, lon, found = true)
        }
        return RepeaterPos(0.0, 0.0, found = false)
    }
}
