package pl.meshcore.observerwidget

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Wspólny styl aplikacji (paleta i drobne elementy jak w M2): ciemny grafit, zielony akcent,
 * nagłówki sekcji WIELKIMI LITERAMI, wiersze rozdzielone cienką linią. Tylko aplikacja –
 * widget ma własne kolory w [WidgetRenderer] i zostaje bez zmian.
 */
object Ui {
    const val BG = 0xFF111315.toInt()
    const val SURFACE = 0xFF171A1C.toInt()
    const val SURFACE_VARIANT = 0xFF23272A.toInt()
    const val PANEL = 0xFF1E2225.toInt()
    const val LINE = 0xFF2A3033.toInt()
    const val OUTLINE = 0xFF3B4140.toInt()
    const val PILL = 0xFF3A3F4A.toInt()
    const val TEXT = 0xFFE2E5E3.toInt()
    const val SUBTEXT = 0xFFD6D9D7.toInt()
    const val MUTED = 0xFF9DA4A0.toInt()
    const val GREEN = 0xFF67D78A.toInt()
    const val ON_GREEN = 0xFF05210D.toInt()
    const val ORANGE = 0xFFF0A84B.toInt()
    const val RED = 0xFFFF6B6B.toInt()
    const val BLUE = 0xFF69B8D2.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
    fun dpF(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun rounded(ctx: Context, color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpF(ctx, radiusDp.toFloat())
        if (strokeColor != null) setStroke(dp(ctx, 1), strokeColor)
    }

    fun text(ctx: Context, t: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
    }

    /** Pasek tytułu ekranu: duży napis + podtytuł, opcjonalnie widok po prawej. */
    fun topBar(ctx: Context, title: String, subtitle: CharSequence?, right: View? = null): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(SURFACE)
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 12))
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(ctx, title, 20f, TEXT, bold = true).apply { letterSpacing = 0.03f })
            if (subtitle != null) col.addView(text(ctx, subtitle, 12f, MUTED).apply { setPadding(0, dp(ctx, 2), 0, 0) })
            addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (right != null) addView(right)
        }

    /** Nagłówek sekcji: zielony, WIELKIE LITERY; opcjonalny dopisek po prawej. */
    fun section(ctx: Context, title: String, right: CharSequence? = null, color: Int = GREEN, rightColor: Int = MUTED): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 6))
            addView(text(ctx, title.uppercase(), 12f, color, bold = true).apply { letterSpacing = 0.06f },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (right != null) addView(text(ctx, right, 12f, rightColor))
        }

    /** Wiersz listy (padding jak w M2); separator dodaje [divider]. */
    fun item(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10))
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)).apply {
            marginStart = dp(ctx, 16)
            marginEnd = dp(ctx, 16)
        }
    }

    /** Wiersz „lewa | prawa” z wyrównaniem do linii bazowej. */
    fun row(ctx: Context, left: View, right: View?): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (right != null) addView(right)
    }

    /** Przycisk pełnej szerokości (pigułka): zielony „go” albo czerwonawy „stop”. */
    fun button(ctx: Context, label: String, primary: Boolean, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(ctx, 12), dp(ctx, 11), dp(ctx, 12), dp(ctx, 11))
        if (primary) {
            setTextColor(ON_GREEN)
            background = rounded(ctx, GREEN, 22)
        } else {
            setTextColor(0xFFFFB3B3.toInt())
            background = rounded(ctx, 0xFF3A1F1F.toInt(), 22, 0xFF5A2A2A.toInt())
        }
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 4))
        }
    }

    /** Przycisk konturowy (np. „Test API”). */
    fun outlineButton(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTypeface(null, Typeface.BOLD)
        setTextColor(GREEN)
        setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
        background = rounded(ctx, 0x00000000, 22, OUTLINE)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 4))
        }
    }

    /** Kafelek „etykieta / wartość” (pakiety/h, razem, ostatni). */
    fun kv(ctx: Context, label: String, value: String, valueColor: Int): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, PANEL, 8)
        setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
        addView(text(ctx, label.uppercase(), 9f, MUTED).apply { letterSpacing = 0.06f })
        addView(text(ctx, value, 15f, valueColor, bold = true))
    }

    /** Rząd kafelków [kv] o równej szerokości. */
    fun kvRow(ctx: Context, vararg cells: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(ctx, 8), 0, 0)
        cells.forEachIndexed { i, c ->
            addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(ctx, 6)
            })
        }
    }

    /** Kropka (np. legenda progów). */
    fun dot(ctx: Context, color: Int, sizeDp: Int = 8): View = View(ctx).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp)).apply {
            marginEnd = dp(ctx, 5)
        }
    }

    fun spacer(ctx: Context, hDp: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(ctx, hDp))
    }
}
