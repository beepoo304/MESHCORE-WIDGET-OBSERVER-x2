package pl.meshcore.observerwidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * Serce widgetu: tyka co sekundę przy włączonym ekranie (odliczanie i puls MAPPER),
 * a przy wyłączonym ekranie usypia na pełny interwał odświeżania.
 *
 * v3 – optymalizacja baterii:
 *  • Wakelock pobierany TYLKO na czas pobierania danych (max 30 s), nie permanentny.
 *  • Przy wyłączonym ekranie pętla nie tyka co 1 s – usypia na cały interwał.
 *  • Ekran włączony/wyłączony wykrywany przez BroadcastReceiver.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Czy ekran jest włączony – aktualizowane przez receiver. */
    @Volatile
    private var screenOn = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    // Natychmiastowe przerysowanie widgetu po włączeniu ekranu.
                    ObserverRefresh.renderAll(context)
                }
                Intent.ACTION_SCREEN_OFF -> screenOn = false
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        screenOn = (getSystemService(PowerManager::class.java))?.isInteractive != false
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
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
        while (scope.isActive) {
            val config = Config(this)
            if (!config.monitoringEnabled || !ObserverRefresh.hasWidgets(this)) {
                stopSelf()
                return
            }

            if (ObserverRefresh.isRefreshDue()) {
                // Pobieranie danych: krótkotrwały wakelock tylko na czas operacji sieciowej.
                acquireRefreshWakeLock()
                try {
                    ObserverRefresh.refreshAll(this)
                } finally {
                    releaseWakeLock()
                }
            } else if (screenOn) {
                // Ekran włączony: przerysowanie widgetu (liczniki sekund, puls MAPPER).
                ObserverRefresh.renderAll(this)
            }
            // Następne tyknięcie:
            //  - ekran włączony: co 1 s (sekundnik, puls MAPPER)
            //  - ekran wyłączony: śpimy do następnego odświeżenia (oszczędzanie baterii)
            if (screenOn) {
                val now = System.currentTimeMillis()
                delay(1_000L - (now % 1_000L) + 15L)
            } else {
                val sleepMs = (ObserverRefresh.nextRefreshAtMs() - System.currentTimeMillis())
                    .coerceIn(1_000L, config.intervalSec * 1_000L)
                delay(sleepMs)
            }
        }
    }

    /**
     * Krótkotrwały wakelock na czas pobierania danych (max 30 s).
     * Tylko żeby sieć nie zamarzła w trakcie HTTP – po zakończeniu od razu zwalniany.
     */
    private fun acquireRefreshWakeLock() {
        try {
            val power = getSystemService(PowerManager::class.java) ?: return
            val lock = wakeLock ?: power.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "ObserverWidget:refresh"
            ).also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
            lock.acquire(REFRESH_WAKELOCK_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot acquire wakelock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        isRunning = false
        releaseWakeLock()
        wakeLock = null
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification = buildNotification(this, ObserverRefresh.latest())

    companion object {
        private const val TAG = "ObserverWidget"
        // Nowy ID kanału: Android nie pozwala podnieść ważności istniejącego kanału
        // (MIN = brak ikony w pasku statusu), więc kanał LOW tworzymy od nowa.
        private const val CHANNEL = "monitor_service_low"
        private const val NOTIFICATION_ID = 2001
        /** Max czas wakelockna na jedno pobranie danych (30 s). */
        private const val REFRESH_WAKELOCK_MS = 30_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            // Usuwamy stary kanał MIN, żeby nie zostawał w ustawieniach jako martwy.
            nm.deleteNotificationChannel("monitor_service")
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, tr("Monitoring w tle", "Background monitoring"), NotificationManager.IMPORTANCE_LOW)
            )
        }

        /**
         * Stałe powiadomienie usługi. Przy włączonej opcji [Config.lockScreenStatus] pokazuje dane
         * Observera (widoczne na ekranie blokady, także z PIN-em): zwinięte = tytuł + skrót brokerów,
         * rozwinięte = pełne linie MQTT1/MQTT2 + MAPPER. Bez danych / przy wyłączonej opcji – stary tekst.
         */
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

        /** Odświeża treść stałego powiadomienia po pobraniu danych (nie co sekundę – oszczędza baterię). */
        fun updateNotification(ctx: Context, data: WidgetData?) {
            if (!isRunning) return
            try {
                val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
                nm.notify(NOTIFICATION_ID, buildNotification(ctx.applicationContext, data))
            } catch (e: Exception) {
                Log.w(TAG, "Cannot update monitor notification", e)
            }
        }

        /** true = usługa działa lub została uruchomiona; false = Android odmówił (np. start z tła). */
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
