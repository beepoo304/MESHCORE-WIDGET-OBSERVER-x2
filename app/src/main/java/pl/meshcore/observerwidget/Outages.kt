package pl.meshcore.observerwidget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Rodzaj przestoju: brokery MQTT i przerwa w wysyłaniu pakietów przez Observera. */
enum class OutageKind(val key: String, val label: String) {
    MQTT1("m1", "MQTT1"),
    MQTT2("m2", "MQTT2"),
    OBSERVER("obs", "Observer")
}

/** Jeden przestój; [endMs] == null = trwa. */
data class Outage(val kind: OutageKind, val startMs: Long, val endMs: Long? = null, val note: String? = null) {
    fun durationMs(nowMs: Long): Long = ((endMs ?: nowMs) - startMs).coerceAtLeast(0L)
}

/**
 * Czysta logika rejestru przestojów (bez Androida, testowalna): GOOD→BAD otwiera przestój,
 * BAD→GOOD go zamyka, UNKNOWN niczego nie zmienia. Przerwy krótsze niż [MIN_MS] są pomijane.
 */
object OutageEngine {
    const val MIN_MS = 60_000L
    const val KEEP_MS = 30L * 24 * 3600_000L
    const val MAX_ENTRIES = 300

    data class Step(val openStartMs: Long?, val closed: Outage?)

    /**
     * Jeden krok dla jednego rodzaju. [startHintMs] = początek przerwy, jeśli jest znany wcześniej
     * niż wykrycie (Observer: czas ostatniego pakietu).
     */
    fun step(kind: OutageKind, openStartMs: Long?, health: Health, nowMs: Long, startHintMs: Long? = null): Step = when (health) {
        Health.UNKNOWN -> Step(openStartMs, null)
        Health.BAD -> Step(openStartMs ?: (startHintMs?.takeIf { it in 1..nowMs } ?: nowMs), null)
        Health.GOOD -> if (openStartMs == null) Step(null, null)
            else Step(null, close(kind, openStartMs, nowMs, null))
    }

    /** Zamknięcie przestoju; null, gdy krótszy niż minimum. */
    fun close(kind: OutageKind, startMs: Long, endMs: Long, note: String?): Outage? =
        if (endMs - startMs < MIN_MS) null else Outage(kind, startMs, endMs, note)

    /** Retencja: najnowsze na początku, nie starsze niż [KEEP_MS], najwyżej [MAX_ENTRIES]. */
    fun prune(list: List<Outage>, nowMs: Long): List<Outage> =
        list.filter { nowMs - (it.endMs ?: nowMs) <= KEEP_MS }
            .sortedByDescending { it.startMs }
            .take(MAX_ENTRIES)

    /** Łączny czas przestojów rodzaju [kind] w oknie [windowMs] przed [nowMs] (trwające liczone do teraz). */
    fun totalInWindow(list: List<Outage>, kind: OutageKind, nowMs: Long, windowMs: Long): Long {
        val from = nowMs - windowMs
        return list.filter { it.kind == kind }.sumOf { o ->
            (minOf(o.endMs ?: nowMs, nowMs) - maxOf(o.startMs, from)).coerceAtLeast(0L)
        }
    }

    fun toJson(list: List<Outage>): String = JSONArray().apply {
        for (o in list) put(JSONObject().apply {
            put("k", o.kind.key)
            put("s", o.startMs)
            o.endMs?.let { put("e", it) }
            o.note?.let { put("n", it) }
        })
    }.toString()

    fun fromJson(s: String?): List<Outage> {
        if (s.isNullOrBlank()) return emptyList()
        val arr = try { JSONArray(s) } catch (_: Exception) { return emptyList() }
        val out = mutableListOf<Outage>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val kind = OutageKind.values().firstOrNull { it.key == o.optString("k") } ?: continue
            out.add(Outage(
                kind, o.optLong("s"),
                if (o.has("e")) o.optLong("e") else null,
                if (o.has("n")) o.optString("n") else null
            ))
        }
        return out
    }
}

/** Rejestr przestojów w SharedPreferences „outages” (zapis z [Alerts.evaluate], odczyt w karcie Statystyki → Przestoje). */
object Outages {
    private const val PREFS = "outages"
    private val lock = Any()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(ctx: Context, kind: OutageKind, health: Health, nowMs: Long, startHintMs: Long? = null) {
        synchronized(lock) {
            val p = prefs(ctx)
            val openKey = "open_${kind.key}"
            val open = p.getLong(openKey, 0L).takeIf { it > 0L }
            val r = OutageEngine.step(kind, open, health, nowMs, startHintMs)
            val e = p.edit()
            if (r.openStartMs != null) e.putLong(openKey, r.openStartMs) else e.remove(openKey)
            r.closed?.let { e.putString("list", OutageEngine.toJson(OutageEngine.prune(listOf(it) + closed(ctx), nowMs))) }
            e.apply()
        }
    }

    /** Zatrzymanie monitoringu: trwające przestoje kończą się teraz z adnotacją. */
    fun closeAll(ctx: Context, nowMs: Long, note: String) {
        synchronized(lock) {
            val p = prefs(ctx)
            val e = p.edit()
            var list = closed(ctx)
            for (kind in OutageKind.values()) {
                val start = p.getLong("open_${kind.key}", 0L).takeIf { it > 0L } ?: continue
                OutageEngine.close(kind, start, nowMs, note)?.let { list = listOf(it) + list }
                e.remove("open_${kind.key}")
            }
            e.putString("list", OutageEngine.toJson(OutageEngine.prune(list, nowMs)))
            e.apply()
        }
    }

    private fun closed(ctx: Context): List<Outage> = OutageEngine.fromJson(prefs(ctx).getString("list", null))

    /** Wszystkie przestoje (trwające dłużej niż minimum + zakończone), najnowsze na początku. */
    fun all(ctx: Context, nowMs: Long): List<Outage> = synchronized(lock) {
        val p = prefs(ctx)
        val open = OutageKind.values().mapNotNull { k ->
            p.getLong("open_${k.key}", 0L).takeIf { it > 0L && nowMs - it >= OutageEngine.MIN_MS }?.let { Outage(k, it) }
        }
        (open + closed(ctx)).sortedByDescending { it.startMs }
    }

    fun anyOngoing(ctx: Context, nowMs: Long): Boolean = all(ctx, nowMs).any { it.endMs == null }

    /** Reset: kasuje listę; trwający przestój zaczyna się od nowa od teraz. */
    fun clear(ctx: Context, nowMs: Long) {
        synchronized(lock) {
            val p = prefs(ctx)
            val e = p.edit().remove("list")
            for (k in OutageKind.values()) if (p.getLong("open_${k.key}", 0L) > 0L) e.putLong("open_${k.key}", nowMs)
            e.apply()
        }
    }
}
