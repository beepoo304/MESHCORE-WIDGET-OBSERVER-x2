package pl.meshcore.observerwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class ObserverWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Szybki, lekki stan początkowy, potem dane z API
        ObserverRefresh.refreshAll(context, goAsync())
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ObserverRefresh.ACTION_REFRESH) {
            ObserverRefresh.refreshAll(context, goAsync())
        } else {
            super.onReceive(context, intent)
        }
    }

    override fun onEnabled(context: Context) {
        RefreshWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        RefreshWorker.sync(context)
    }
}
