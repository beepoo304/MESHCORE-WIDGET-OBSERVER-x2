package pl.meshcore.observerwidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Serce widgetu: tyka co sekundę na granicy pełnej sekundy zegara.
 *
 * Optymalizacja baterii:
 *  • Usunięto stały 24/7 wakelock – krótkotrwały wakelock pobierany jest tylko
 *    wewnątrz [ObserverRefresh.cycle] na czas transferu sieciowego (max 15 s).
 *  • Gdy ekran jest wyłączony, rysowanie [ObserverRefresh.renderAll] jest pomijane
 *    (oszczędność IPC, GPU i baterii), ale pobieranie danych działa normalnie.
 *  • Pętla tyka co 1 s, dzięki czemu po wybudzeniu ekranu widget natychmiast ożywa
 *    i nigdy nie wisi zamrożony.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            isRunning = false
            stopSelf()
            ObserverRefresh.scheduleTick(this)
            ObserverRefresh.scheduleNext(this)
            return START_NOT_STICKY
        }
        if (!Config(this).monitoringEnabled || !ObserverRefresh.hasWidgets(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        ObserverRefresh.cancelAlarm(this) // rezerwa z alarmów nie jest potrzebna
        if (loop?.isActive != true) loop = scope.launch { runLoop() }
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        true
    } catch (e: Exception) {
        Log.w(TAG, "startForeground failed", e)
        false
    }

    private suspend fun runLoop() {
        val power = getSystemService(PowerManager::class.java)
        while (scope.isActive) {
            val config = Config(this)
            if (!config.monitoringEnabled || !ObserverRefresh.hasWidgets(this)) {
                stopSelf()
                return
            }

            // Pobieranie danych biegnie zawsze (także przy wyłączonym ekranie).
            // Rysowanie co 1 s działa tylko przy włączonym ekranie (oszczędza IPC i GPU).
            if (ObserverRefresh.isRefreshDue()) {
                ObserverRefresh.refreshAll(this)
            } else if (power?.isInteractive != false) {
                ObserverRefresh.renderAll(this)
            }

            // Następne tyknięcie tuż po granicy pełnej sekundy: cyfry zmieniają się równo co 1 s.
            val now = System.currentTimeMillis()
            delay(1_000L - (now % 1_000L) + 15L)
        }
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification = buildNotification(this, ObserverRefresh.latest())

    companion object {
        private const val TAG = "ObserverWidget"
        private const val CHANNEL = "monitor_service_low"
        private const val NOTIFICATION_ID = 2001

        @Volatile
        var isRunning: Boolean = false
            private set

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.deleteNotificationChannel("monitor_service")
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, tr("Monitoring w tle", "Background monitoring"), NotificationManager.IMPORTANCE_LOW)
            )
        }

        fun buildNotification(ctx: Context, data: WidgetData?): Notification {
            ensureChannel(ctx)
            val open = PendingIntent.getActivity(
                ctx, 10, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val config = Config(ctx)
            val builder = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_tower)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(open)
            if (config.lockScreenStatus && data != null && data.configured) {
                val t = LockScreenText.build(data, System.currentTimeMillis()) { ping, i ->
                    MapperMonitor.nick(ping.userId) ?: data.mapping?.mapperNicks?.getOrNull(i)
                }
                builder.setContentTitle(t.title)
                    .setContentText(t.summary)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(t.expanded))
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            } else {
                builder.setContentTitle(tr("Monitoring Observera aktywny", "Observer monitoring active"))
                    .setContentText(tr("Widget odświeżany co ${config.intervalSec} s", "Widget refreshes every ${config.intervalSec} s"))
            }
            return builder.build()
        }

        fun updateNotification(ctx: Context, data: WidgetData?) {
            if (!isRunning) return
            try {
                val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
                nm.notify(NOTIFICATION_ID, buildNotification(ctx.applicationContext, data))
            } catch (e: Exception) {
                Log.w(TAG, "Cannot update monitor notification", e)
            }
        }

        fun start(ctx: Context): Boolean {
            if (isRunning) return true
            return try {
                ContextCompat.startForegroundService(ctx.applicationContext, Intent(ctx, MonitorService::class.java))
                true
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start MonitorService (background start restricted?)", e)
                false
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.applicationContext.stopService(Intent(ctx, MonitorService::class.java))
            } catch (_: Exception) {
            }
            isRunning = false
        }
    }
}
