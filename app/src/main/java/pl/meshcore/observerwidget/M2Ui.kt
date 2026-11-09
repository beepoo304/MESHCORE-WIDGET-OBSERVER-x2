package pl.meshcore.observerwidget

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Wygląd aplikacji w stylu M2: grafitowe tło, duży tytuł ekranu, listy z liniami zamiast ramek,
 * zielone nagłówki sekcji, zielone przyciski-pigułki, pola z obramowaniem i etykietą w ramce.
 * Widoki budowane w kodzie (jak dotąd), bez XML.
 */
class M2Ui(private val act: Activity) {
    companion object {
        const val BG = 0xFF131519.toInt()
        const val HEADER = 0xFF1A1D21.toInt()
        const val GREEN = 0xFF66DC8A.toInt()
        const val MUTED = 0xFF9AA0A6.toInt()
        const val LINE = 0xFF2C2F34.toInt()
        const val NAV = 0xFF22242A.toInt()
        const val PILL = 0xFF4A4558.toInt()
        const val STOP = 0xFFFF6B6B.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val TEXT = 0xFFDDDDDD.toInt()
        const val OBS_BLUE = 0xFF4FC3F7.toInt()
        const val RPT_ORANGE = 0xFFFF9500.toInt()
        const val PUSH_BG = 0xFF22252A.toInt()
        const val CHIP_LINE = 0xFF3A3D42.toInt()
    }

    fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    fun text(t: CharSequence, size: Float = 15f, color: Int = WHITE, bold: Boolean = false, mono: Boolean = false) =
        TextView(act).apply {
            text = t; textSize = size; setTextColor(color)
            typeface = when {
                mono -> Typeface.MONOSPACE
                bold -> Typeface.create("sans-serif-medium", Typeface.BOLD)
                else -> Typeface.create("sans-serif", Typeface.NORMAL)
            }
        }

    fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        strokeColor?.let { setStroke(dp(1), it) }
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) = LinearLayout.LayoutParams(w, h)
    fun weight(w: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)

    fun vertical() = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    /** Zielony nagłówek sekcji (np. „MAPOWANIE · 24 H”). */
    fun section(parent: LinearLayout, title: String, right: String? = null) {
        val r = row().apply { setPadding(dp(18), dp(16), dp(18), dp(4)); gravity = Gravity.BOTTOM }
        r.addView(text(title, 16f, GREEN, bold = true).apply { letterSpacing = 0.04f }, weight())
        right?.let { r.addView(text(it, 13f, GREEN)) }
        parent.addView(r)
    }

    /** Pozycja listy: odstępy jak w M2 i cienka linia pod spodem. */
    fun item(parent: LinearLayout, build: LinearLayout.() -> Unit): LinearLayout {
        val box = vertical().apply { setPadding(dp(18), dp(12), dp(18), dp(12)) }
        box.build()
        parent.addView(box)
        parent.addView(View(act).apply { setBackgroundColor(LINE) }, lp(h = dp(1)))
        return box
    }

    fun muted(t: CharSequence, size: Float = 13f) = text(t, size, MUTED).apply { setPadding(0, dp(2), 0, 0) }

    /** Znacznik źródła: OBSERVER niebieski, RPTn pomarańczowy (czarny napis). */
    fun tag(t: String, color: Int) = text(t, 11f, Color.BLACK, bold = true).apply {
        setPadding(dp(7), dp(1), dp(7), dp(1))
        background = rounded(color, 6)
    }

    fun copyIcon(fullKey: String, label: String) = ImageView(act).apply {
        setImageResource(R.drawable.ic_copy_m2)
        setColorFilter(GREEN)
        setPadding(dp(8), dp(2), dp(4), dp(2))
        contentDescription = "Kopiuj klucz $label"
        layoutParams = LinearLayout.LayoutParams(dp(30), dp(22))
        setOnClickListener { copy(fullKey, label) }
    }

    fun copy(fullKey: String, label: String) {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Klucz $label", fullKey))
        Toast.makeText(act, "Skopiowano klucz $label", Toast.LENGTH_SHORT).show()
    }

    fun shortKey(key: String): String = if (key.length > 16) "${key.take(8).lowercase()}…${key.takeLast(6).lowercase()}" else key.lowercase()

    /** Klucz mapującego pod nickiem: skrót + ikona kopiowania albo „klucz nieznany”. */
    fun keyRow(key: String?, label: String): View {
        val r = row().apply { setPadding(0, dp(3), 0, 0) }
        if (key == null) {
            r.addView(text("klucz nieznany (brak w live API)", 12f, MUTED).apply { setTypeface(typeface, Typeface.ITALIC) })
        } else {
            r.addView(text(shortKey(key), 12f, MUTED, mono = true))
            r.addView(copyIcon(key, label))
        }
        return r
    }

    /** Zielony przycisk-pigułka (albo czerwony „Zatrzymaj”). */
    fun pill(t: String, color: Int = GREEN, full: Boolean = false, onClick: () -> Unit) = text(t, 14f, 0xFF0B0C0E.toInt(), bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(9), dp(18), dp(9))
        background = rounded(color, 999)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = (if (full) lp() else lp(ViewGroup.LayoutParams.WRAP_CONTENT)).apply {
            setMargins(dp(18), dp(12), dp(18), 0)
        }
    }

    fun chip(t: String, on: Boolean, onClick: () -> Unit) = text(t, 13f, if (on) 0xFF0B0C0E.toInt() else MUTED, bold = on).apply {
        setPadding(dp(12), dp(5), dp(12), dp(5))
        background = if (on) rounded(GREEN, 999) else rounded(BG, 999, CHIP_LINE)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
    }

    /** Pasek poziomy (ranking): [fraction] 0–1 wypełnienia w kolorze [color]. */
    fun bar(fraction: Double, color: Int): View {
        val track = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(0xFF2A2D32.toInt(), 3)
            clipToOutline = true
        }
        val f = fraction.coerceIn(0.02, 1.0).toFloat()
        track.addView(View(act).apply { background = rounded(color, 3) }, LinearLayout.LayoutParams(0, dp(6), f))
        if (f < 1f) track.addView(View(act), LinearLayout.LayoutParams(0, dp(6), 1f - f))
        return track
    }

    /** Pole z obramowaniem i etykietą w ramce (jak M2). */
    fun field(parent: LinearLayout, label: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT, mono: Boolean = false): EditText {
        val frame = FrameLayout(act)
        val edit = EditText(act).apply {
            setText(value)
            inputType = type
            isSingleLine = true
            textSize = 15f
            setTextColor(WHITE)
            if (mono) typeface = Typeface.MONOSPACE
            background = rounded(BG, 8, CHIP_LINE)
            setPadding(dp(12), dp(12), dp(12), dp(10))
        }
        frame.addView(edit, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        frame.addView(text(label, 12f, MUTED).apply {
            setBackgroundColor(BG)
            setPadding(dp(5), 0, dp(5), 0)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) })
        parent.addView(frame, lp().apply { setMargins(dp(18), dp(10), dp(18), 0) })
        return edit
    }

    /** Dwa pola obok siebie (progi, opóźnienie/cooldown). */
    fun fieldPair(parent: LinearLayout, l1: String, v1: String, l2: String, v2: String, type: Int): Pair<EditText, EditText> {
        val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        val a = vertical(); val b = vertical()
        val e1 = field(a, l1, v1, type); val e2 = field(b, l2, v2, type)
        (a.getChildAt(0).layoutParams as LinearLayout.LayoutParams).setMargins(0, dp(10), dp(6), 0)
        (b.getChildAt(0).layoutParams as LinearLayout.LayoutParams).setMargins(dp(6), dp(10), 0, 0)
        r.addView(a, weight()); r.addView(b, weight())
        parent.addView(r, lp().apply { setMargins(dp(18), 0, dp(18), 0) })
        return e1 to e2
    }

    /** Przełącznik z opisem (powiadomienia, ekran blokady). */
    fun switchRow(parent: LinearLayout, label: String, on: Boolean, hint: String? = null): Switch {
        val sw = Switch(act).apply {
            text = label
            textSize = 15f
            setTextColor(WHITE)
            isChecked = on
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0xFF0B0C0E.toInt(), MUTED))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(GREEN, CHIP_LINE))
            trackTintMode = android.graphics.PorterDuff.Mode.SRC
            setPadding(0, dp(10), 0, dp(10))
        }
        val box = vertical().apply { setPadding(dp(18), 0, dp(18), 0) }
        box.addView(sw, lp())
        hint?.let { box.addView(muted(it, 12f).apply { setPadding(0, 0, dp(56), dp(6)) }) }
        parent.addView(box)
        parent.addView(View(act).apply { setBackgroundColor(LINE) }, lp(h = dp(1)).apply { setMargins(dp(18), 0, dp(18), 0) })
        return sw
    }

    fun spacer(h: Int = 10) = View(act).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
}
