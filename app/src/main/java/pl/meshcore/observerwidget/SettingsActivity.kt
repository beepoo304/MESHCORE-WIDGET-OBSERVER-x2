package pl.meshcore.observerwidget

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Wejście do ustawień z widgetu. Zamiast osobnego ekranu (z którego nie było powrotu) otwiera
 * główną aplikację na karcie „Ustawienia” – z dolnym paskiem i działającym „wstecz”.
 */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
        overridePendingTransition(0, 0)
    }
}
