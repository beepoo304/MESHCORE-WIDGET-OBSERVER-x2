package pl.meshcore.observerwidget

import android.content.Context
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Praca na historii mapera poza cyklem widgetu: synchronizacja co [SYNC_EVERY_MS], klucze mapujących,
 * push „mapował obok – nie słyszałeś” i dzienny raport o 8:00. Wszystko w osobnym wątku, żeby
 * pobieranie całej bazy nie blokowało odświeżania widgetu.
 */
object HistoryJobs {
    private const val TAG = "HistoryJobs"
    private const val SYNC_EVERY_MS = 5 * 60_000L
    private const val PREFS = "history_jobs"
    private const val REPORT_HOUR = 8
    private const val DAY_MS = 24 * 60 * 60_000L

    private val running = AtomicBoolean(false)

    /** Nasłuch zakładek aplikacji: wywoływany po każdej synchronizacji (w wątku tła). */
    @Volatile var listener: (() -> Unit)? = null

    val isRunning: Boolean get() = running.get()

    /** Asynchronicznie (z cyklu widgetu albo z aplikacji); [force] = synchronizuj od razu. */
    fun kick(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        if (!force && !due(app)) return
        if (!running.compareAndSet(false, true)) return
        thread(name = "mapper-history") {
            try {
                work(app)
            } finally {
                running.set(false)
                listener?.invoke()
            }
        }
    }

    /** Synchronicznie (z WorkManagera, który i tak działa w tle). */
    fun runNow(ctx: Context) {
        val app = ctx.applicationContext
        if (!due(app)) return
        if (!running.compareAndSet(false, true)) return
        try {
            work(app)
        } finally {
            running.set(false)
            listener?.invoke()
        }
    }

    private fun due(app: Context): Boolean {
        val last = MapperHistory.lastSyncMs(app)
        val now = System.currentTimeMillis()
        return now - last >= SYNC_EVERY_MS || last > now || needsReport(app, now)
    }

    private fun work(app: Context) {
        try {
            val ok = MapperHistory.sync(app)
            CompanionKeys.refreshIfStale(app)
            if (ok) {
                checkDaily(app)
            }
        } catch (e: Exception) {
            Log.w(TAG, "History job failed", e)
        }
    }

    /** Węzły z pozycjami: Observer + RPT1–RPT5 (kolejność jak w aplikacji). */
    fun nodes(cfg: Config, snap: MapperHistory.Snapshot): List<MapperStats.Node> {
        val out = mutableListOf<MapperStats.Node>()
        val obs = MapperApi.shortId(cfg.observerId)
        if (obs.length == 16) {
            val r = snap.repeaters[obs]
            out.add(MapperStats.Node(obs, "OBSERVER", r?.name, r?.lat, r?.lon))
        }
        for ((slot, id) in cfg.rptIds.toSortedMap()) {
            val s = MapperApi.shortId(id)
            if (s.length != 16 || out.any { it.shortId == s }) continue
            val r = snap.repeaters[s]
            out.add(MapperStats.Node(s, "RPT$slot", r?.name, r?.lat, r?.lon))
        }
        return out
    }

    /**
     * Push o sesjach w okolicy bez odbioru u mnie – raz na sesję, po jej zakończeniu.
     * Pierwsze uruchomienie tylko zapamiętuje istniejące sesje (bez zalewu powiadomień z historii).
     */
    private fun checkNearby(app: Context) {
        val cfg = Config(app)
        if (!cfg.monitoringEnabled) return
        val snap = MapperHistory.snapshot(app) ?: return
        val mine = MapperHistory.myShorts(cfg)
        val center = MapperHistory.centerOf(mine, snap.repeaters) ?: return
        val now = System.currentTimeMillis()
        val sessions = MapperStats.nearbyUnheard(
            snap.points, center.first, center.second, cfg.nearbyRadiusKm, mine.toSet(),
            sinceMs = now - DAY_MS, finishedBeforeMs = now - MapperApi.SESSION_GAP_MS
        )
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getString("nearby_seen", "").orEmpty().split(',').filter { it.isNotBlank() }.toMutableSet()
        val first = !prefs.getBoolean("nearby_init", false)
        val fresh = sessions.filter { it.key !in seen }
        if (!first && cfg.alertNearby) {
            for (s in fresh.sortedBy { it.startMs }) {
                val nick = snap.nick(s.userId)
                val heard = s.heardBy.map { id -> snap.repeaters[id]?.name?.let { "„$it”" } ?: id.take(4).uppercase() }
                val content = buildString {
                    append("$nick mapował ${GeoMath.km(s.minKm)} ${GeoMath.compassLabel(s.bearingDeg)} od Observera ")
                    append("${hhmm(s.startMs)}–${hhmm(s.endMs)}: ${MapperStats.pingsLabel(s.pings)}, 0 usłyszanych.")
                    if (heard.isNotEmpty()) append(" Słyszał go: ${heard.take(3).joinToString(", ")}.")
                }
                Alerts.notifyHistory(app, Alerts.ID_NEARBY, Alerts.CHANNEL_NEARBY, "Mapował obok – nie słyszałeś", content, MainActivity.TAB_PEOPLE)
            }
        }
        // Pamiętamy tylko klucze z ostatniej doby (+ zapas), żeby lista nie rosła bez końca.
        val keep = sessions.map { it.key }.toSet()
        val next = (seen.filter { k -> k.substringAfter(':').toLongOrNull()?.let { now - it < 2 * DAY_MS } == true } + keep).toSet()
        prefs.edit().putString("nearby_seen", next.joinToString(",")).putBoolean("nearby_init", true).apply()
    }

    private fun needsReport(app: Context, now: Long): Boolean {
        val cfg = Config(app)
        if (!cfg.alertDaily || !cfg.monitoringEnabled) return false
        val z = ZonedDateTime.now(ZoneId.systemDefault())
        if (z.hour < REPORT_HOUR) return false
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("daily_done", null) != z.toLocalDate().toString()
    }

    /** Raport dzienny: raz dziennie po 8:00, za cały poprzedni dzień kalendarzowy. */
    private fun checkDaily(app: Context) {
        val now = System.currentTimeMillis()
        if (!needsReport(app, now)) return
        val cfg = Config(app)
        val snap = MapperHistory.snapshot(app) ?: return
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val from = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val d = report(cfg, snap, from, to)
        val title = "Raport ${today.minusDays(1).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}"
        if (Alerts.notifyHistory(app, Alerts.ID_DAILY, Alerts.CHANNEL_DAILY, title, MapperStats.dailyText(d), MainActivity.TAB_PEOPLE)) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("daily_done", today.toString()).apply()
        }
    }

    fun report(cfg: Config, snap: MapperHistory.Snapshot, from: Long, to: Long): MapperStats.Daily {
        val nodes = nodes(cfg, snap)
        val center = MapperHistory.centerOf(nodes.map { it.shortId }, snap.repeaters)
        return MapperStats.daily(snap.points, nodes, center, cfg.nearbyRadiusKm, from, to)
    }

    private fun hhmm(ms: Long): String =
        DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
}
