package pl.meshcore.observerwidget

import org.json.JSONObject

/**
 * Pełna historia mapowań z publicznej bazy mapera (meshcoresouth.pl/mapper) — pod zakładkę MAP.
 *
 * [MapperMonitor] trzyma tylko pingi przy naszym Observerze i RPT1–RPT5 (do karty LOG).
 * MAP potrzebuje całych tras: wszystkich punktów danego mapującego, niezależnie od tego,
 * który repeater go usłyszał. Dlatego pobieramy pełną bazę punktów od zera (API zwraca
 * ją stronami po 20 tys.) i składamy w sesje per użytkownik — tak jak widok sesji
 * w samym mapperze (przerwa > 5 min = nowa sesja).
 *
 * Logika jest czysta (bez Androida), więc testowana zwykłym JUnit.
 */
object MapperHistory {

    /** Jeden repeater, który usłyszał ping (odpowiedź w punkcie trasy). */
    data class Hit(val rptShortId: String, val snr: Double?, val rssi: Double?)

    /** Punkt trasy: pozycja companiona + repeatery, które go usłyszały (może być zero). */
    data class TrackPoint(val tsMs: Long, val lat: Double, val lon: Double, val hits: List<Hit>)

    /** Sesja mapowania jednego użytkownika: punkty połączone przerwami ≤ 5 min. */
    data class Session(
        val userId: Long,
        val nick: String?,
        val startMs: Long,
        val endMs: Long,
        val points: List<TrackPoint>
    )

    /** Repeater z bazy mapera (do wyświetlenia na mapie MAP). */
    data class Repeater(val shortId: String, val name: String?, val lat: Double?, val lon: Double?)

    data class History(val sessions: List<Session>)

    /** Maksymalna liczba stron (20 tys. punktów każda) pobieranych przy pełnym wczytaniu. */
    const val MAX_PAGES = 30

    /**
     * Pobiera całą bazę punktów mapera (od początku) i buduje sesje per użytkownik.
     * null = serwer nie odpowiada. [maxPages] ogranicza rozmiar pobieranej historii.
     */
    fun fetch(timeoutMs: Int, maxPages: Int = MAX_PAGES): History? {
        val raw = fetchPoints(timeoutMs, maxPages) ?: return null
        return History(buildSessions(raw.points, raw.users))
    }

    /** Surowe punkty bazy mapera + nicki (pod statystyki repeaterów). */
    data class Raw(val points: List<MapperPing>, val users: Map<Long, String>, val lastId: Long) {
        /** Doklejenie nowszych punktów (dociągnięcie od [lastId]); powtórzone ID pomijane. */
        operator fun plus(newer: Raw): Raw {
            val ids = HashSet<Long>(points.size * 2)
            points.forEach { ids.add(it.id) }
            return Raw(points + newer.points.filter { ids.add(it.id) }, users + newer.users, maxOf(lastId, newer.lastId))
        }
    }

    /**
     * Baza punktów stronami od [sinceId] (0 = od początku); null = serwer nie odpowiada.
     * Dociągnięcie tylko nowych punktów = [sinceId] z poprzedniego [Raw.lastId].
     */
    fun fetchPoints(timeoutMs: Int, maxPages: Int = MAX_PAGES, sinceId: Long = 0L): Raw? {
        var since = sinceId
        val points = mutableListOf<MapperPing>()
        val users = mutableMapOf<Long, String>()
        var pages = 0
        while (pages < maxPages) {
            pages++
            val result = MapperApi.poll(sinceId = since, timeoutMs = timeoutMs) ?: return null
            users.putAll(result.users)
            points.addAll(result.points)
            since = result.lastId
            if (!result.more) break
        }
        return Raw(points, users, since)
    }

    /** Klucz grupowania: ten sam mapujący, czas i pozycja = jedno miejsce trasy z kilkoma hitami. */
    private data class PosKey(val userId: Long, val tsMs: Long, val lat: Double, val lon: Double)

    /**
     * Czysta logika: punkty z bazy (jeden wiersz = jedna para companion↔repeater) grupujemy
     * w miejsca trasy (te same współrzędne i czas = jedno miejsce z listą repeaterów),
     * a potem dzielimy na sesje per użytkownik: przerwa > [MapperApi.SESSION_GAP_MS] = nowa sesja.
     * Punkty bez pozycji GPS ani bez repeatera (ping bez odpowiedzi z GPS-em) trafiają do trasy
     * jako „brak odpowiedzi”. Sortowanie wg czasu, najstarsze najpierw.
     */
    fun buildSessions(points: List<MapperPing>, users: Map<Long, String>): List<Session> {
        val grouped = LinkedHashMap<PosKey, MutableList<Hit>>()
        for (p in points) {
            val lat = p.lat ?: continue
            val lon = p.lon ?: continue
            val key = PosKey(p.userId, p.tsMs, lat, lon)
            val hits = grouped.getOrPut(key) { mutableListOf() }
            val rpt = p.rptId ?: continue          // ping bez odpowiedzi: miejsce bez hitów
            if (hits.none { it.rptShortId == rpt }) hits.add(Hit(rpt, p.snrDb, p.rssiDbm))
        }
        // Pozycje trasy per użytkownik, chronologicznie
        val byUser = HashMap<Long, MutableList<TrackPoint>>()
        for ((key, hits) in grouped) {
            byUser.getOrPut(key.userId) { mutableListOf() }.add(TrackPoint(key.tsMs, key.lat, key.lon, hits.toList()))
        }
        // Podział na sesje: przerwa > SESSION_GAP_MS
        val sessions = mutableListOf<Session>()
        for ((userId, pts) in byUser) {
            val sorted = pts.sortedBy { it.tsMs }
            var current = mutableListOf<TrackPoint>()
            for (tp in sorted) {
                if (current.isNotEmpty() && tp.tsMs - current.last().tsMs > MapperApi.SESSION_GAP_MS) {
                    sessions.add(sessionOf(userId, users, current))
                    current = mutableListOf()
                }
                current.add(tp)
            }
            if (current.isNotEmpty()) sessions.add(sessionOf(userId, users, current))
        }
        return sessions.sortedByDescending { it.endMs }
    }

    private fun sessionOf(userId: Long, users: Map<Long, String>, pts: List<TrackPoint>): Session = Session(
        userId = userId,
        nick = users[userId],
        startMs = pts.first().tsMs,
        endMs = pts.last().tsMs,
        points = pts
    )

    /** Pozycje i nazwy wszystkich repeaterów z bazy mapera (klucz = krótkie ID 16 hex). */
    fun repeaters(timeoutMs: Int): Map<String, Repeater> {
        val body = MapperApi.repeatersBody(timeoutMs) ?: return emptyMap()
        return parseRepeaters(body)
    }

    /** Parsowanie action=repeaters (czyste, testowalne). Uszkodzone rekordy pomijamy. */
    fun parseRepeaters(body: String): Map<String, Repeater> {
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return emptyMap()
        }
        if (!root.optBoolean("ok", false)) return emptyMap()
        val arr = root.optJSONArray("repeaters") ?: return emptyMap()
        val out = LinkedHashMap<String, Repeater>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val id = r.optString("id").trim().lowercase()
            if (id.length < 16) continue
            val name = r.optString("name", "").trim().takeIf { it.isNotEmpty() && it != "null" }
            val lat = r.optDouble("lat", Double.NaN).takeIf { !it.isNaN() }
            val lon = r.optDouble("lon", Double.NaN).takeIf { !it.isNaN() }
            out[id.take(16)] = Repeater(id.take(16), name, lat, lon)
        }
        return out
    }
}
