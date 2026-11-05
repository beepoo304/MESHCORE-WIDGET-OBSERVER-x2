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
        // Panel przewijany w pionie: karta mapowania może być wysoka i nie może zasłaniać kart brokerów.
        root.addView(ScrollView(this).apply { addView(dash) }, LinearLayout.LayoutParams(
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
        // Karta mapowania: gdy ktoś mapuje lub mapował w ciągu 24 h, albo po kliknięciu MAPPER na widgecie.
        val mapping = d.mapping
        val mappingRecent = mapping?.pings?.lastOrNull()?.let { wallClockNow - it.tsMs < MapperApi.KEEP_MS } == true
        if (showMapping || (mapping != null && (mapping.active || mappingRecent))) {
            dash.addView(mappingCard(mapping, d, wallClockNow))
            dash.addView(spacer())
        }
        dash.addView(brokerCard("MQTT1", d.mqtt1Host, d.broker1, d, now, d.mqtt1Health))
        dash.addView(spacer())
        dash.addView(brokerCard("MQTT2", d.mqtt2Host, d.broker2, d, now, d.mqtt2Health))
        dash.addView(spacer())
        val fetchedAt = obs?.fetchedAtMs ?: now
        dash.addView(line("Pobrano ${DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(fetchedAt).atZone(ZoneId.systemDefault()))}, następne za ${TimeFormat.countdown(ObserverRefresh.remainingSeconds(this, wallClockNow), Config(this).monitoringEnabled)}", 12f, WidgetRenderer.GRAY))
    }

    /**
     * Karta mapowania — KOMPAKTOWA (2–3 linijki), widoczna 24 h od ostatniego pinga:
     * tytuł z kluczem i ikoną kopiowania, mapujący z kierunkiem i odległością
     * (nick w kolorze strzałki tego mapera na widgecie), statystyki pingów.
     */
    private fun mappingCard(m: MappingState?, d: WidgetData, nowMs: Long): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_outline)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val active = m?.active == true
        // Pulsująca kropka w tytule: sekunda jasna, sekunda przyciemniona (jak ikona na widgecie).
        val pulseOn = WidgetRenderer.mappingIconOn(nowMs)
        val titleColor = when {
            active && pulseOn -> 0xFF4FC3F7.toInt()
            active -> 0xFF1F6E8F.toInt()
            else -> WidgetRenderer.GRAY
        }
        // Linia 1: tytuł + klucz (pierwsze 4 znaki) + ikona kopiowania pełnego (jak w widgecie).
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        titleRow.addView(line(if (active) "● MAPOWANIE W TOKU" else "OSTATNIE MAPOWANIE", 18f, titleColor, bold = true))
        titleRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        titleRow.addView(line("${d.mappingObserverName()} ${d.observerId.take(4)}…", 15f, Color.WHITE))
        val copy = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_copy)
            setPadding(dp(6), dp(2), dp(2), dp(2))
            contentDescription = "Kopiuj pełny klucz Observera"
            setOnClickListener {
                CopyKeyReceiver.fullKey(Config(this@MainActivity).observerId)?.let { key ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Klucz Observera", key))
                    android.widget.Toast.makeText(this@MainActivity, "Skopiowano pełny klucz Observera", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        titleRow.addView(copy)
        box.addView(titleRow)
        if (m == null) {
            box.addView(line("Brak danych mapowania (mapper meshcoresouth.pl nie odpowiada).", 15f, WidgetRenderer.GRAY))
            return box
        }
        // Linia 2: mapujący z kierunkiem i odległością od Observera (pozycja RPT z bazy mapera);
        // nick w kolorze strzałki danego mapera na widgecie (czerwony/żółty/fioletowy/…).
        val latest = m.pings
            .filter { it.lat != null && it.lon != null }
            .groupBy { it.userId }
            .map { (_, v) -> v.last() }
            .sortedBy { it.userId }
        if (latest.isNotEmpty() || m.mapperNicks.isNotEmpty()) {
            val sb = android.text.SpannableStringBuilder()
            sb.append(if (active) "Mapuje: " else "Mapowali: ")
            if (latest.isEmpty()) {
                sb.append(m.mapperNicks.joinToString(", "))
            } else latest.forEachIndexed { i, p ->
                val nick = MapperMonitor.nick(p.userId) ?: m.mapperNicks.getOrNull(i) ?: "user ${p.userId}"
                val nStart = sb.length
                sb.append(nick)
                sb.setSpan(
                    android.text.style.ForegroundColorSpan(WidgetRenderer.mapperArrowColor(i)),
                    nStart, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (m.observerLat != null && m.observerLon != null) {
                    val bearing = GeoMath.bearingDeg(m.observerLat, m.observerLon, p.lat!!, p.lon!!)
                    val dist = GeoMath.distanceKm(m.observerLat, m.observerLon, p.lat, p.lon!!)
                    sb.append(" ${GeoMath.km(dist)} ${GeoMath.compassLabel(bearing)}")
                }
                if (i < latest.size - 1) sb.append(" · ")
            }
            box.addView(TextView(this).apply {
                text = sb; textSize = 15f; setTextColor(Color.WHITE)
                setPadding(0, dp(2), 0, dp(2))
            })
        }
        // Linia 3: liczba pingów, ostatni ping, SNR u Observera (jeśli jest).
        val pings = m.pings
        if (pings.isNotEmpty()) {
            val last = pings.last()
            val parts = mutableListOf(
                "Pingów: ${pings.size}",
                "ostatni ${TimeFormat.packetAge(nowMs - last.tsMs)} temu (${timeOf(last.tsMs)})"
            )
            last.observerSnrDb?.let { parts.add("SNR u Obs: ${fmt(it)} dB") }
            box.addView(line(parts.joinToString(" · "), 15f, Color.WHITE))
        }
        return box
    }

    private fun WidgetData.mappingObserverName(): String =
        observer?.name ?: "Observer"

    private fun timeOf(ms: Long): String =
        DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    private fun fmt(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v)

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
                MonitorService.ensureChannel(this@MainActivity)
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
        val titleColor = WidgetRenderer.brokerHealthColor(health, r)
        box.addView(line("$title  ${Config.displayHost(host)}", 18f, titleColor, bold = true))
        when (r) {
            is FetchResult.Ok -> {
                val s = r.snapshot
                box.addView(line("${s.packetsPerHour} pkt/h   ·   razem ${Status.formatCount(s.totalPackets)}", 16f, WidgetRenderer.GREEN))
                if (s.lastPacketMs == null) box.addView(line("Ostatni pakiet: brak danych", 16f, WidgetRenderer.GRAY))
                else {
                    val age = s.lastPacketAgeMs(now) ?: 0L
                    val cfg = Config(this)
                    val color = when (Status.level(age, cfg.orangeMin, cfg.redMin)) {
                        Level.GREEN -> WidgetRenderer.GREEN; Level.ORANGE -> WidgetRenderer.ORANGE; else -> WidgetRenderer.RED
                    }
                    box.addView(line("Ostatni pakiet: ${TimeFormat.packetAge(age)} temu", 18f, color, bold = true))
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

    companion object {
        const val EXTRA_SHOW_MAPPING = "pl.meshcore.observerwidget.SHOW_MAPPING"

        /** Ikona „M” na widgecie: otwiera aplikację z rozwiniętą kartą mapowania. */
        fun mappingIntent(ctx: Context): PendingIntent =
            PendingIntent.getActivity(
                ctx, 4,
                Intent(ctx, MainActivity::class.java)
                    .putExtra(EXTRA_SHOW_MAPPING, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
