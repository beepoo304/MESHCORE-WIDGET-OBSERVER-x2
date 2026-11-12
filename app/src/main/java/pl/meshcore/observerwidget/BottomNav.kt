package pl.meshcore.observerwidget

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Dolny pasek aplikacji (styl M2 NavigationBar): ikona + podpis, aktywna karta w „pigułce”.
 * Bez bibliotek Material – zwykłe widoki, wektorowe ikony barwione w locie.
 */
class BottomNav(ctx: Context, private val onSelect: (Int) -> Unit) : LinearLayout(ctx) {
    private class Tab(val pill: FrameLayout, val icon: ImageView, val label: TextView, val badge: View)

    private val tabs = mutableListOf<Tab>()
    var selected = -1
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Ui.SURFACE)
        setPadding(0, Ui.dp(ctx, 8), 0, Ui.dp(ctx, 10))
        add(R.drawable.ic_nav_observer, "Observer")
        add(R.drawable.ic_nav_map, "Mapa")
        add(R.drawable.ic_nav_log, "Log")
        add(R.drawable.ic_nav_outage, "Przestoje")
        add(R.drawable.ic_nav_settings, "Ustawienia")
    }

    private fun add(icon: Int, title: String) {
        val ctx = context
        val index = tabs.size
        val cell = LinearLayout(ctx).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            contentDescription = title
            setOnClickListener { onSelect(index) }
        }
        val pill = FrameLayout(ctx)
        val iv = ImageView(ctx).apply { setImageResource(icon) }
        pill.addView(iv, FrameLayout.LayoutParams(Ui.dp(ctx, 22), Ui.dp(ctx, 22), Gravity.CENTER))
        val badge = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.RED) }
            visibility = View.GONE
        }
        pill.addView(badge, FrameLayout.LayoutParams(Ui.dp(ctx, 8), Ui.dp(ctx, 8), Gravity.TOP or Gravity.END).apply {
            topMargin = Ui.dp(ctx, 2)
            marginEnd = Ui.dp(ctx, 12)
        })
        cell.addView(pill, LayoutParams(Ui.dp(ctx, 56), Ui.dp(ctx, 30)))
        val label = TextView(ctx).apply {
            text = title
            textSize = 11f
            gravity = Gravity.CENTER
            isSingleLine = true
            includeFontPadding = false
            setPadding(0, Ui.dp(ctx, 4), 0, 0)
        }
        cell.addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(cell, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        tabs.add(Tab(pill, iv, label, badge))
        paint(index, false)
    }

    private fun paint(i: Int, on: Boolean) {
        val t = tabs[i]
        val color = if (on) Ui.TEXT else Ui.MUTED
        t.icon.setColorFilter(color)
        t.label.setTextColor(color)
        t.label.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        t.pill.background = if (on) Ui.rounded(context, Ui.PILL, 15) else null
    }

    /**
     * Jedna wspólna wielkość podpisów: największa (≤ 11 sp), przy której najdłuższy podpis
     * (pogrubiony, jak na aktywnej karcie) mieści się w kolumnie z marginesem – równy pasek.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || tabs.isEmpty()) return
        val avail = w / tabs.size - Ui.dp(context, 6)
        val paint = android.text.TextPaint().apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }
        var size = 11f
        while (size > 8f) {
            paint.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, size, resources.displayMetrics)
            if (tabs.all { paint.measureText(it.label.text.toString()) <= avail }) break
            size -= 0.5f
        }
        post { tabs.forEach { it.label.textSize = size } }
    }

    fun select(i: Int) {
        if (i == selected) return
        if (selected in tabs.indices) paint(selected, false)
        selected = i
        paint(i, true)
    }

    /** Czerwona kropka na ikonie (np. Observer, gdy któryś broker jest BAD). */
    fun setBadge(i: Int, on: Boolean) {
        val v = if (on) View.VISIBLE else View.GONE
        if (tabs[i].badge.visibility != v) tabs[i].badge.visibility = v
    }
}
