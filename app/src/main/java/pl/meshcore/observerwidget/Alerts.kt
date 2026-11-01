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
        val assessment = AlertEngine.assess(last1, last2, freshest, nowMs, cfg.redMin)
        val delayMs = cfg.alertDelayMin * 60_000L
        val cooldownMs = cfg.alertCooldownMin * 60_000L
        step(ctx, Kind.OBSERVER, assessment.observer, cfg.alertObserver, nowMs, delayMs, cooldownMs)
        step(ctx, Kind.MQTT1, assessment.mqtt1, cfg.alertMqtt1, nowMs, delayMs, cooldownMs)
        step(ctx, Kind.MQTT2, assessment.mqtt2, cfg.alertMqtt2, nowMs, delayMs, cooldownMs)
    }

    private fun step(ctx: Context, kind: Kind, health: Health, enabled: Boolean, nowMs: Long, delayMs: Long, cooldownMs: Long) {
        synchronized(lock) {
            val prefs = ctx.applicationContext.getSharedPreferences("alert_state", Context.MODE_PRIVATE)
            val key = kind.key
            val previous = KeyState(
                bad = prefs.getBoolean("${key}_bad", false),
                badSinceMs = prefs.getLong("${key}_since", 0),
                notified = prefs.getBoolean("${key}_notified", false),
                lastNotifiedMs = prefs.getLong("${key}_last", 0)
            )
            val (next, action) = AlertEngine.step(previous, health, enabled, nowMs, delayMs, cooldownMs)

            var stateToPersist = next
            when (action) {
                AlertAction.NOTIFY -> {
                    if (!notify(ctx, kind, next.badSinceMs, nowMs)) {
                        // Keep the alert pending; a later refresh can retry after permission is granted.
                        stateToPersist = next.copy(notified = false, lastNotifiedMs = previous.lastNotifiedMs)
                    }
                }
                AlertAction.CLEAR -> NotificationManagerCompat.from(ctx).cancel(kind.id)
                AlertAction.NONE -> Unit
            }

            prefs.edit()
                .putBoolean("${key}_bad", stateToPersist.bad)
                .putLong("${key}_since", stateToPersist.badSinceMs)
                .putBoolean("${key}_notified", stateToPersist.notified)
                .putLong("${key}_last", stateToPersist.lastNotifiedMs)
                .apply()
        }
    }

    private fun notify(ctx: Context, kind: Kind, sinceMs: Long, nowMs: Long): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        ensureChannels(ctx)
        val openIntent = PendingIntent.getActivity(
            ctx, kind.id, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val elapsedMinutes = ((nowMs - sinceMs) / 60_000L).coerceAtLeast(0)
        val notification = NotificationCompat.Builder(ctx, kind.channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(kind.title)
            .setContentText("Stan trwa od ok. $elapsedMinutes min. Dotknij, aby otworzyć.")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        return try {
            NotificationManagerCompat.from(ctx).notify(kind.id, notification)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }
}
