package pl.meshcore.observerwidget

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Zakładki: Dashboard, LIVE (250 pakietów), Settings (osobny ekran). */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private lateinit var dash: LinearLayout
    private var dashboardData: WidgetData? = null

    private val ticker = object : Runnable {
        override fun run() {
            val config = Config(this@MainActivity)
            if (!config.monitoringEnabled) return
            refresh()
            handler.postDelayed(this, config.intervalSec * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Alerts.ensureChannels(this)
        RefreshWorker.sync(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        toolbar.addView(TextView(this).apply {
            text = "Observer"
            textSize = 20f
            setTextColor(WidgetRenderer.GREEN)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        toolbar.addView(Button(this).apply {
            text = "Settings"
            setTextColor(Color.WHITE)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        root.addView(toolbar)

        dash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(14))
        }
        root.addView(dash, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        root.applySystemBarsPadding()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        if (Config(this).monitoringEnabled) handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun refresh() {
        if (!Config(this).monitoringEnabled) {
            renderDashboard(dashboardData ?: ObserverRefresh.emptyData(this))
            return
        }
        if (!busy.compareAndSet(false, true)) return
        val app = applicationContext
        thread {
            try {
                val data = runBlocking { ObserverRefresh.collect(app) }
                runOnUiThread { renderDashboard(data) }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun renderDashboard(d: WidgetData) {
        dash.removeAllViews()
        dashboardData = d
        val wallClockNow = System.currentTimeMillis()
        val now = if (Config(this).monitoringEnabled) wallClockNow
            else Config(this).monitoringStoppedAtMs.takeIf { it > 0L } ?: 0L
        dash.addView(monitoringControl())
        if (!Config(this).monitoringEnabled) {
            dash.addView(line("Monitoring zatrzymany. Dane nie są odświeżane, alarmy są wyłączone.", 14f, WidgetRenderer.ORANGE))
            dash.addView(spacer())
        }
        if (!d.configured) {
            dash.addView(line("Wejdź w Settings i wpisz pełny hash Observera (64 znaki).", 18f, WidgetRenderer.ORANGE))
            return
        }
        val obs = d.observer
        dash.addView(line(obs?.name ?: "—", 26f, Color.WHITE, bold = true))
        dash.addView(line(
            listOfNotNull(obs?.model, obs?.firmware?.let { "fw $it" }, d.observerId.take(4).uppercase()).joinToString("  ·  "),
            16f, Color.WHITE
        ))
        obs?.let { dash.addView(line("Dane z: ${Config.displayHost(it.host)}", 12f, WidgetRenderer.GRAY)) }
        dash.addView(spacer())
        dash.addView(brokerCard("MQTT1", d.mqtt1Host, d.broker1, d, now, d.mqtt1Health))
        dash.addView(spacer())
        dash.addView(brokerCard("MQTT2", d.mqtt2Host, d.broker2, d, now, d.mqtt2Health))
        dash.addView(spacer())
        dash.addView(line("Odświeżono ${DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))}, co ${d.intervalSec} s", 12f, WidgetRenderer.GRAY))
    }

    private fun monitoringControl(): View = Button(this).apply {
        val config = Config(this@MainActivity)
        text = if (config.monitoringEnabled) "ZATRZYMAJ MONITORING" else "URUCHOM MONITORING"
        setTextColor(Color.WHITE)
        setBackgroundColor(if (config.monitoringEnabled) 0xFF8E2525.toInt() else 0xFF246B2B.toInt())
        setOnClickListener {
            val wasEnabled = Config(this@MainActivity).monitoringEnabled
            if (wasEnabled) {
                ObserverRefresh.stop(this@MainActivity)
                handler.removeCallbacks(ticker)
            } else {
                ObserverRefresh.start(this@MainActivity)
                handler.removeCallbacks(ticker)
                handler.post(ticker)
            }
            renderDashboard(dashboardData ?: ObserverRefresh.emptyData(this@MainActivity))
        }
    }

    private fun brokerCard(title: String, host: String, r: FetchResult?, d: WidgetData, now: Long, health: Health): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_outline)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val titleColor = when (health) { Health.GOOD -> Color.WHITE; Health.BAD -> WidgetRenderer.RED; Health.UNKNOWN -> WidgetRenderer.GRAY }
        box.addView(line("$title  ${Config.displayHost(host)}", 18f, titleColor, bold = true))
        when (r) {
            is FetchResult.Ok -> {
                val s = r.snapshot
                box.addView(line("${s.packetsPerHour} pkt/h   ·   razem ${Status.formatCount(s.totalPackets)}", 16f, WidgetRenderer.GREEN))
                if (s.lastPacketMs == null) box.addView(line("Ostatni pakiet: brak danych", 16f, WidgetRenderer.GRAY))
                else {
                    val age = now - s.lastPacketMs
                    val color = when (Status.level(age, d.orangeMin, d.redMin)) {
                        Level.GREEN -> WidgetRenderer.GREEN; Level.ORANGE -> WidgetRenderer.ORANGE; else -> WidgetRenderer.RED
                    }
                    box.addView(line("Ostatni pakiet: ${Status.formatAge(age)} temu", 18f, color, bold = true))
                }
            }
            is FetchResult.Fail -> box.addView(line("API niedostępne: ${r.reason}", 16f, WidgetRenderer.RED))
            null -> box.addView(line("…", 16f, WidgetRenderer.GRAY))
        }
        return box
    }

    private fun line(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(10)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

}
