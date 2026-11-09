package pl.meshcore.observerwidget

/**
 * Statystyki z historii mapera (zakładki Zasięg i Ludzie, dzienny raport). Czyste funkcje – testowalne.
 *
 * Jeden punkt bazy = jeden ping mapującego usłyszany przez jeden repeater (rpt). Ten sam ping słyszany
 * przez Observera i RPT1 to dwa punkty, dlatego „pingi” liczymy jako różne (mapujący, czas).
 */
object MapperStats {

    /** Mój węzeł: Observer albo RPTn, z pozycją z bazy repeaterów mapera (null = brak). */
    data class Node(val shortId: String, val label: String, val name: String?, val lat: Double?, val lon: Double?) {
        val isObserver: Boolean get() = label == "OBSERVER"
        val hasPos: Boolean get() = lat != null && lon != null
    }

    data class Record(val node: Node, val ping: MapperPing, val km: Double, val bearingDeg: Double)

    /** Najdalszy ping usłyszany przez węzeł (z całej historii). */
    fun record(points: List<MapperPing>, node: Node): Record? {
        if (!node.hasPos) return null
        var best: Record? = null
        for (p in points) {
            if (p.rptId != node.shortId || p.lat == null || p.lon == null) continue
            val km = GeoMath.distanceKm(node.lat!!, node.lon!!, p.lat, p.lon)
            if (best == null || km > best.km) best = Record(node, p, km, GeoMath.bearingDeg(node.lat, node.lon, p.lat, p.lon))
        }
        return best
    }

    data class NodeStats(val node: Node, val pings: Int, val maxKm: Double?, val avgSnr: Double?)

    /** Tabela „Observer vs RPT”: liczba pingów, najdalszy ping, średni SNR odbioru u węzła. */
    fun nodeStats(points: List<MapperPing>, node: Node): NodeStats {
        val heard = points.filter { it.rptId == node.shortId }
        val snrs = heard.mapNotNull { it.observerSnrDb }
        return NodeStats(
            node = node,
            pings = heard.size,
            maxKm = record(heard, node)?.km,
            avgSnr = if (snrs.isEmpty()) null else snrs.average()
        )
    }

    data class Rank(val userId: Long, val pings: Int, val lastTsMs: Long)

    /** Ranking mapujących usłyszanych przez moje węzły od [sinceMs]. */
    fun ranking(points: List<MapperPing>, mine: Set<String>, sinceMs: Long): List<Rank> =
        points.asSequence()
            .filter { it.tsMs >= sinceMs && it.rptId != null && it.rptId in mine }
            .groupBy { it.userId }
            .map { (user, list) -> Rank(user, list.distinctBy { it.tsMs }.size, list.maxOf { it.tsMs }) }
            .sortedWith(compareByDescending<Rank> { it.pings }.thenByDescending { it.lastTsMs })

    data class Session(
        val userId: Long,
        val startMs: Long,
        val endMs: Long,
        /** Różne pingi (po czasie) w sesji. */
        val pings: Int,
        /** Pingi usłyszane przez moje węzły. */
        val heardByMine: Int,
        /** Najbliższy punkt sesji od środka okolicy. */
        val minKm: Double,
        val bearingDeg: Double,
        /** Krótkie ID innych repeaterów, które słyszały te pingi. */
        val heardBy: Set<String>
    ) {
        val key: String get() = "$userId:$startMs"
    }

    /** Podział pingów jednego mapującego na sesje (przerwa > [MapperApi.SESSION_GAP_MS] = nowa sesja). */
    fun splitSessions(sorted: List<MapperPing>): List<List<MapperPing>> {
        val out = mutableListOf<MutableList<MapperPing>>()
        for (p in sorted) {
            val cur = out.lastOrNull()
            if (cur != null && p.tsMs - cur.last().tsMs <= MapperApi.SESSION_GAP_MS) cur.add(p) else out.add(mutableListOf(p))
        }
        return out
    }

