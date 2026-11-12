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
            setBackgroundColor(M2_BG)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(M2_BG)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        toolbar.addView(TextView(this).apply {
            text = "Observer"
            textSize = 18f
            setTextColor(M2_ACCENT)
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.04f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        fun navBtn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setTextColor(M2_TEXT)
            setAllCaps(false)
            textSize = 13f
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF1E2225.toInt())
                setStroke(dp(1), 0xFF2A3033.toInt())
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
            setOnClickListener { onClick() }
        }
        toolbar.addView(navBtn("Mapa") { startActivity(Intent(this@MainActivity, MapTrackActivity::class.java)) })
        toolbar.addView(navBtn("Ustawienia") { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) })
        root.addView(toolbar)

        dash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(16))
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
        val cfg = Config(this)
        val wallClockNow = System.currentTimeMillis()
        val now = if (cfg.monitoringEnabled) wallClockNow
            else cfg.monitoringStoppedAtMs.takeIf { it > 0L } ?: 0L

        dash.addView(sectionTitle("Obserwator"))
        dash.addView(monitoringControl())
        dash.addView(smallSpacer())
        if (!cfg.monitoringEnabled) {
            dash.addView(infoCard("Monitoring zatrzymany. Dane nie są odświeżane, alarmy są wyłączone.", M2_ORANGE))
            dash.addView(spacer())
        }
        if (!d.configured) {
            dash.addView(infoCard("Wejdź w Settings i wpisz pełny hash Observera (64 znaki).", M2_ORANGE))
            return
        }
        dash.addView(observerCard(d))
        dash.addView(spacer())

        dash.addView(sectionTitle("Brokery MQTT"))
        dash.addView(brokerCard("MQTT1", d.mqtt1Host, d.broker1, d, now, d.mqtt1Health))
        dash.addView(smallSpacer())
        dash.addView(brokerCard("MQTT2", d.mqtt2Host, d.broker2, d, now, d.mqtt2Health))
        dash.addView(spacer())

        mappingHistorySection(d, cfg, wallClockNow)?.let { sec ->
            dash.addView(sectionTitle("Historia mapowań"))
            dash.addView(sec)
            dash.addView(spacer())
        }

        dash.addView(sectionTitle("Moje repeatery"))
        dash.addView(rptSection())
        dash.addView(spacer())

        val fetchedAt = d.observer?.fetchedAtMs ?: now
        dash.addView(line(
            "Pobrano ${DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(fetchedAt).atZone(ZoneId.systemDefault()))} · następne za ${TimeFormat.countdown(ObserverRefresh.remainingSeconds(this, wallClockNow), cfg.monitoringEnabled)}",
            12f, M2_MUTED
        ))
    }

    private fun observerCard(d: WidgetData): View {
        val box = cardBox()
        val obs = d.observer
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = obs?.name ?: "—"
            textSize = 20f
            setTextColor(M2_TEXT)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val live = WidgetRenderer.liveState(d, System.currentTimeMillis())
        header.addView(line(live.first, 12f, live.second, bold = true))
        box.addView(header)
        box.addView(line(
            listOfNotNull(obs?.model, obs?.firmware?.let { "fw $it" }, d.observerId.take(4).uppercase()).joinToString("  ·  "),
            13f, M2_SUBTEXT
        ))
        obs?.let { box.addView(line("Dane z: ${Config.displayHost(it.host)}", 12f, M2_MUTED)) }
        return box
    }

    private fun mappingHistorySection(d: WidgetData, cfg: Config, wallClockNow: Long): View? {
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
        if (logCards.isEmpty()) return null
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(mappingHeader(cfg))
        wrap.addView(smallSpacer())
        for ((_, card) in logCards.sortedByDescending { it.first }) {
            wrap.addView(card)
            wrap.addView(smallSpacer())
        }
        return wrap
    }

    private fun mappingHeader(cfg: Config): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
        }
        row.addView(TextView(this).apply {
            text = "Zakres"
            textSize = 13f
            setTextColor(M2_MUTED)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val spinner = object : Spinner(this) {
            override fun performClick(): Boolean {
                logSpinnerOpen = true
                return super.performClick()
            }
        }.apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, keepOptions.map { it.second }).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val idx = keepOptions.indexOfFirst { it.first == cfg.mapperKeepMs }
            if (idx >= 0) setSelection(idx)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) { logSpinnerOpen = false }
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    logSpinnerOpen = false
                    val value = keepOptions[position].first
                    if (value == cfg.mapperKeepMs) return
                    cfg.mapperKeepMs = value
                    MapperMonitor.refreshKeepMs(this@MainActivity)
                    refresh()
                }
            }
        }
        row.addView(spinner)
        return row
    }

    private fun cardBox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.card_outline)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun infoCard(text: String, color: Int): View = cardBox().apply {
        addView(line(text, 14f, color))
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(M2_MUTED)
        setTypeface(null, android.graphics.Typeface.BOLD)
        letterSpacing = 0.06f
        setPadding(dp(2), dp(4), 0, dp(6))
    }

    /**
     * Karta mapowania — KOMPAKTOWA (2–3 linijki), widoczna 24 h od ostatniego pinga:
     * tytuł z kluczem i ikoną kopiowania, mapujący z kierunkiem i odległością
     * (nick w kolorze strzałki tego mapera na widgecie), statystyki pingów.
     */
    private fun mappingCard(
        m: MappingState?, nowMs: Long, tag: String, tagColor: Int, where: String,
        fullKey: String?, keyLabel: String, snrLabel: String
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_outline)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val active = m?.active == true
        // Pulsująca kropka w tytule: sekunda jasna, sekunda przyciemniona (jak ikona na widgecie).
        val pulseOn = WidgetRenderer.mappingIconOn(nowMs)
        val titleColor = when {
            active && pulseOn -> tagColor
            active -> if (tagColor == WidgetRenderer.ORANGE) WidgetRenderer.rptIconColor(false) else 0xFF1F6E8F.toInt()
            else -> WidgetRenderer.GRAY
        }
        // Linia 1: znacznik źródła (OBSERVER niebieski / RPTn pomarańczowy) + stan mapowania.
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        titleRow.addView(tagView(tag, tagColor))
        titleRow.addView(line(if (active) "● MAPOWANIE W TOKU" else "OSTATNIE MAPOWANIE", 17f, titleColor, bold = true).apply {
            setPadding(dp(8), dp(2), 0, dp(2))
        })
        box.addView(titleRow)
        // Linia 2: nazwa (Observer / repeater z bazy mapera) + klucz (4 znaki); kopiowanie tylko klucza Observera.
        val whereRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        whereRow.addView(line(where, 15f, Color.WHITE).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (fullKey != null) whereRow.addView(copyButton(fullKey, keyLabel))
        box.addView(whereRow)
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
            sb.append(if (active) "Mapuje: " else "Mapował: ")
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
                "ostatni ping: ${timeOf(last.tsMs)} · ${TimeFormat.dayMonth(last.tsMs)}"
            )
            last.observerSnrDb?.let { parts.add("$snrLabel: ${fmt(it)} dB") }
            box.addView(line(parts.joinToString(" · "), 15f, Color.WHITE))
        }
        return box
    }

    /** Kolorowy znacznik źródła: OBSERVER / RPT1… (czarny napis na kolorowym tle). */
    private fun tagView(text: String, color: Int, alpha: Float = 1f) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.BLACK)
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(6).toFloat()
        }
        this.alpha = alpha
    }

    private fun copyButton(fullKey: String, label: String) = android.widget.ImageView(this).apply {
        setImageResource(R.drawable.ic_copy)
        setPadding(dp(8), dp(4), dp(4), dp(4))
        contentDescription = "Kopiuj pełny klucz $label"
        setOnClickListener {
            val key = CopyKeyReceiver.fullKey(fullKey) ?: fullKey.takeIf { it.isNotBlank() } ?: return@setOnClickListener
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Klucz $label", key))
            android.widget.Toast.makeText(this@MainActivity, "Skopiowano klucz $label", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Sekcja „MOJE REPEATERY” na karcie OBSERVER: do 5 kluczy repeaterów (RPT1–RPT5).
     * Klucz wpisuje się w okienku (panel przerysowuje się co sekundę, więc pole tekstowe
     * na stałe by znikało). Pod kluczem: czy repeater jest w bazie mappera i jego nazwa.
     */
    private fun rptSection(): View {
        val cfg = Config(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_outline)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        box.addView(line("Mapowanie przy RPT1–RPT5 (pomarańczowe) na widgecie i powiadomieniach.", 12f, M2_MUTED))
        val ids = cfg.rptIds
        for ((slot, id) in ids) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            row.addView(tagView("RPT$slot", WidgetRenderer.ORANGE))
            row.addView(TextView(this).apply {
                text = if (id.length > 14) "${id.take(8)}…${id.takeLast(4)}" else id
                textSize = 14f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(0xFFDDDDDD.toInt())
                setPadding(dp(10), dp(4), dp(4), dp(4))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { editRptDialog(slot) }
            })
            row.addView(copyButton(id, "RPT$slot"))
            row.addView(TextView(this).apply {
                text = "✕"
                textSize = 18f
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(2), dp(4), dp(2))
                contentDescription = "Usuń RPT$slot"
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Usunąć RPT$slot?")
                        .setMessage("Klucz ${id.take(8)}… zniknie z widgetu i powiadomień.")
                        .setPositiveButton("Usuń") { _, _ ->
                            Config(this@MainActivity).setRptId(slot, "")
                            renderDashboard(dashboardData ?: ObserverRefresh.emptyData(this@MainActivity))
                        }
                        .setNegativeButton("Anuluj", null)
                        .show()
                }
            })
            box.addView(row)
            val info = MapperMonitor.rptInfo(MapperApi.shortId(id))
            val (status, color) = when {
                info == null -> "sprawdzam w bazie mappera…" to WidgetRenderer.GRAY
                info.found -> "✓ w bazie mappera: „${info.name ?: "bez nazwy"}”" to WidgetRenderer.GREEN
                info.name != null -> "„${info.name}” bez pozycji w bazie mappera – bez strzałki i odległości" to WidgetRenderer.ORANGE
                else -> "✕ brak w bazie mappera – bez nazwy, strzałki i odległości" to WidgetRenderer.ORANGE
            }
            box.addView(line(status, 12f, color).apply { setPadding(dp(4), dp(2), 0, 0) })
        }
        val free = (1..Config.RPT_SLOTS).firstOrNull { it !in ids }
        if (free != null) {
            box.addView(TextView(this).apply {
                text = "+ dodaj repeater (max ${Config.RPT_SLOTS})"
                textSize = 14f
                setTextColor(WidgetRenderer.ORANGE)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(4))
                setOnClickListener { editRptDialog(free) }
            })
        }
        return box
    }

    /** Okienko wpisania/zmiany klucza RPTn (tylko repeater; Observer ma własne pole w Settings). */
    private fun editRptDialog(slot: Int) {
        val cfg = Config(this)
        val input = android.widget.EditText(this).apply {
            setText(cfg.rptId(slot))
            hint = "klucz repeatera (64 znaki hex)"
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 14f
            isSingleLine = true
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("RPT$slot: klucz repeatera")
            .setView(wrap)
            .setPositiveButton("Zapisz", null)
            .setNegativeButton("Anuluj", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = input.text.toString().filterNot { it.isWhitespace() }
                val short = MapperApi.shortId(id)
                val err = when {
                    !Config.isValidRptId(id) -> "Klucz: znaki 0-9 i A-F, od 16 do 64 znaków."
                    short == MapperApi.shortId(cfg.observerId) -> "To klucz Observera – wpisz klucz innego repeatera."
                    cfg.rptIds.any { (s, other) -> s != slot && MapperApi.shortId(other) == short } -> "Ten repeater jest już na liście."
                    else -> null
                }
                if (err != null) {
                    input.error = err
                    return@setOnClickListener
                }
                cfg.setRptId(slot, id)
                dialog.dismiss()
                renderDashboard(dashboardData ?: ObserverRefresh.emptyData(this))
                // Od razu sprawdzamy repeater w bazie mappera (nazwa + pozycja do strzałki).
                thread {
                    MapperMonitor.lookupRpt(applicationContext, id, 6_000)
                    runOnUiThread { if (!isFinishing) renderDashboard(dashboardData ?: ObserverRefresh.emptyData(this)) }
                }
            }
        }
        dialog.show()
    }

    private fun WidgetData.mappingObserverName(): String =
        observer?.name ?: "Observer"

    private fun timeOf(ms: Long): String =
        DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    private fun fmt(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v)

    /** Opcje retencji śladu mapowania w LOG: (wartość ms, −1 = na stałe) → etykieta. */
    /** Lista rozwinięta → ticker nie przerysowuje panelu (inaczej lista znika przed kliknięciem). */
    private var logSpinnerOpen = false

    private val keepOptions = listOf(
        24 * 3600_000L to "24 h",
        48 * 3600_000L to "48 h",
        3 * 24 * 3600_000L to "3 dni",
        10 * 24 * 3600_000L to "10 dni",
        30 * 24 * 3600_000L to "30 dni",
        -1L to "na stałe"
    )


    private fun monitoringControl(): View = Button(this).apply {
        val config = Config(this@MainActivity)
        text = if (config.monitoringEnabled) "Zatrzymaj monitoring" else "Uruchom monitoring"
        setTextColor(Color.WHITE)
        setAllCaps(false)
        textSize = 14f
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(if (config.monitoringEnabled) 0xFF8E2525.toInt() else 0xFF246B2B.toInt())
            cornerRadius = dp(10).toFloat()
        }
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
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(line(title, 17f, titleColor, bold = true))
        head.addView(line("  ${Config.displayHost(host)}", 13f, WidgetRenderer.GRAY))
        box.addView(head)
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

    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(12)) }
    private fun smallSpacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(6)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_SHOW_MAPPING = "pl.meshcore.observerwidget.SHOW_MAPPING"

        // Paleta UI (styl m2): ciemny, minimalistyczny — bez zmian w widgecie.
        private val M2_BG = 0xFF111315.toInt()
        private val M2_TEXT = 0xFFE2E5E3.toInt()
        private val M2_SUBTEXT = 0xFFD6D9D7.toInt()
        private val M2_MUTED = 0xFF9DA4A0.toInt()
        private val M2_ACCENT = 0xFF67D78A.toInt()
        private val M2_ORANGE = 0xFFF0A84B.toInt()

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