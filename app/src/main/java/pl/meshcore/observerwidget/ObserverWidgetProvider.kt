package pl.meshcore.observerwidget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

class ObserverWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Natychmiastowy widok (ostatnie dane albo stan ładowania), potem dane z API.
        ObserverRefresh.renderNow(context, ids)
        ObserverRefresh.refreshAll(context, goAsync())
    }

    /** Launcher zmienił rozmiar widgetu: przerysowujemy go od razu, w odpowiednim wariancie layoutu. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        val hadData = ObserverRefresh.renderNow(context, intArrayOf(id))
        if (!hadData) ObserverRefresh.refreshAll(context, goAsync())
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
