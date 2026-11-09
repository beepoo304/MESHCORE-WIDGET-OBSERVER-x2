package pl.meshcore.observerwidget

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Ustawienia są teraz zakładką aplikacji; ten ekran (otwierany z widgetu) tylko na nią przekierowuje. */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
