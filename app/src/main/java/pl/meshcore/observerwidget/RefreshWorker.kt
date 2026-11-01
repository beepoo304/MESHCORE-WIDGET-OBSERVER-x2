package pl.meshcore.observerwidget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Odświeżanie w tle co 15 min (minimum Androida dla WorkManagera). */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        ObserverRefresh.refreshOnce(applicationContext)
        return Result.success()
    }

    companion object {
        private const val NAME = "observer_widget_refresh"
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
        fun cancel(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(NAME)

        /** Worker działa tylko wtedy, gdy jest po co: istnieje widget albo włączony jest jakiś alarm. */
        fun sync(ctx: Context) {
            val ids = android.appwidget.AppWidgetManager.getInstance(ctx)
                .getAppWidgetIds(android.content.ComponentName(ctx, ObserverWidgetProvider::class.java))
            if (ids.isNotEmpty() || Config(ctx).anyAlertEnabled) schedule(ctx) else cancel(ctx)
        }
    }
}
