package pl.meshcore.observerwidget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Alerts {
    private enum class Kind(val key: String, val channel: String, val title: String, val id: Int) {
        OBSERVER("obs", "alerts_observer", "Observer nie odbiera pakietów", 1001),
        MQTT1("m1", "alerts_mqtt1", "MQTT1 nie działa", 1002),
        MQTT2("m2", "alerts_mqtt2", "MQTT2 nie działa", 1003)
    }

    private val lock = Any()

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("alerts_observer", "Awaria Observera", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel("alerts_mqtt1", "Awaria MQTT1", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel("alerts_mqtt2", "Awaria MQTT2", NotificationManager.IMPORTANCE_HIGH))
    }

    fun evaluate(ctx: Context, data: WidgetData, nowMs: Long = System.currentTimeMillis()) {
        if (!data.configured) return
        val cfg = Config(ctx)
        val last1 = (data.broker1 as? FetchResult.Ok)?.snapshot?.lastPacketMs
        val last2 = (data.broker2 as? FetchResult.Ok)?.snapshot?.lastPacketMs
        val freshest = listOfNotNull(last1, last2, data.observer?.lastPacketMs).maxOrNull()
        val a = AlertEngine.assess(last1, last2, freshest, nowMs, cfg.redMin)
        val delayMs = cfg.alertDelayMin * 60_000L
        val cooldownMs = cfg.alertCooldownMin * 60_000L
        step(ctx, Kind.OBSERVER, a.observer, cfg.alertObserver, nowMs, delayMs, cooldownMs)
        step(ctx, Kind.MQTT1, a.mqtt1, cfg.alertMqtt1, nowMs, delayMs, cooldownMs)
        step(ctx, Kind.MQTT2, a.mqtt2, cfg.alertMqtt2, nowMs, delayMs, cooldownMs)
    }

    private fun step(ctx: Context, k: Kind, h: Health, enabled: Boolean, now: Long, delay: Long, cooldown: Long) {
        synchronized(lock) {
            val prefs = ctx.applicationContext.getSharedPreferences("alert_state", Context.MODE_PRIVATE)
            val prev = KeyState(
                bad = prefs.getBoolean("${k.key}_bad", false),
                badSinceMs = prefs.getLong("${k.key}_since", 0),
                notified = prefs.getBoolean("${k.key}_notified", false),
                lastNotifiedMs = prefs.getLong("${k.key}_last", 0)
            )
            val (next, action) = AlertEngine.step(prev, h, enabled, now, delay, cooldown)
            prefs.edit()
                .putBoolean("${k.key}_bad", next.bad)
                .putLong("${k.key}_since", next.badSinceMs)
                .putBoolean("${k.key}_notified", next.notified)
                .putLong("${k.key}_last", next.lastNotifiedMs)
                .apply()
            when (action) {
                AlertAction.NOTIFY -> notify(ctx, k, next.badSinceMs, now)
                AlertAction.CLEAR -> NotificationManagerCompat.from(ctx).cancel(k.id)
                AlertAction.NONE -> {}
            }
        }
    }

    private fun notify(ctx: Context, k: Kind, sinceMs: Long, nowMs: Long) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx, k.id, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mins = ((nowMs - sinceMs) / 60_000L).coerceAtLeast(0)
        val n = NotificationCompat.Builder(ctx, k.channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(k.title)
            .setContentText("Stan trwa od ok. $mins min. Dotknij, aby otworzyć.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(k.id, n)
        } catch (_: SecurityException) {
        }
    }
}
