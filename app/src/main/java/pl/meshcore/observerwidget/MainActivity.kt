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
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Zakładki: Dashboard, LIVE (250 pakietów), Settings (osobny ekran). */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private var tab = 0

    private lateinit var tabViews: List<TextView>
    private lateinit var dashScroll: ScrollView
    private lateinit var dash: LinearLayout
    private lateinit var liveBox: LinearLayout
    private lateinit var liveHeader: TextView
    private lateinit var liveList: ListView
    private val adapter = PacketAdapter()

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, Config(this@MainActivity).intervalSec * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Alerts.ensureChannels(this)
        RefreshWorker.sync(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabViews = listOf("Dashboard", "LIVE", "Settings").mapIndexed { i, t ->
            TextView(this).apply {
                text = t; textSize = 18f; gravity = android.view.Gravity.CENTER
                setPadding(0, dp(14), 0, dp(14))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    if (i == 2) startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) else select(i)
                }
            }
        }
        tabViews.forEach { bar.addView(it) }
        root.addView(bar)

        dash = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(14)) }
        dashScroll = ScrollView(this).apply { addView(dash) }

        liveHeader = TextView(this).apply { textSize = 13f; setTextColor(Color.WHITE); setPadding(dp(14), dp(6), dp(14), dp(6)) }
        liveList = ListView(this).apply { this.adapter = this@MainActivity.adapter; divider = null; setBackgroundColor(Color.BLACK) }
        liveBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(liveHeader)
            addView(liveList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        root.addView(dashScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(liveBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        select(0)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    private fun select(i: Int) {
        tab = i
        tabViews.forEachIndexed { idx, v ->
            v.setTextColor(if (idx == i) WidgetRenderer.GREEN else Color.WHITE)
            v.setTypeface(null, if (idx == i) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        dashScroll.visibility = if (i == 0) View.VISIBLE else View.GONE
        liveBox.visibility = if (i == 1) View.VISIBLE else View.GONE
        refresh()
    }

    private fun refresh() {
        if (!busy.compareAndSet(false, true)) return
        val app = applicationContext
        val which = tab
        thread {
            try {
                if (which == 0) {
                    val data = runBlocking { ObserverRefresh.collect(app) }
                    runOnUiThread { renderDashboard(data) }
                } else {
                    val c = Config(app)
                    if (!Config.isValidId(c.observerId)) {
                        runOnUiThread { showLive(null, "Wpisz hash Observera w Settings.", emptyList()) }
                    } else {
                        val t = c.timeoutSec * 1000
                        val hosts = listOf(c.mqtt1Host, c.mqtt2Host, c.fallbackHost).filter { it.isNotBlank() }
                        var ok: PacketsResult.Ok? = null
                        val errors = ArrayList<String>()
                        for (h in hosts) {
                            when (val r = PacketApi.fetch(h, c.observerId, t)) {
                                is PacketsResult.Ok -> { ok = r; break }
                                is PacketsResult.Fail -> errors.add("${Config.displayHost(h)}: ${r.reason}")
                            }
                        }
                        val found = ok
                        runOnUiThread {
                            if (found != null) showLive(found.host, if (found.packets.isEmpty()) "Brak pakietów dla tego Observera." else null, found.packets, errors)
                            else showLive(null, "Nie można pobrać pakietów:\n" + errors.joinToString("\n"), emptyList())
                        }
                    }
                }
            } finally {
                busy.set(false)
            }
        }
    }

    private fun showLive(host: String?, msg: String?, packets: List<LivePacket>, errors: List<String> = emptyList()) {
        val src = host?.let { "Źródło: ${Config.displayHost(it)}" + if (errors.isNotEmpty()) " (zapasowe, bo: ${errors.joinToString("; ")})" else "" }
        liveHeader.text = listOfNotNull(src, "${packets.size} pakietów".takeIf { packets.isNotEmpty() }, msg).joinToString("  •  ")
        liveHeader.setTextColor(if (msg != null && host == null) WidgetRenderer.RED else Color.WHITE)
        adapter.set(packets)
    }

    private fun renderDashboard(d: WidgetData) {
        dash.removeAllViews()
        val now = System.currentTimeMillis()
        if (!d.configured) {
            dash.addView(line("Wejdź w Settings i wpisz pełny hash Observera (64 znaki).", 18f, WidgetRenderer.ORANGE))
            return
        }
        val obs = d.observer
        val (liveText, liveColor) = WidgetRenderer.liveState(d, now)
        dash.addView(line(obs?.name ?: "—", 26f, Color.WHITE, bold = true))
        dash.addView(line(
            listOfNotNull(obs?.model, obs?.firmware?.let { "fw $it" }, d.observerId.take(4).uppercase()).joinToString("  ·  "),
            16f, Color.WHITE
        ))
        dash.addView(line(liveText, 24f, liveColor, bold = true))
        obs?.let { dash.addView(line("Dane z: ${Config.displayHost(it.host)}", 12f, WidgetRenderer.GRAY)) }
        dash.addView(spacer())
        dash.addView(brokerCard("MQTT1", d.mqtt1Host, d.broker1, d, now))
        dash.addView(spacer())
        dash.addView(brokerCard("MQTT2", d.mqtt2Host, d.broker2, d, now))
        dash.addView(spacer())
        dash.addView(line("Odświeżono ${DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))}, co ${d.intervalSec} s", 12f, WidgetRenderer.GRAY))
    }

    private fun brokerCard(title: String, host: String, r: FetchResult?, d: WidgetData, now: Long): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_outline)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        box.addView(line("$title  ${Config.displayHost(host)}", 18f, Color.WHITE, bold = true))
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

    private inner class PacketAdapter : BaseAdapter() {
        private var items: List<LivePacket> = emptyList()
        private val hms = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val dmhms = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")

        fun set(p: List<LivePacket>) { items = p; notifyDataSetChanged() }
        override fun getCount() = items.size
        override fun getItem(i: Int) = items[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, convert: View?, parent: ViewGroup): View {
            val tv = (convert as? TextView) ?: TextView(this@MainActivity).apply {
                setPadding(dp(14), dp(6), dp(14), dp(6)); textSize = 15f; setTextColor(Color.WHITE)
            }
            val p = items[i]
            val ts = p.timeMs?.let {
                val z = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                (if (z.toLocalDate() == LocalDate.now()) hms else dmhms).format(z)
            } ?: "—"
            val rf = listOfNotNull(p.snr?.let { "SNR ${"%.1f".format(java.util.Locale.US, it)}" }, p.rssi?.let { "RSSI ${it.toInt()}" }).joinToString("  ")
            tv.text = "$ts   ${p.type}\n${p.sender}   $rf   ${p.hash}"
            return tv
        }
    }
}