    /**
     * „Mapował obok · nie słyszałeś”: sesje z pingiem w promieniu [radiusKm] od środka okolicy,
     * w których żaden z moich węzłów nie usłyszał ani jednego pingu. [finishedBeforeMs] – tylko sesje
     * zakończone przed tą chwilą (do powiadomień), null = wszystkie.
     */
    fun nearbyUnheard(
        points: List<MapperPing>, centerLat: Double, centerLon: Double, radiusKm: Double,
        mine: Set<String>, sinceMs: Long, finishedBeforeMs: Long? = null
    ): List<Session> = sessionsNear(points, centerLat, centerLon, radiusKm, mine, sinceMs)
        .filter { it.heardByMine == 0 && (finishedBeforeMs == null || it.endMs < finishedBeforeMs) }

    fun sessionsNear(
        points: List<MapperPing>, centerLat: Double, centerLon: Double, radiusKm: Double,
        mine: Set<String>, sinceMs: Long
    ): List<Session> {
        val out = mutableListOf<Session>()
        val byUser = points.asSequence().filter { it.tsMs >= sinceMs }.groupBy { it.userId }
        for ((user, list) in byUser) {
            for (s in splitSessions(list.sortedBy { it.tsMs })) {
                var minKm = Double.MAX_VALUE
                var bearing = 0.0
                for (p in s) {
                    if (p.lat == null || p.lon == null) continue
                    val km = GeoMath.distanceKm(centerLat, centerLon, p.lat, p.lon)
                    if (km < minKm) { minKm = km; bearing = GeoMath.bearingDeg(centerLat, centerLon, p.lat, p.lon) }
                }
                if (minKm > radiusKm) continue
                val heardMine = s.filter { it.rptId != null && it.rptId in mine }.distinctBy { it.tsMs }.size
                out.add(
                    Session(
                        userId = user, startMs = s.first().tsMs, endMs = s.last().tsMs,
                        pings = s.distinctBy { it.tsMs }.size, heardByMine = heardMine,
                        minKm = minKm, bearingDeg = bearing,
                        heardBy = s.mapNotNull { it.rptId }.filter { it !in mine }.toSet()
                    )
                )
            }
        }
        return out.sortedByDescending { it.startMs }
    }

    data class Daily(
        val mappers: Int, val pings: Int, val maxKm: Double?, val maxNode: Node?, val nearbyUnheard: Int
    )

    /** Dzienny raport za okres [fromMs, toMs): mapujący, pingi i najdalszy ping przy moich węzłach. */
    fun daily(
        points: List<MapperPing>, nodes: List<Node>, center: Pair<Double, Double>?, radiusKm: Double,
        fromMs: Long, toMs: Long
    ): Daily {
        val mine = nodes.map { it.shortId }.toSet()
        val inDay = points.filter { it.tsMs in fromMs until toMs }
        val heard = inDay.filter { it.rptId != null && it.rptId in mine }
        var maxKm: Double? = null
        var maxNode: Node? = null
        for (n in nodes) {
            val r = record(heard, n) ?: continue
            if (maxKm == null || r.km > maxKm) { maxKm = r.km; maxNode = n }
        }
        val nearby = center?.let { (la, lo) -> nearbyUnheard(inDay, la, lo, radiusKm, mine, fromMs).size } ?: 0
        return Daily(
            mappers = heard.map { it.userId }.distinct().size,
            pings = heard.distinctBy { it.userId to it.tsMs }.size,
            maxKm = maxKm, maxNode = maxNode, nearbyUnheard = nearby
        )
    }

    /** Polska odmiana: 1 ping, 2–4 pingi, 5+ pingów (12–14 pingów). */
    fun plural(n: Int, one: String, few: String, many: String): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            n == 1 -> one
            mod10 in 2..4 && mod100 !in 12..14 -> few
            else -> many
        }
    }

    fun pingsLabel(n: Int): String = "$n ${plural(n, "ping", "pingi", "pingów")}"

    fun dailyText(d: Daily): String = buildString {
        append("Wczoraj przy Twoich węzłach: ")
        append("${d.mappers} ${plural(d.mappers, "mapujący", "mapujących", "mapujących")} · ${pingsLabel(d.pings)}")
        d.maxKm?.let { km ->
            append(" · max ${GeoMath.km(km)}")
            d.maxNode?.let { append(" (${if (it.isObserver) "Observer" else it.label})") }
        }
        if (d.nearbyUnheard > 0) {
            append(" · ${d.nearbyUnheard} ${plural(d.nearbyUnheard, "sesja", "sesje", "sesji")} obok bez odbioru")
        }
    }
}
