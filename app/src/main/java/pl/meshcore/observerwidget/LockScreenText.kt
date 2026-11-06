package pl.meshcore.observerwidget

/**
 * Teksty stałego powiadomienia monitoringu, widocznego na ekranie blokady.
 *
 * Zwinięte powiadomienie ma 2 linie po ok. 36–45 znaków (Roboto 14 sp, ekran ~410 dp),
 * więc tytuł i linia skrótu muszą zmieścić się w ~35 znakach:
 *  - tytuł:  „K-ce Paderewa 2 · ● LIVE”
 *  - skrót:  „M1 1273/h 12s · M2 1190/h 15s”
 * Po rozwinięciu (BigTextStyle) pełne linie brokerów + MAPPER z kierunkiem i odległością.
 *
 * Czysta logika (bez Androida) – testowana zwykłym JUnit.
 */
object LockScreenText {
    /** Maksymalna długość nazwy Observera w tytule (dłuższe skracamy „…”). */
    const val MAX_NAME = 22

    data class Texts(val title: String, val summary: String, val expanded: String)

    /**
     * @param nick nick mapującego dla danego pinga (z cache [MapperMonitor]); null = nieznany.
     */
    fun build(d: WidgetData, nowMs: Long, nick: (MapperPing, Int) -> String? = { _, _ -> null }): Texts {
        val state = WidgetRenderer.liveState(d, nowMs).first
        val rawName = d.observer?.name?.trim().orEmpty().ifBlank { d.observerId.take(8).ifBlank { "Observer" } }
        val title = "${shorten(rawName, MAX_NAME)} · $state"

        val b1 = brokerShort(d.broker1, nowMs, d.broker1FrozenAgeMs)
        val b2 = brokerShort(d.broker2, nowMs, d.broker2FrozenAgeMs)
        val summary = "M1 $b1 · M2 $b2"

        val lines = mutableListOf(
            "MQTT1 ${brokerLong(d.broker1, nowMs, d.broker1FrozenAgeMs)}",
            "MQTT2 ${brokerLong(d.broker2, nowMs, d.broker2FrozenAgeMs)}"
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

    /** „1273/h · ostatni 12s temu”. */
    internal fun brokerLong(r: FetchResult?, nowMs: Long, frozenAgeMs: Long?): String = when (r) {
        is FetchResult.Ok -> {
            val age = r.snapshot.lastPacketAgeMs(nowMs)
            "${r.snapshot.packetsPerHour}/h · " + (age?.let { "ostatni ${shortAge(it)} temu" } ?: "brak pakietów")
        }
        is FetchResult.Fail -> if (frozenAgeMs != null) "API ✕ · ostatni ${shortAge(frozenAgeMs)} temu" else "API ✕"
        null -> "…"
    }

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
