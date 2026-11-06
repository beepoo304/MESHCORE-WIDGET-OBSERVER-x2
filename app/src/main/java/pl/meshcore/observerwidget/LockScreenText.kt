package pl.meshcore.observerwidget

/**
 * Teksty stałego powiadomienia monitoringu, widocznego na ekranie blokady.
 *
 * Zwinięte powiadomienie ma 2 linie po ok. 36–45 znaków (Roboto 14 sp, ekran ~410 dp),
 * więc tytuł i linia skrótu muszą zmieścić się w ~35 znakach:
 *  - tytuł:  „#F480 · ● LIVE” / „#F480 · ● ERROR” (4 pierwsze znaki ID Observera)
 *  - skrót:  „M1 1273/h 12s · M2 1190/h 15s”
 * Po rozwinięciu (BigTextStyle) linie brokerów „M1 738/h · 17s · 482773” (pakiety/h, wiek, total),
 * „M1 -/h · …” przy pakietach ponad czerwonym progiem, „D_M2 …” przy padniętym brokerze
 * + MAPPER z kierunkiem i odległością.
 *
 * Czysta logika (bez Androida) – testowana zwykłym JUnit.
 */
object LockScreenText {
    data class Texts(val title: String, val summary: String, val expanded: String)

    /**
     * @param nick nick mapującego dla danego pinga (z cache [MapperMonitor]); null = nieznany.
     */
    fun build(d: WidgetData, nowMs: Long, nick: (MapperPing, Int) -> String? = { _, _ -> null }): Texts {
        val title = "#${d.observerId.take(4).ifBlank { "????" }} · ${state(d, nowMs)}"

        val b1 = brokerShort(d.broker1, nowMs, d.broker1FrozenAgeMs)
        val b2 = brokerShort(d.broker2, nowMs, d.broker2FrozenAgeMs)
        val summary = "M1 $b1 · M2 $b2"

        val lines = mutableListOf(
            brokerLine("M1", d.broker1, d.broker1LastOk, d, nowMs),
            brokerLine("M2", d.broker2, d.broker2LastOk, d, nowMs)
        )
        mapperLine(d, nowMs, nick)?.let { lines += it }
        return Texts(title, summary, lines.joinToString("\n"))
    }

    /** „1273/h 12s”, przy padniętym API zamrożony wiek „— 3m05s”, bez danych „—”. */
    internal fun brokerShort(r: FetchResult?, nowMs: Long, frozenAgeMs: Long?): String = when (r) {
        is FetchResult.Ok -> {
            val age = r.snapshot.lastPacketAgeMs(nowMs)
            "${r.snapshot.packetsPerHour}/h " + (age?.let { shortAge(it) } ?: "brak")
        }
        is FetchResult.Fail -> if (frozenAgeMs != null) "— ${shortAge(frozenAgeMs)}" else "API ✕"
        null -> "…"
    }

    /**
     * „● LIVE”, gdy oba brokery odpowiadają i pakiety są świeże (poniżej pomarańczowego progu);
     * „● ERROR” przy każdym przekroczeniu progu z ustawień lub padniętym brokerze.
     */
    internal fun state(d: WidgetData, nowMs: Long): String {
        val brokers = listOf(d.broker1, d.broker2)
        if (brokers.all { it == null }) return "…"
        val ok = brokers.all { r ->
            r is FetchResult.Ok && Status.level(r.snapshot.lastPacketAgeMs(nowMs), d.orangeMin, d.redMin) == Level.GREEN
        }
        return if (ok) "● LIVE" else "● ERROR"
    }

    /**
     * Linia brokera, np. „M1 738/h · 17s · 482773” (pakiety/h, wiek ostatniego pakietu, total):
     *  - API żyje, ale pakiety ponad czerwonym progiem → „M1 -/h · 6m10s · 482773”,
     *  - broker padł → „D_M1 …” z ostatnimi znanymi wartościami (wiek dalej rośnie),
     *    bez udanego odczytu od startu → „D_M1 -/h · - · -”.
     */
    internal fun brokerLine(label: String, r: FetchResult?, lastOk: ObserverSnapshot?, d: WidgetData, nowMs: Long): String =
        when (r) {
            null -> "$label …"
            is FetchResult.Ok -> {
                val age = r.snapshot.lastPacketAgeMs(nowMs)
                val stale = Status.level(age, d.orangeMin, d.redMin) == Level.RED
                "$label ${values(r.snapshot, age, if (stale) "-" else null)}"
            }
            is FetchResult.Fail ->
                if (lastOk == null) "D_$label -/h · - · -"
                else "D_$label ${values(lastOk, lastOk.lastPacketAgeMs(nowMs), null)}"
        }

    private fun values(s: ObserverSnapshot, ageMs: Long?, perHour: String?): String =
        "${perHour ?: s.packetsPerHour}/h · ${ageMs?.let { shortAge(it) } ?: "-"} · ${s.totalPackets}"

    /** „MAPPER NE 1,6 km (nick)” – tylko gdy ktoś mapuje teraz i znamy pozycje. */
    internal fun mapperLine(d: WidgetData, nowMs: Long, nick: (MapperPing, Int) -> String?): String? {
        val m = d.mapping ?: return null
        if (!m.active) return null
        val arrow = WidgetRenderer.mappingArrow(m, nowMs)
        val oLat = m.observerLat
        val oLon = m.observerLon
        if (arrow == null || oLat == null || oLon == null) return "MAPPER aktywny"
        val (ping, idx) = arrow
        val dist = GeoMath.distanceKm(oLat, oLon, ping.lat!!, ping.lon!!)
        val dir = GeoMath.compassLabel(GeoMath.bearingDeg(oLat, oLon, ping.lat, ping.lon))
        val who = nick(ping, idx)?.let { " (${shorten(it, 15)})" }.orEmpty()
        return "MAPPER $dir ${GeoMath.km(dist)}$who"
    }

    internal fun shortAge(ageMs: Long): String = Status.formatAge(ageMs).replace(" ", "")

    internal fun shorten(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"
}
