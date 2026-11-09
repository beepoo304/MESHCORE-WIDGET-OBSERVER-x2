package pl.meshcore.observerwidget

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Pełna historia bazy mapera MeshCore South dla zakładek Zasięg i Ludzie.
 *
 * Pierwsze uruchomienie pobiera całą bazę (kilka stron po 20 000 punktów) i zapisuje na dysk tylko
 * punkty istotne dla nas: odebrane przez Observera lub moje RPT albo leżące do [KEEP_RADIUS_KM]
 * od Observera (do „Mapował obok · nie słyszałeś”). Kolejne synchronizacje dociągają tylko nowe punkty.
 * Zmiana Observera, listy RPT albo rewizji bazy = pobranie od nowa.
 */
object MapperHistory {
    /** Promień zapisu punktów wokół Observera (maksymalny promień okolicy w ustawieniach). */
    const val KEEP_RADIUS_KM = 50.0

    private const val PREFS = "mapper_history"
    private const val FILE_POINTS = "mapper_history.txt"
    private const val FILE_NICKS = "mapper_history_nicks.json"
    private const val FILE_REPEATERS = "mapper_repeaters.json"
    private const val MAX_PAGES = 20
    private const val HTTP_TIMEOUT_MS = 30_000
    private const val REPEATERS_TTL_MS = 6 * 60 * 60_000L

    /** Repeater z bazy mapera: nazwa i pozycja (null = brak pozycji). */
    data class Repeater(val shortId: String, val name: String?, val lat: Double?, val lon: Double?)

    data class Snapshot(
        val points: List<MapperPing>,
        val nicks: Map<Long, String>,
        val repeaters: Map<String, Repeater>,
        val syncedAtMs: Long
    ) {
        fun nick(userId: Long): String = nicks[userId] ?: MapperMonitor.nick(userId) ?: "user $userId"
    }

    private val lock = Any()
    @Volatile private var cache: Snapshot? = null

