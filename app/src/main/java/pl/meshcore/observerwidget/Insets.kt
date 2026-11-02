package pl.meshcore.observerwidget

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Przy targetSdk 35 aplikacja rysuje się pod paskami systemowymi (edge-to-edge). Odsuwamy zawartość
 * o paski stanu/nawigacji i wycięcie ekranu, zachowując dotychczasowy padding widoku.
 */
fun View.applySystemBarsPadding() {
    val l = paddingLeft
    val t = paddingTop
    val r = paddingRight
    val b = paddingBottom
    if (this is ViewGroup) clipToPadding = false
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
