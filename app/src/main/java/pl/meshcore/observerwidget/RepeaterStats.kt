package pl.meshcore.observerwidget

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Statystyki jednego repeatera (Observer albo RPT1–RPT5) — te same co „Statystyki repeatera”
 * na stronie mapera (app.js rstatCompute), liczone w aplikacji z pełnej bazy punktów.
 * Czysta logika (bez Androida), testowana JUnit.
 */
object RepeaterStats {
    /** Promień kratki pokrycia (m) i jej powierzchnia (km²) — jak RS_HEX na stronie mapera. */
    const val HEX_M = 250.0
    val HEX_KM2 = 3 * sqrt(3.0) / 2 * HEX_M * HEX_M / 1e6

    /** Minimum odpowiedzi z dobrym sygnałem do policzenia praktycznego zasięgu. */
    const val PRACTICAL_MIN = 5
    const val GOOD_SNR = -5.0
    /** Bilans łącza: różnica średnich SNR w tym zakresie = łącze zrównoważone. */
    const val BALANCED_DB = 4.0
    const val BALANCE_MIN = 5

    data class Far(val km: Double, val snr: Double?)

    data class Result(
        /** Pingi z odpowiedzią (różne pary mapujący+czas). */
        val pings: Int,
        /** Pomiary (wiersze bazy) tego repeatera. */
        val measurements: Int,
        val users: Int,
        val days: Int,
        val far: Far?,
        val practicalKm: Double?,
        /** SNR: my słyszymy repeater (snr). */
        val downAvg: Double?,
        val downBest: Double?,
        /** SNR: repeater słyszy nas (usnr). */
        val upAvg: Double?,
        val upBest: Double?,
        val upCount: Int,
        val rssiAvg: Double?,
        val hexes: Int,
        val onlyHexes: Int,
        val lastMs: Long,
        /** Pingi w jego kratkach po ostatniej odpowiedzi (do ostrzeżenia o milczeniu). */
        val silentPings: Int,
        /** Top mapujący: nick (albo #id) → liczba pomiarów, najwięcej pierwsze. */
        val topUsers: List<Pair<String, Int>>
    ) {
        val coverKm2: Double get() = hexes * HEX_KM2
        val onlyKm2: Double get() = onlyHexes * HEX_KM2
        /** Bilans łącza liczony, gdy jest dość pomiarów w obie strony. */
        val hasBalance: Boolean get() = upAvg != null && downAvg != null && upCount >= BALANCE_MIN
        val balanceDiff: Double? get() = if (hasBalance) downAvg!! - upAvg!! else null
    }

    /**
     * [rptShortId] = 16 pierwszych znaków klucza (małe litery), [rptLat]/[rptLon] = pozycja z bazy
     * mapera (null = bez zasięgów), [fromMs] = początek okresu (0 = całość).
     */
    fun compute(
        points: List<MapperPing>,
        users: Map<Long, String>,
        rptShortId: String,
        rptLat: Double?,
        rptLon: Double?,
        fromMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Result? {
        val id = rptShortId.lowercase()
        data class Ping(val tsMs: Long, val hex: Long)
        val pings = HashMap<Pair<Long, Long>, Ping>()
        val hexRpts = HashMap<Long, MutableSet<String>>()
        val mine = ArrayList<MapperPing>()
        for (p in points) {
            if (p.tsMs < fromMs) continue
            val lat = p.lat ?: continue
            val lon = p.lon ?: continue
            val k = p.userId to p.tsMs
            val pg = pings.getOrPut(k) { Ping(p.tsMs, hexKey(lat, lon)) }
            val r = p.rptId ?: continue
            hexRpts.getOrPut(pg.hex) { HashSet() }.add(r)
            if (r == id) mine.add(p)
        }
        if (mine.isEmpty()) return null

        val byUser = HashMap<Long, Int>()
        val days = HashSet<java.time.LocalDate>()
        val myPings = HashSet<Pair<Long, Long>>()
        val myHex = HashSet<Long>()
        val down = ArrayList<Double>()
        val up = ArrayList<Double>()
        val rssi = ArrayList<Double>()
        val good = ArrayList<Double>()
        var far: Far? = null
        var last = 0L
        for (p in mine) {
            val k = p.userId to p.tsMs
            last = maxOf(last, p.tsMs)
            byUser[p.userId] = (byUser[p.userId] ?: 0) + 1
            days.add(Instant.ofEpochMilli(p.tsMs).atZone(zone).toLocalDate())
            myPings.add(k)
            myHex.add(pings.getValue(k).hex)
            p.snrDb?.let { down.add(it) }
            p.observerSnrDb?.let { up.add(it) }
            p.rssiDbm?.let { rssi.add(it) }
            if (rptLat != null && rptLon != null) {
                val d = GeoMath.distanceKm(rptLat, rptLon, p.lat!!, p.lon!!)
                if (far == null || d > far.km) far = Far(d, p.snrDb)
                if ((p.snrDb ?: Double.NEGATIVE_INFINITY) >= GOOD_SNR) good.add(d)
            }
        }
        good.sort()
        val practical = if (good.size >= PRACTICAL_MIN) good[floor(good.size * 0.75).toInt()] else null
        val silent = pings.values.count { it.tsMs > last && it.hex in myHex }
        return Result(
            pings = myPings.size,
            measurements = mine.size,
            users = byUser.size,
            days = days.size,
            far = far,
            practicalKm = practical,
            downAvg = down.average().takeIf { down.isNotEmpty() },
            downBest = down.maxOrNull(),
            upAvg = up.average().takeIf { up.isNotEmpty() },
            upBest = up.maxOrNull(),
            upCount = up.size,
            rssiAvg = rssi.average().takeIf { rssi.isNotEmpty() },
            hexes = myHex.size,
            onlyHexes = myHex.count { hexRpts[it]?.size == 1 },
            lastMs = last,
            silentPings = silent,
            topUsers = byUser.entries
                .map { (u, c) -> (users[u]?.takeIf { it.isNotBlank() } ?: "#$u") to c }
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
                .take(5)
        )
    }

    // Siatka heksagonalna jak na stronie mapera (hexKey: punkt odniesienia 50°N 19°E).
    private const val LAT0 = 50.0
    private const val LON0 = 19.0
    private val KX = 111320 * kotlin.math.cos(Math.toRadians(50.0))
    private const val KY = 110574.0
    private val SQ3 = sqrt(3.0)

    /** Kratka (q, r) spakowana w jeden Long. */
    fun hexKey(lat: Double, lon: Double, size: Double = HEX_M): Long {
        val x = (lon - LON0) * KX
        val y = (lat - LAT0) * KY
        val q = (SQ3 / 3 * x - y / 3) / size
        val r = (2.0 / 3 * y) / size
        val s = -q - r
        var rq = jsRound(q)
        var rr = jsRound(r)
        val rs = jsRound(s)
        val dq = abs(rq - q)
        val dr = abs(rr - r)
        val ds = abs(rs - s)
        if (dq > dr && dq > ds) rq = -rr - rs else if (dr > ds) rr = -rq - rs
        return (rq.toLong() shl 32) or (rr.toLong() and 0xFFFFFFFFL)
    }

    /** Math.round z JS (połówki w górę). */
    private fun jsRound(v: Double): Double = floor(v + 0.5)
}