    /** Ostatni wczytany stan (null = jeszcze nie ma historii na dysku). */
    fun snapshot(ctx: Context): Snapshot? {
        cache?.let { return it }
        synchronized(lock) {
            cache?.let { return it }
            val app = ctx.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean("complete", false)) return null
            val snap = Snapshot(
                points = readPoints(File(app.filesDir, FILE_POINTS)),
                nicks = nicksFromJson(readText(File(app.filesDir, FILE_NICKS))),
                repeaters = repeatersFromJson(readText(File(app.filesDir, FILE_REPEATERS))),
                syncedAtMs = prefs.getLong("synced_at", 0L)
            )
            cache = snap
            return snap
        }
    }

    fun lastSyncMs(ctx: Context): Long =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("synced_at", 0L)

    /** Krótkie ID moich węzłów: Observer + RPT1–RPT5 (bez pustych i duplikatów). */
    fun myShorts(cfg: Config): List<String> =
        (listOf(MapperApi.shortId(cfg.observerId)) + cfg.rptIds.toSortedMap().values.map { MapperApi.shortId(it) })
            .filter { it.length == 16 }.distinct()

    /**
     * Synchronizacja z bazą mapera. true = historia jest aktualna (pobrana lub uzupełniona).
     * Błąd sieci zostawia poprzedni stan na dysku bez zmian.
     */
    fun sync(ctx: Context): Boolean = synchronized(lock) {
        val app = ctx.applicationContext
        val cfg = Config(app)
        val mine = myShorts(cfg)
        if (mine.isEmpty()) return false
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        var repeaters = repeatersFromJson(readText(File(app.filesDir, FILE_REPEATERS)))
        if (repeaters.isEmpty() || now - prefs.getLong("repeaters_at", 0L) > REPEATERS_TTL_MS) {
            MapperApi.repeatersBody(HTTP_TIMEOUT_MS)?.let { body ->
                val parsed = parseRepeaters(body)
                if (parsed.isNotEmpty()) {
                    repeaters = parsed
                    File(app.filesDir, FILE_REPEATERS).writeText(repeatersToJson(parsed))
                    prefs.edit().putLong("repeaters_at", now).apply()
                }
            }
        }
        val center = centerOf(mine, repeaters)
        val signature = mine.sorted().joinToString(",") + "|" +
            (center?.let { String.format(java.util.Locale.US, "%.3f,%.3f", it.first, it.second) } ?: "-")

        var full = !prefs.getBoolean("complete", false) || prefs.getString("signature", null) != signature
        val mineSet = mine.toSet()
        val relevant: (MapperPing) -> Boolean = { p ->
            (p.rptId != null && p.rptId in mineSet) ||
                (center != null && p.lat != null && p.lon != null &&
                    GeoMath.distanceKm(center.first, center.second, p.lat, p.lon) <= KEEP_RADIUS_KM)
        }
        val nicks = HashMap(if (full) emptyMap() else nicksFromJson(readText(File(app.filesDir, FILE_NICKS))))
        val points = ArrayList<MapperPing>()
        val ids = HashSet<Long>()
        if (!full) readPoints(File(app.filesDir, FILE_POINTS)).forEach { if (ids.add(it.id)) points.add(it) }
        var since = if (full) 0L else prefs.getLong("last_id", 0L)
        var rev = prefs.getInt("rev", 0)
        var pages = 0
        var restarted = false
        while (pages < MAX_PAGES) {
            pages++
            val r = MapperApi.poll(since, HTTP_TIMEOUT_MS) ?: return false
            if (!full && r.rev != 0 && rev != 0 && r.rev != rev && !restarted) {
                // Zmiana rewizji bazy (usunięte dane): pobieramy wszystko od nowa.
                full = true; restarted = true
                points.clear(); ids.clear(); nicks.clear()
                since = 0L; pages = 0
                continue
            }
            if (r.rev != 0) rev = r.rev
            nicks.putAll(r.users)
            for (p in r.points) if (relevant(p) && ids.add(p.id)) points.add(p)
            val pageMax = r.points.maxOfOrNull { it.id } ?: 0L
            val next = if (r.more && pageMax > since) pageMax else maxOf(r.lastId, pageMax, since)
            if (!r.more || next <= since) { since = next; break }
            since = next
        }
        points.sortBy { it.tsMs }
        writePoints(File(app.filesDir, FILE_POINTS), points)
        File(app.filesDir, FILE_NICKS).writeText(nicksToJson(nicks))
        prefs.edit()
            .putBoolean("complete", true)
            .putString("signature", signature)
            .putLong("last_id", since)
            .putInt("rev", rev)
            .putLong("synced_at", now)
            .apply()
        cache = Snapshot(points, nicks, repeaters, now)
        true
    }

    /** Środek „okolicy”: pozycja Observera, a gdy go nie ma w bazie – pierwszego RPT z pozycją. */
    fun centerOf(mine: List<String>, repeaters: Map<String, Repeater>): Pair<Double, Double>? {
        for (s in mine) {
            val r = repeaters[s] ?: continue
            if (r.lat != null && r.lon != null) return r.lat to r.lon
        }
        return null
    }

    /** Lista repeaterów z action=repeaters: krótkie ID (16 znaków, małe litery) → nazwa i pozycja. */
    fun parseRepeaters(body: String): Map<String, Repeater> {
        val root = try { JSONObject(body) } catch (_: Exception) { return emptyMap() }
        if (!root.optBoolean("ok", false)) return emptyMap()
        val arr = root.optJSONArray("repeaters") ?: return emptyMap()
        val out = HashMap<String, Repeater>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val short = MapperApi.shortId(o.optString("id"))
            if (short.length != 16) continue
            val name = o.optString("name", "").trim().takeIf { it.isNotEmpty() && it != "null" }
            val lat = o.optNullableDouble("lat")?.takeIf { !it.isNaN() }
            val lon = o.optNullableDouble("lon")?.takeIf { !it.isNaN() }
            out[short] = Repeater(short, name, lat, lon)
        }
        return out
    }

    internal fun repeatersToJson(map: Map<String, Repeater>): String {
        val o = JSONObject()
        for ((id, r) in map) {
            val j = JSONObject()
            r.name?.let { j.put("n", it) }
            r.lat?.let { j.put("la", it) }
            r.lon?.let { j.put("lo", it) }
            o.put(id, j)
        }
        return o.toString()
    }

    internal fun repeatersFromJson(json: String?): Map<String, Repeater> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(json)
            val out = HashMap<String, Repeater>()
            for (key in o.keys()) {
                val j = o.optJSONObject(key) ?: continue
                out[key] = Repeater(
                    key,
                    if (j.has("n") && !j.isNull("n")) j.getString("n") else null,
                    j.optNullableDouble("la"), j.optNullableDouble("lo")
                )
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Zapis punktów: jedna linia = id;ts;lat;lon;rpt;snr;usnr;user (puste pole = brak). */
    internal fun pointLine(p: MapperPing): String =
        listOf(
            p.id.toString(), p.tsMs.toString(), p.lat?.toString().orEmpty(), p.lon?.toString().orEmpty(),
            p.rptId.orEmpty(), p.snrDb?.toString().orEmpty(), p.observerSnrDb?.toString().orEmpty(), p.userId.toString()
        ).joinToString(";")

    internal fun parseLine(line: String): MapperPing? {
        val f = line.split(';')
        if (f.size < 8) return null
        return try {
            MapperPing(
                id = f[0].toLong(), tsMs = f[1].toLong(),
                lat = f[2].toDoubleOrNull(), lon = f[3].toDoubleOrNull(),
                rptId = f[4].ifEmpty { null },
                snrDb = f[5].toDoubleOrNull(), observerSnrDb = f[6].toDoubleOrNull(),
                rssiDbm = null, userId = f[7].toLong(), accM = null, noiseFloorDb = null
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun writePoints(file: File, points: List<MapperPing>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.bufferedWriter().use { w -> for (p in points) { w.write(pointLine(p)); w.newLine() } }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun readPoints(file: File): List<MapperPing> {
        if (!file.exists()) return emptyList()
        return try {
            file.bufferedReader().useLines { lines -> lines.mapNotNull { parseLine(it) }.toList() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readText(file: File): String? = try { if (file.exists()) file.readText() else null } catch (_: Exception) { null }

    /** Zmiana Observera / RPT: historia pobierze się od nowa przy następnej synchronizacji. */
    fun invalidate(ctx: Context) {
        synchronized(lock) {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("signature", "").apply()
        }
    }
}
