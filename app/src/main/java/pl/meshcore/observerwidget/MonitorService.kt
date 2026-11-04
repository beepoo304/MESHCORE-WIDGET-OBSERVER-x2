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
 * Serce widgetu: tyka dokładnie co sekundę (na granicy pełnej sekundy zegara),
 * przerysowuje liczniki i uruchamia pobieranie, gdy licznik odświeżania dojdzie do 00s.
 *
 * Usługa pierwszoplanowa (specialUse) to jedyny sposób na niezawodną aktualizację co sekundę:
 * AlarmManager ma minimalny odstęp ~5 s, a WorkManager 15 min.
 * Pobieranie danych biegnie zawsze, gdy monitoring jest włączony – także przy wyłączonym ekranie
 * (częściowy wakelock chroni pętlę przed zamrożeniem przez Doze w nocy). Rysowanie co 1 s
 * działa tylko przy włączonym ekranie; po włączeniu ekranu widget dorysowuje się w ciągu sekundy.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

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
            renewWakeLock()
            // Pobieranie danych biegnie cały czas (także przy wyłączonym ekranie);
            // rysowanie co 1 s ma sens tylko przy włączonym ekranie.
            if (ObserverRefresh.isRefreshDue()) ObserverRefresh.refreshAll(this)
            else if (power?.isInteractive != false) ObserverRefresh.renderAll(this)
            // Następne tyknięcie tuż po granicy pełnej sekundy: cyfry zmieniają się równo co 1 s.
            val now = System.currentTimeMillis()
            delay(1_000L - (now % 1_000L) + 15L)
        }
    }

    /**
     * Częściowy wakelock, żeby Doze nie zamrażał pętli w nocy. Odnawiany z limitem czasu
     * (zamiast trzymania w nieskończoność): pętla podtrzymuje go przy każdym obrocie,
     * a [onDestroy] zawsze zwalnia.
     */
    private fun renewWakeLock() {
        val lock = wakeLock
        if (lock != null && lock.isHeld) return
        val power = getSystemService(PowerManager::class.java) ?: return
        try {
            if (lock == null) {
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ObserverWidget:monitor").also {
                    it.setReferenceCounted(false)
                    it.acquire(WAKELOCK_RENEW_MS)
                }
            } else {
                lock.acquire(WAKELOCK_RENEW_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot acquire wakelock", e)
        }
    }

    override fun onDestroy() {
        isRunning = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tower)
            .setContentTitle("Monitoring Observera aktywny")
            .setContentText("Widget odświeżany co ${Config(this).intervalSec} s")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
    }

    companion object {
        private const val TAG = "ObserverWidget"
        private const val CHANNEL = "monitor_service"
        private const val NOTIFICATION_ID = 2001
        private const val WAKELOCK_RENEW_MS = 10 * 60_000L

        @Volatile
        var isRunning: Boolean = false
            private set

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Monitoring w tle", NotificationManager.IMPORTANCE_MIN)
            )
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
