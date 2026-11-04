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
            obs == null && d.busy -> "…" to GRAY
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

    fun render(
        ctx: Context,
        d: WidgetData,
        layout: WidgetLayout = WidgetLayout.DEFAULT,
        nowMs: Long = System.currentTimeMillis()
    ): RemoteViews {
        val v = RemoteViews(ctx.packageName, if (layout.compact) R.layout.widget_observer_compact else R.layout.widget_observer)
        val totalVisibility = if (layout.showTotal) android.view.View.VISIBLE else android.view.View.GONE
        v.setViewVisibility(R.id.m1_total_col, totalVisibility)
        v.setViewVisibility(R.id.m2_total_col, totalVisibility)
        v.setOnClickPendingIntent(R.id.widget_root, ObserverRefresh.pendingIntent(ctx))
        v.setOnClickPendingIntent(R.id.obs_name, settingsIntent(ctx))

        val monitoringEnabled = Config(ctx).monitoringEnabled
        val scheduledAt = ObserverRefresh.nextRefreshElapsedRealtime(d.intervalSec)
        val remainingSeconds = if (monitoringEnabled) {
            ((scheduledAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 999L) / 1_000L
        } else 0L
        v.setTextViewText(
            R.id.interval_text,
            if (!monitoringEnabled) "00s" else String.format("%02ds", remainingSeconds)
        )
        v.setViewVisibility(R.id.refresh_icon, android.view.View.VISIBLE)
        v.setImageViewResource(
            R.id.refresh_icon,
            if (d.busy) R.drawable.ic_refresh_animated else R.drawable.ic_refresh
        )
        v.setViewVisibility(R.id.refresh_spinner, android.view.View.GONE)

        // Wiersz Observera
        val obs = d.observer
        v.setTextViewText(R.id.obs_hash, d.observerId.ifBlank { "----" }.uppercase())
        v.setTextColor(R.id.obs_hash, GRAY)
        v.setTextViewText(R.id.obs_name, when {
            !d.configured -> "Ustaw hash w aplikacji"
            obs?.name != null -> obs.name
            else -> "—"
        })
        v.setTextColor(R.id.m1_title, brokerHealthColor(d.mqtt1Health, d.broker1))
        v.setTextColor(R.id.m2_title, brokerHealthColor(d.mqtt2Health, d.broker2))

        val effectiveNowMs = if (monitoringEnabled) nowMs
            else Config(ctx).monitoringStoppedAtMs.takeIf { it > 0L } ?: 0L
        broker(v, d, d.broker1, effectiveNowMs, monitoringEnabled, layout, R.id.m1_hour, R.id.m1_total, R.id.m1_last)
        broker(v, d, d.broker2, effectiveNowMs, monitoringEnabled, layout, R.id.m2_hour, R.id.m2_total, R.id.m2_last)
        return v
    }

    /** Teksty karty brokera. */
    data class BrokerTexts(val hour: String, val total: String, val last: String?)

    fun brokerTexts(r: FetchResult?, compact: Boolean): BrokerTexts = when (r) {
        is FetchResult.Ok -> {
            val s = r.snapshot
            BrokerTexts(
                hour = if (compact) "${s.packetsPerHour}/h" else s.packetsPerHour.toString(),
                total = Status.formatCount(s.totalPackets).let { if (compact) "$it pkt" else it },
                last = if (s.lastPacketMs == null) "brak" else null
            )
        }
        // API niedostępne: nigdy nie udajemy LIVE
        else -> BrokerTexts("—", "—", if (r == null) "…" else "API ✕")
    }

    private fun broker(
        v: RemoteViews, d: WidgetData, r: FetchResult?, nowMs: Long, monitoringEnabled: Boolean, layout: WidgetLayout,
        hourId: Int, totalId: Int, lastId: Int
    ) {
        val t = brokerTexts(r, layout.compact)
        v.setTextViewText(hourId, t.hour)
        v.setTextViewText(totalId, t.total)
        when {
            r is FetchResult.Ok && r.snapshot.lastPacketMs != null -> {
                val age = (nowMs - r.snapshot.lastPacketMs).coerceAtLeast(0L)
                val chronometerBase = SystemClock.elapsedRealtime() - age
                if (monitoringEnabled) {
                    v.setChronometer(lastId, chronometerBase, "%s", true)
                    v.setChronometerCountDown(lastId, false)
                } else {
                    v.setTextViewText(lastId, formatLastPacketAge(age))
                }
                v.setInt(lastId, "setBackgroundResource", when (Status.level(age, d.orangeMin, d.redMin)) {
                    Level.GREEN -> R.drawable.pill_green
                    Level.ORANGE -> R.drawable.pill_orange
                    else -> R.drawable.pill_red
                })
            }
            else -> {
                v.setTextViewText(lastId, t.last)
                v.setInt(lastId, "setBackgroundResource", when {
                    r is FetchResult.Ok -> R.drawable.pill_gray
                    r == null -> R.drawable.pill_gray
                    else -> R.drawable.pill_red
                })
            }
        }
    }

    private fun formatLastPacketAge(ageMs: Long): String {
        val totalSeconds = ageMs / 1_000L
        return if (totalSeconds < 60L) {
            String.format("%02d", totalSeconds)
        } else {
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun brokerHealthColor(health: Health, result: FetchResult?): Int = when {
        health == Health.BAD -> RED
        health == Health.GOOD || result is FetchResult.Ok -> GREEN
        else -> GRAY
    }

    private fun healthColor(health: Health): Int = when (health) {
        Health.GOOD -> GREEN
        Health.BAD -> RED
        Health.UNKNOWN -> GRAY
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
