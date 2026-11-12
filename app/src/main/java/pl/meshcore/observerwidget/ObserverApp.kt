package pl.meshcore.observerwidget

import android.app.Application

/** Start procesu (aplikacja, usługa, widget, alarmy): wczytanie języka przed pierwszym tekstem. */
class ObserverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        L10n.init(this)
    }
}
