package pl.meshcore.observerwidget

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Panel główny (OBSERVER): dashboard z kartami brokerów i mapowania, Settings jako osobny ekran. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private lateinit var dash: LinearLayout
    private var dashboardData: WidgetData? = null
    /** Otwarto z ikony mapowania na widgecie – pokaż/rozwiń kartę mapowania. */
    private var showMapping = false

    /** Co sekundę: przerysowanie z danych w pamięci; pobieranie tylko gdy licznik dojdzie do zera. */
    private val ticker = object : Runnable {
        override fun run() {
            val config = Config(this@MainActivity)
            if (!config.monitoringEnabled) return
            refresh()
            val now = System.currentTimeMillis()
            handler.postDelayed(this, 1_000L - (now % 1_000L) + 15L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showMapping = intent?.getBooleanExtra(EXTRA_SHOW_MAPPING, false) == true
        Alerts.ensureChannels(this)
        RefreshWorker.sync(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111315.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(0xFF171A1C.toInt())
            setBackgroundResource(R.drawable.card_outline)
            isFocusable = true
            minimumHeight = dp(56)
        }
        toolbar.addView(TextView(this).apply {
            text = "Observer"
            textSize = 20f
            setTextColor(WidgetRenderer.GREEN)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        toolbar.addView(Button(this).apply {
            text = "MAP"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF1F282B.toInt())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { startActivity(Intent(this@MainActivity, MapTrackActivity::class.java)) }
        })
        toolbar.addView(Button(this).apply {
            text = "Settings"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF1F282B.toInt())
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        })
        root.addView(toolbar)

        dash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            setBackgroundColor(0xFF111315.toInt())
        }
        // Panel przewijany w pionie: karta mapowania może być wysoka i nie może zasłaniać kart brokerów.
        root.addView(ScrollView(this).apply {
            setBackgroundColor(0xFF111315.toInt())
            addView(dash)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        root.applySystemBarsPadding()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        if (Config(this).monitoringEnabled) {
            // Aplikacja na pierwszym planie: Android pozwala uruchomić usługę tykania widgetu.
            ObserverRefresh.ensureTicking(this)
            handler.post(ticker)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun refresh() {
        if (logSpinnerOpen) return
        if (!Config(this).monitoringEnabled) {
            renderDashboard(dashboardData ?: ObserverRefresh.emptyData(this))
            return
        }
        if (!MonitorService.isRunning && ObserverRefresh.isRefreshDue()) ObserverRefresh.refreshAll(applicationContext)
        renderDashboard(ObserverRefresh.latest() ?: ObserverRefresh.emptyData(this, busy = true))
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
        dash.addView(rptSection())
        dash.addView(spacer())
        // Zakładka LOG: karty mapowań — najnowsze na górze, widoczne przez czas wybrany
        // w liście rozwijanej (24 h / 48 h / 3 dni / 10 dni / 30 dni / na stałe).
        val cfg = Config(this)
        val keepMs = cfg.mapperKeepEffectiveMs
        val logCards = mutableListOf<Pair<Long, View>>()
        val mapping = d.mapping
        val mappingRecent = mapping?.pings?.lastOrNull()?.let { wallClockNow - it.tsMs < keepMs } == true
        if (showMapping || (mapping != null && (mapping.active || mappingRecent))) {
            logCards.add((mapping?.pings?.lastOrNull()?.tsMs ?: 0L) to mappingCard(
                mapping, wallClockNow,
                tag = "OBSERVER", tagColor = WidgetRenderer.MAPPER_BLUE,
                where = "${d.mappingObserverName()} ${d.observerId.take(4)}…",
                fullKey = cfg.observerId, keyLabel = "Observera", snrLabel = "SNR u Obs"
            ))
        }
        // Karty mapowania przy moich repeaterach RPT1–RPT5 (ten sam wybrany czas trzymania).
        for (r in d.rptMappings.sortedBy { it.slot }) {
            val st = r.state
            val recent = st.pings.lastOrNull()?.let { wallClockNow - it.tsMs < keepMs } == true
            if (!st.active && !recent) continue
            if (cfg.rptId(r.slot).isBlank()) continue
            logCards.add((st.pings.lastOrNull()?.tsMs ?: 0L) to mappingCard(
                st, wallClockNow,
                tag = r.label, tagColor = WidgetRenderer.ORANGE,
                where = "${r.name ?: "Repeater"} · ${r.shortId.take(4).uppercase()}…",
                fullKey = null, keyLabel = r.label, snrLabel = "SNR u RPT"
            ))
        }
        if (logCards.isNotEmpty()) {
            dash.addView(logHeader(cfg))
            dash.addView(spacer())
            for ((_, card) in logCards.sortedByDescending { it.first }) {
                dash.addView(card)
                dash.addView(spacer())
            }
        }
        dash.addView(brokerCard("MQTT1", d.mqtt1Host, d.broker1, d, now, d.mqtt1Health))
        dash.addView(spacer())
        dash.addView(brokerCard("MQTT2", d.mqtt2Host, d.broker2, d, now, d.mqtt2Health))
        dash.addView(spacer())
        val fetchedAt = obs?.fetchedAtMs ?: now
        dash.addView(line("Pobrano ${DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(fetchedAt).atZone(ZoneId.systemDefault()))}, następne za ${TimeFormat.countdown(ObserverRefresh.nextRefreshAtMs(this))}", 11f, WidgetRenderer.GRAY))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)) }

    private fun line(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        this.textSize = size
        setTextColor(color)
        setTypeface(null, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        setPadding(dp(6), dp(4), dp(6), dp(4))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun monitoringControl(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setBackgroundColor(0xFF171A1C.toInt())
        setBackgroundResource(R.drawable.card_outline)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(TextView(this@MainActivity).apply {
            text = "Monitoring"
            setTextColor(WidgetRenderer.GREEN)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(0), dp(0), dp(0))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(Button(this@MainActivity).apply {
            text = "STOP"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF1F282B.toInt())
            setOnClickListener { Config(this@MainActivity).monitoringEnabled = false; refresh() }
        })
    }

    private fun rptSection(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xFF171A1C.toInt())
        setBackgroundResource(R.drawable.card_outline)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(TextView(this@MainActivity).apply {
            text = "Repeater status"
            setTextColor(WidgetRenderer.GREEN)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
    }

    private fun logHeader(cfg: Config): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(0xFF171A1C.toInt())
        setBackgroundResource(R.drawable.card_outline)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(TextView(this@MainActivity).apply {
            text = "LOG · ${cfg.mapperKeepHours}h"
            setTextColor(WidgetRenderer.GREEN)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
    }

    private fun mappingCard(
        mapping: MappingState?,
        nowMs: Long,
        tag: String,
        tagColor: Int,
        where: String,
        fullKey: String?,
        keyLabel: String,
        snrLabel: String
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xFF171A1C.toInt())
        setBackgroundResource(R.drawable.card_outline)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(TextView(this@MainActivity).apply {
            text = "$tag · $where"
            setTextColor(tagColor)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        addView(TextView(this@MainActivity).apply {
            text = "${mapping?.active ?: false}".ifEmpty { "—" }
            setTextColor(Color.WHITE)
        })
    }

    private fun brokerCard(
        label: String,
        host: String,
        broker: BrokerState?,
        d: WidgetData,
        nowMs: Long,
        health: Health
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xFF171A1C.toInt())
        setBackgroundResource(R.drawable.card_outline)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(TextView(this@MainActivity).apply {
            text = "$label · ${Config.displayHost(host)}"
            setTextColor(WidgetRenderer.GREEN)
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
    }

    companion object {
        const val EXTRA_SHOW_MAPPING = "pl.meshcore.observerwidget.EXTRA_SHOW_MAPPING"
    }
}

private val Config.mapperKeepHours: Int
    get() = (mapperKeepEffectiveMs / 3_600_000L).toInt().coerceAtLeast(1)

private val Config.monitoringEnabled: Boolean
    get() = false

private val Config.monitoringStoppedAtMs: Long
    get() = 0L

private class BrokerState
private enum class Health { GOOD, BAD }
private class MappingState(val active: Boolean = false)
private class WidgetData(
    val configured: Boolean = false,
    val observer: Observer? = null,
    val mapping: MappingState? = null,
    val rptMappings: List<Any> = emptyList(),
    val mqtt1Host: String = "",
    val mqtt2Host: String = "",
    val broker1: BrokerState? = null,
    val broker2: BrokerState? = null,
    val mqtt1Health: Health = Health.GOOD,
    val mqtt2Health: Health = Health.GOOD,
    val observerId: String = ""
) {
    fun mappingObserverName(): String = "OBSERVER"
}
private class Observer(val name: String?, val model: String?, val firmware: String?, val host: String, val lastPacketMs: Long?, val fetchedAtMs: Long)

private fun Any?.ifEmpty(default: String): String = if (this == null || this == "") default else this.toString()

private fun observerWidgetCompatDummy() {}

