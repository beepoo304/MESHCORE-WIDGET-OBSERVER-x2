package pl.meshcore.observerwidget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Local website watchdog, driven only by the existing MonitorService loop. */
object WdgMonitor {
    data class Site(val id: String, val url: String, val intervalMin: Int)
    data class Outage(val siteId: String, val startMs: Long, val endMs: Long?) {
        val durationMs: Long get() = ((endMs ?: System.currentTimeMillis()) - startMs).coerceAtLeast(0L)
    }
    data class WidgetItem(val id: String, val host: String, val uptime: String, val status: WdgEngine.Status)

    private const val PREFS = "wdg_monitor"
    private const val KEY_SITES = "sites"
    private const val CHANNEL = "wdg_alerts"
    private const val TAG = "WDG"
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun sites(ctx: Context): List<Site> {
        val p = prefs(ctx)
        if (!p.contains(KEY_SITES)) return listOf(Site("site1", "https://sn9pj.pzk.pl", 10))
        return try {
            val a = JSONArray(p.getString(KEY_SITES, "[]"))
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                Site(o.optString("id"), o.optString("url"), o.optInt("intervalMin", 10))
            }.filter { it.id.isNotBlank() && validUrl(it.url) && it.intervalMin in INTERVALS }.take(3)
        } catch (_: Exception) { emptyList() }
    }

    fun saveSites(ctx: Context, sites: List<Site>) {
        require(sites.size <= 3)
        require(sites.map { it.id }.distinct().size == sites.size)
        require(sites.all { it.id.isNotBlank() && validUrl(it.url) && it.intervalMin in INTERVALS })
        val a = JSONArray()
        sites.forEach { s -> a.put(JSONObject().put("id", s.id).put("url", s.url.trim()).put("intervalMin", s.intervalMin)) }
        prefs(ctx).edit().putString(KEY_SITES, a.toString()).apply()
        sites.forEach { setNextCheck(ctx, it.id, 0L) }
        ObserverRefresh.renderAll(ctx)
    }

    fun validUrl(value: String): Boolean = try {
        val u = Uri.parse(value.trim())
        (u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrBlank() && u.userInfo == null
    } catch (_: Exception) { false }

    private fun state(ctx: Context, id: String): WdgEngine.State {
        val o = try { JSONObject(prefs(ctx).getString("state_$id", "{}")) } catch (_: Exception) { JSONObject() }
        return WdgEngine.State(
            status = runCatching { WdgEngine.Status.valueOf(o.optString("status", "UNKNOWN")) }.getOrDefault(WdgEngine.Status.UNKNOWN),
            pending = runCatching { WdgEngine.Pending.valueOf(o.optString("pending", "NONE")) }.getOrDefault(WdgEngine.Pending.NONE),
            nextCheckAtMs = o.optLong("next", 0L),
            onlineSinceMs = o.optLong("onlineSince", 0L),
            outageStartedAtMs = o.optLong("outageSince", 0L)
        )
    }

    private fun saveState(ctx: Context, id: String, s: WdgEngine.State) {
        val o = JSONObject().put("status", s.status.name).put("pending", s.pending.name)
            .put("next", s.nextCheckAtMs).put("onlineSince", s.onlineSinceMs).put("outageSince", s.outageStartedAtMs)
        prefs(ctx).edit().putString("state_$id", o.toString()).apply()
    }

    private fun setNextCheck(ctx: Context, id: String, at: Long) {
        val s = state(ctx, id)
        saveState(ctx, id, s.copy(nextCheckAtMs = at))
    }

    /** Called once per existing service-loop tick. No timer, service, or WakeLock is created here. */
    fun tick(ctx: Context, scope: CoroutineScope) {
        val app = ctx.applicationContext
        if (!Config(app).monitoringEnabled) return
        val now = System.currentTimeMillis()
        for (site in sites(app)) {
            if (now < state(app, site.id).nextCheckAtMs || !inFlight.add(site.id)) continue
            scope.launch(Dispatchers.IO) {
                try {
                    if (!hasValidatedInternet(app)) {
                        setNextCheck(app, site.id, System.currentTimeMillis() + WdgEngine.CONFIRM_MS)
                        return@launch
                    }
                    val success = probe(site.url)
                    val current = state(app, site.id)
                    val result = WdgEngine.onCheck(current, success, System.currentTimeMillis(), site.intervalMin * 60_000L)
                    saveState(app, site.id, result.state)
                    when (result.action) {
                        WdgEngine.Action.ERROR -> {
                            openOutage(app, site.id, result.state.outageStartedAtMs)
                            notify(app, site, false)
                        }
                        WdgEngine.Action.RESTORE -> {
                            closeOutage(app, site.id, System.currentTimeMillis())
                            notify(app, site, true)
                        }
                        WdgEngine.Action.NONE -> Unit
                    }
                    ObserverRefresh.renderAll(app)
                } catch (e: Exception) {
                    Log.w(TAG, "WDG check failed for ${site.url}", e)
                    setNextCheck(app, site.id, System.currentTimeMillis() + WdgEngine.CONFIRM_MS)
                } finally {
                    inFlight.remove(site.id)
                }
            }
        }
    }

    fun onMonitoringStarted(ctx: Context) {
        sites(ctx).forEach { setNextCheck(ctx, it.id, 0L) }
    }

    private fun hasValidatedInternet(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) { false }
    }

    private fun probe(address: String): Boolean {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(address).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4_000
                readTimeout = 4_000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", "MeshCore-Observer-WDG")
            }
            val code = c.responseCode
            if (code != 200) false else c.inputStream.use { it.read() >= 0 }
        } catch (_: Exception) { false } finally { c?.disconnect() }
    }

    private fun history(ctx: Context, id: String): JSONArray =
        try { JSONArray(prefs(ctx).getString("history_$id", "[]")) } catch (_: Exception) { JSONArray() }

    private fun writeHistory(ctx: Context, id: String, a: JSONArray) {
        prefs(ctx).edit().putString("history_$id", a.toString()).apply()
    }

    private fun openOutage(ctx: Context, id: String, start: Long) {
        val a = history(ctx, id)
        a.put(JSONObject().put("start", start).put("end", JSONObject.NULL))
        writeHistory(ctx, id, a)
    }

    private fun closeOutage(ctx: Context, id: String, end: Long) {
        val a = history(ctx, id)
        for (i in a.length() - 1 downTo 0) {
            val o = a.optJSONObject(i) ?: continue
            if (o.isNull("end")) {
                o.put("end", end)
                break
            }
        }
        writeHistory(ctx, id, a)
    }

    fun outages(ctx: Context, id: String): List<Outage> {
        val a = history(ctx, id)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Outage(id, o.optLong("start"), if (o.isNull("end")) null else o.optLong("end"))
        }
    }

    /** Clearing history preserves live state; an active outage starts a fresh record at clear time. */
    fun clearHistory(ctx: Context, id: String) {
        val active = state(ctx, id).status == WdgEngine.Status.ERROR
        val now = System.currentTimeMillis()
        val a = JSONArray()
        if (active) a.put(JSONObject().put("start", now).put("end", JSONObject.NULL))
        writeHistory(ctx, id, a)
    }

    fun clearAllHistory(ctx: Context) = sites(ctx).forEach { clearHistory(ctx, it.id) }

    fun widgetItems(ctx: Context, nowMs: Long = System.currentTimeMillis()): List<WidgetItem> =
        if (!Config(ctx).monitoringEnabled) emptyList() else sites(ctx).map { site ->
            val s = state(ctx, site.id)
            val host = Uri.parse(site.url).host ?: site.url
            val since = if (s.status == WdgEngine.Status.ERROR) s.outageStartedAtMs else s.onlineSinceMs
            WidgetItem(site.id, host, if (since > 0L) formatUptime((nowMs - since).coerceAtLeast(0L)) else "—", s.status)
        }

    private fun formatUptime(ms: Long): String {
        val total = ms / 60_000L
        val days = total / 1_440L
        val hours = (total % 1_440L) / 60L
        return if (days > 0) "${days}d ${hours}h" else "${hours}h ${total % 60L}m"
    }

    private fun notify(ctx: Context, site: Site, restore: Boolean) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "WDG", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val host = Uri.parse(site.url).host ?: site.url
            val label = if (host.equals("sn9pj.pzk.pl", true)) "M2_WEB" else host
            val title = if (restore) "🟢 $label — RESTORE" else "🔴 $label — ERROR"
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_tower)
                .setContentTitle(title)
                .setContentText(site.url)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(40_000 + (site.id.hashCode() and 0x0FFF), n)
        } catch (e: Exception) { Log.w(TAG, "Cannot post WDG notification", e) }
    }

    val INTERVALS = listOf(5, 10, 15, 30, 60)
}
