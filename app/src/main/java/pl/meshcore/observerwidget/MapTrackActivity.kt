package pl.meshcore.observerwidget

import android.app.Activity
import android.os.Bundle
import androidx.core.view.WindowCompat

/**
 * Samodzielny ekran mapy (ten sam [MapScreen], co karta „Mapa” w dolnym pasku aplikacji);
 * zostaje dla zgodności z dotychczasowymi wejściami.
 */
class MapTrackActivity : Activity() {
    private lateinit var screen: MapScreen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = MapScreen(this, embedded = false) { finish() }
        setContentView(screen.view)
        WindowCompat.getInsetsController(window, screen.view).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    override fun onDestroy() {
        screen.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen.canGoBack()) screen.goBack() else super.onBackPressed()
    }
}
