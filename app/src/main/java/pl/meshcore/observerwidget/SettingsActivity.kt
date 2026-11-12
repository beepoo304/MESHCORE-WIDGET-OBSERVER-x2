package pl.meshcore.observerwidget

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView

/**
 * Samodzielny ekran ustawień (otwierany m.in. z widgetu). Zawartość to ten sam [SettingsScreen],
 * co karta „Ustawienia” w dolnym pasku aplikacji.
 */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(SettingsScreen(this@SettingsActivity).build())
            applySystemBarsPadding()
        })
    }
}
