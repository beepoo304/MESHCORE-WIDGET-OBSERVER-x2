package pl.meshcore.observerwidget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews

object WidgetRenderer {
    const val GREEN = 0xFF6EF015.toInt()
    const val ORANGE = 0xFFFF9500.toInt()
    const val RED = 0xFFFF3B30.toInt()
    const val GRAY = 0xFF6B6B6B.toInt()

    /** Napis i kolor statusu Observera. Wspólne dla widgetu i Dashboardu. */
    fun liveState(d: WidgetData, nowMs: Long): Pair<String, Int> {
        val obs = d.observer
        return when {
            !d.configured -> "SETUP" to ORANGE
            obs == null -> "API ✕" to RED
            obs.lastPacketMs == null -> "NO DATA" to GRAY
            else -> when (Status.level(nowMs - obs.lastPacketMs, d.orangeMin, d.redMin)) {
                Level.GREEN -> "● LIVE" to GREEN
                Level.ORANGE -> "● IDLE" to ORANGE
                else -> "● NO PKT" to RED
            }
        }
    }

    private fun settingsIntent(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 1, Intent(ctx, SettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun render(ctx: Context, d: WidgetData, nowMs: Long = System.currentTimeMillis()): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_observer)
        v.setOnClickPendingIntent(R.id.widget_root, ObserverRefresh.pendingIntent(ctx))
        v.setOnClickPendingIntent(R.id.obs_name, settingsIntent(ctx))

        v.setTextViewText(R.id.interval_text, "${d.intervalSec} s")
        v.setImageViewResource(R.id.refresh_icon, if (d.busy) R.drawable.ic_refresh_busy else R.drawable.ic_refresh)

        v.setTextViewText(R.id.m1_host, Config.displayHost(d.mqtt1Host))
        v.setTextViewText(R.id.m2_host, Config.displayHost(d.mqtt2Host))

        // Wiersz Observera
        val obs = d.observer
        v.setTextViewText(R.id.obs_hash, if (d.observerId.length >= 4) d.observerId.take(4).uppercase() else "----")
        v.setTextViewText(R.id.obs_name, when {
            !d.configured -> "Ustaw hash w aplikacji"
            obs?.name != null -> obs.name
            else -> "—"
        })
        val (liveText, liveColor) = liveState(d, nowMs)
        v.setTextViewText(R.id.obs_live, liveText)
        v.setTextColor(R.id.obs_live, liveColor)
        v.setInt(R.id.obs_live, "setBackgroundResource", liveBg(liveColor))

        broker(v, d, d.broker1, nowMs, R.id.m1_hour, R.id.m1_total, R.id.m1_last)
        broker(v, d, d.broker2, nowMs, R.id.m2_hour, R.id.m2_total, R.id.m2_last)
        return v
    }

    private fun broker(v: RemoteViews, d: WidgetData, r: FetchResult?, nowMs: Long, hourId: Int, totalId: Int, lastId: Int) {
        when (r) {
            is FetchResult.Ok -> {
                val s = r.snapshot
                v.setTextViewText(hourId, s.packetsPerHour.toString())
                v.setTextViewText(totalId, Status.formatCount(s.totalPackets))
                if (s.lastPacketMs == null) {
                    v.setTextViewText(lastId, "brak")
                    v.setInt(lastId, "setBackgroundResource", R.drawable.pill_gray)
                } else {
                    val age = nowMs - s.lastPacketMs
                    v.setChronometer(lastId, SystemClock.elapsedRealtime() - age.coerceAtLeast(0), "%s", true)
                    v.setInt(lastId, "setBackgroundResource", when (Status.level(age, d.orangeMin, d.redMin)) {
                        Level.GREEN -> R.drawable.pill_green
                        Level.ORANGE -> R.drawable.pill_orange
                        else -> R.drawable.pill_red
                    })
                }
            }
            else -> {
                // API niedostępne: nigdy nie udajemy LIVE
                v.setTextViewText(hourId, "—")
                v.setTextViewText(totalId, "—")
                v.setTextViewText(lastId, if (r == null) "…" else "API ✕")
                v.setInt(lastId, "setBackgroundResource", if (r == null) R.drawable.pill_gray else R.drawable.pill_red)
            }
        }
    }

    private fun liveBg(color: Int) = when (color) {
        GREEN -> R.drawable.live_green
        ORANGE -> R.drawable.live_orange
        RED -> R.drawable.live_red
        else -> R.drawable.live_gray
    }

    /** Uproszczony layout, który realnie zastępuje główny, gdy jego zbudowanie się nie powiedzie. */
    fun safe(ctx: Context, message: String): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_observer_safe)
        v.setTextViewText(R.id.safe_text, message)
        v.setOnClickPendingIntent(R.id.safe_root, ObserverRefresh.pendingIntent(ctx))
        return v
    }
}
