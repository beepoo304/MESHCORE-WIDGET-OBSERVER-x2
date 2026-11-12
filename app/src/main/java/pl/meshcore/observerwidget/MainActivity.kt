package pl.meshcore.observerwidget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Aplikacja z dolnym paskiem (styl M2): Observer · Mapa · Log · Ustawienia.
 * Observer = stan Observera, brokery MQTT, „Mapują teraz”, moje repeatery; Log = karty mapowań;
 * Mapa = [MapScreen]; Ustawienia = [SettingsScreen]. Dane i logika bez zmian – tylko prezentacja.
 */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private lateinit var nav: BottomNav
    private lateinit var content: FrameLayout
    private lateinit var obsScroll: ScrollView
    private lateinit var dash: LinearLayout
    private lateinit var logScroll: ScrollView
    private lateinit var logDash: LinearLayout
    private var settingsScroll: ScrollView? = null
    private var mapScreen: MapScreen? = null
    private var tab = -1
    private var dashboardData: WidgetData? = null
    /** Otwarto z ikony mapowania na widgecie – karta Log z kartą mapowania Observera. */
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
            setBackgroundColor(Ui.BG)
        }
        content = FrameLayout(this)
        dash = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        obsScroll = ScrollView(this).apply { addView(dash) }
        logDash = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logScroll = ScrollView(this).apply { addView(logDash) }
        content.addView(obsScroll, matchParent())
        content.addView(logScroll, matchParent())
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        nav = BottomNav(this) { selectTab(it) }
        root.addView(nav)
        setContentView(root)
        root.applySystemBarsPadding()

        selectTab(if (showMapping) TAB_LOG else savedInstanceState?.getInt(STATE_TAB, TAB_OBSERVER) ?: TAB_OBSERVER)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_MAPPING, false)) {
            showMapping = true
            selectTab(TAB_LOG)
            logScroll.scrollTo(0, 0)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab)
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

    override fun onDestroy() {
        mapScreen?.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val map = mapScreen
        when {
            tab == TAB_MAP && map != null && map.canGoBack() -> map.goBack()
            tab != TAB_OBSERVER -> selectTab(TAB_OBSERVER)
            else -> super.onBackPressed()
        }
    }

    /** Przełączenie karty dolnego paska: widoczny jest tylko jeden ekran. */
    private fun selectTab(t: Int) {
        if (t == tab) {
            // Ponowne dotknięcie aktywnej karty: przewinięcie na górę.
            when (t) {
                TAB_OBSERVER -> obsScroll.smoothScrollTo(0, 0)
                TAB_LOG -> logScroll.smoothScrollTo(0, 0)
                TAB_SETTINGS -> settingsScroll?.smoothScrollTo(0, 0)
            }
            return
        }
        tab = t
        nav.select(t)
        hideKeyboard()
        obsScroll.visibility = if (t == TAB_OBSERVER) View.VISIBLE else View.GONE
        logScroll.visibility = if (t == TAB_LOG) View.VISIBLE else View.GONE
        if (t == TAB_MAP) {
            val map = mapScreen ?: MapScreen(this, embedded = true) { selectTab(TAB_OBSERVER) }.also {
                mapScreen = it
                content.addView(it.view, matchParent())
            }
            map.view.visibility = View.VISIBLE
            map.onShown()
        } else mapScreen?.view?.visibility = View.GONE
        // Ustawienia budowane od nowa przy każdym wejściu: pola pokazują bieżący zapis.
        settingsScroll?.let { content.removeView(it) }
        settingsScroll = null
        if (t == TAB_SETTINGS) {
            val sv = ScrollView(this).apply { addView(SettingsScreen(this@MainActivity).build()) }
            settingsScroll = sv
            content.addView(sv, matchParent())
        }
        refresh()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        content.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }
    }

    private fun refresh() {
        if (logSpinnerOpen) return
        if (!Config(this).monitoringEnabled) {
            render(dashboardData ?: ObserverRefresh.emptyData(this))
            return
        }
        if (!MonitorService.isRunning && ObserverRefresh.isRefreshDue()) ObserverRefresh.refreshAll(applicationContext)
        render(ObserverRefresh.latest() ?: ObserverRefresh.emptyData(this, busy = true))
    }

    /** Przerysowanie tylko widocznej karty (Mapa i Ustawienia mają własny stan). */
    private fun render(d: WidgetData) {
        dashboardData = d
        nav.setBadge(TAB_OBSERVER, d.configured && (d.mqtt1Health == Health.BAD || d.mqtt2Health == Health.BAD))
        when (tab) {
            TAB_OBSERVER -> renderDashboard(d)
            TAB_LOG -> renderLog(d)
        }
    }

    private fun rerender() = render(dashboardData ?: ObserverRefresh.emptyData(this))

    // ───────────────────────────── OBSERVER ─────────────────────────────

    private fun renderDashboard(d: WidgetData) {
        dash.removeAllViews()
        val cfg = Config(this)
        val wallClockNow = System.currentTimeMillis()
        val now = if (cfg.monitoringEnabled) wallClockNow
            else cfg.monitoringStoppedAtMs.takeIf { it > 0L } ?: 0L
        // Stan LIVE/IDLE liczony tym samym (po zatrzymaniu zamrożonym) czasem co wiek pakietów.
        val live = if (d.configured) WidgetRenderer.liveState(d, if (now > 0L) now else wallClockNow) else null

        dash.addView(Ui.topBar(
            this, "OBSERVER",
            if (cfg.monitoringEnabled) "Monitoring aktywny · co ${cfg.intervalSec} s" else "Monitoring zatrzymany",
            live?.let { Ui.text(this, it.first, 13f, appColor(it.second), bold = true).apply { letterSpacing = 0.05f } }
        ))
        if (!cfg.monitoringEnabled) {
            dash.addView(infoItem("Monitoring zatrzymany. Dane nie są odświeżane, alarmy są wyłączone.", Ui.ORANGE))
        }
        if (!d.configured) {
            dash.addView(infoItem("Wejdź w Ustawienia i wpisz pełny hash Observera (64 znaki).", Ui.ORANGE))
            dash.addView(Ui.button(this, "Otwórz Ustawienia", primary = true) { selectTab(TAB_SETTINGS) })
            dash.addView(monitoringControl())
            return
        }

        mappingNowSection(d, cfg, wallClockNow)

        dash.addView(Ui.section(this, "Observer"))
        dash.addView(observerItem(d))

        val ok = listOf(d.mqtt1Health, d.mqtt2Health).count { it == Health.GOOD }
        dash.addView(Ui.section(this, "Brokery MQTT", "$ok / 2 OK", rightColor = when (ok) {
            2 -> Ui.GREEN
            1 -> Ui.ORANGE
            else -> Ui.RED
        }))
        dash.addView(brokerItem("MQTT1", d.mqtt1Host, d.broker1, now, d.mqtt1Health, d.broker1FrozenAgeMs, cfg))
        dash.addView(Ui.divider(this))
        dash.addView(brokerItem("MQTT2", d.mqtt2Host, d.broker2, now, d.mqtt2Health, d.broker2FrozenAgeMs, cfg))
        if (cfg.fallbackHost.isNotBlank()) {
            dash.addView(Ui.divider(this))
            dash.addView(fallbackItem(cfg, d.fallbackHealth))
        }
        dash.addView(thresholdLegend(cfg))

        dash.addView(monitoringControl())

        dash.addView(Ui.section(this, "Moje repeatery", "RPT1–RPT${Config.RPT_SLOTS}"))
        dash.addView(rptSection(d))

        val fetchedAt = d.observer?.fetchedAtMs ?: now
        dash.addView(Ui.text(
            this,
            "Pobrano ${timeOf(fetchedAt)} · następne za ${TimeFormat.countdown(ObserverRefresh.remainingSeconds(this, wallClockNow), cfg.monitoringEnabled)}",
            11f, Ui.MUTED
        ).apply { setPadding(dp(16), dp(12), dp(16), dp(16)) })
    }

    private fun observerItem(d: WidgetData): View {
        val box = Ui.item(this)
        val obs = d.observer
        box.addView(Ui.text(this, obs?.name ?: "—", 17f, Ui.TEXT, bold = true))
        box.addView(Ui.text(
            this,
            listOfNotNull(obs?.model, obs?.firmware?.let { "fw $it" }, d.observerId.take(4).uppercase()).joinToString(" · "),
            12f, Ui.SUBTEXT
        ).apply { setPadding(0, dp(3), 0, 0) })
        obs?.let { box.addView(Ui.text(this, "Dane z: ${Config.displayHost(it.host)}", 11f, Ui.MUTED)) }
        return box
    }

    /**
     * Wiersz brokera: stan serwera (GOOD/BAD/UNKNOWN) + to, co Observer do niego wysłał
     * (pakiety/h, razem, wiek ostatniego pakietu) – te same wartości co na widgecie.
     */
    private fun brokerItem(
        title: String, host: String, r: FetchResult?, now: Long, health: Health, frozenAgeMs: Long?, cfg: Config
    ): View {
        val box = Ui.item(this)
        val head = SpannableStringBuilder().apply {
            append(title)
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(healthColor(health)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(1.2f), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val s = length
            append("  ${Config.displayHost(host)}")
            setSpan(ForegroundColorSpan(Ui.MUTED), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        box.addView(Ui.row(this, Ui.text(this, head, 13f, Ui.TEXT).apply {
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
        }, healthLabel(health)))

        val t = WidgetRenderer.brokerTexts(r, compact = false, frozenAgeMs = frozenAgeMs, orangeMin = cfg.orangeMin, redMin = cfg.redMin)
        val (lastText, lastColor) = when {
            r is FetchResult.Ok && r.snapshot.lastPacketMs != null -> {
                val age = r.snapshot.lastPacketAgeMs(now) ?: 0L
                TimeFormat.packetAge(age) to levelColor(Status.level(age, cfg.orangeMin, cfg.redMin))
            }
            t.lastLevel != null -> (t.last ?: "—") to levelColor(t.lastLevel)
            else -> (t.last ?: "—") to Ui.MUTED
        }
        val valueColor = if (r is FetchResult.Ok) Ui.TEXT else Ui.MUTED
        box.addView(Ui.kvRow(
            this,
            Ui.kv(this, "Pakiety / h", t.hour, valueColor),
            Ui.kv(this, "Razem", t.total, valueColor),
            Ui.kv(this, "Ostatni", lastText, lastColor)
        ))
        if (r is FetchResult.Fail) {
            box.addView(Ui.text(this, "API niedostępne: ${r.reason}", 11f, Ui.RED).apply { setPadding(0, dp(6), 0, 0) })
        }
        if (health == Health.BAD) {
            box.addView(Ui.text(this, "Powiadomienie po ${cfg.alertDelayMin} min · ponownie co ${cfg.alertCooldownMin} min", 11f, Ui.MUTED)
                .apply { setPadding(0, dp(4), 0, 0) })
        }
        return box
    }

    private fun fallbackItem(cfg: Config, health: Health): View {
        val box = Ui.item(this)
        val head = SpannableStringBuilder().apply {
            append("Zapasowy")
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("  ${Config.displayHost(cfg.fallbackHost)}")
        }
        box.addView(Ui.row(this, Ui.text(this, head, 13f, Ui.MUTED).apply {
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
        }, healthLabel(health)))
        box.addView(Ui.text(this, "Używany, gdy oba brokery milczą", 11f, Ui.MUTED))
        return box
    }

    /** Legenda kolorów wieku ostatniego pakietu (progi z bieżących ustawień). */
    private fun thresholdLegend(cfg: Config): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        fun entry(color: Int, label: String) {
            row.addView(Ui.dot(this, color))
            row.addView(Ui.text(this, label, 11f, Ui.MUTED).apply { setPadding(0, 0, dp(12), 0) })
        }
        entry(Ui.GREEN, "< ${cfg.orangeMin} min")
        entry(Ui.ORANGE, "${cfg.orangeMin}–${cfg.redMin} min")
        entry(Ui.RED, "> ${cfg.redMin} min")
        return row
    }

    private fun healthLabel(h: Health) = Ui.text(this, when (h) {
        Health.GOOD -> "● Good"
        Health.BAD -> "● Bad"
        Health.UNKNOWN -> "● Unknown"
    }, 12f, healthColor(h), bold = true)

    private fun healthColor(h: Health) = when (h) {
        Health.GOOD -> Ui.GREEN
        Health.BAD -> Ui.RED
        Health.UNKNOWN -> Ui.MUTED
    }

    private fun levelColor(l: Level?) = when (l) {
        Level.GREEN -> Ui.GREEN
        Level.ORANGE -> Ui.ORANGE
        Level.RED -> Ui.RED
        else -> Ui.MUTED
    }

    /** Kolor statusu z [WidgetRenderer] (paleta widgetu) → odpowiednik z palety aplikacji. */
    private fun appColor(widgetColor: Int) = when (widgetColor) {
        WidgetRenderer.GREEN -> Ui.GREEN
        WidgetRenderer.ORANGE -> Ui.ORANGE
        WidgetRenderer.RED -> Ui.RED
        else -> Ui.MUTED
    }

    private fun infoItem(text: String, color: Int): View = Ui.item(this).apply {
        addView(Ui.text(this@MainActivity, text, 13f, color))
    }

    /**
     * „Mapują teraz”: osobny wiersz na każdego mapującego przy Observerze (niebieski) i przy
     * moich RPT (pomarańczowy), bez nazw repeaterów. Kolor nicku = kolor strzałki tego mapera
     * na widgecie. Sekcji nie ma, gdy nikt nie mapuje. Dotknięcie → karta Log.
     */
    private fun mappingNowSection(d: WidgetData, cfg: Config, nowMs: Long) {
        val rows = mutableListOf<View>()
        fun addSource(tag: String, rpt: Boolean, m: MappingState?) {
            if (m?.active != true) return
            val latest = latestPerMapper(m)
            if (latest.isEmpty()) {
                rows.add(mappingNowRow(tag, rpt, m.mapperNicks.joinToString(", ").ifBlank { "?" }, Ui.TEXT, null, m, nowMs))
            } else latest.forEachIndexed { i, p ->
                val nick = MapperMonitor.nick(p.userId) ?: m.mapperNicks.getOrNull(i) ?: "user ${p.userId}"
                rows.add(mappingNowRow(tag, rpt, nick, WidgetRenderer.mapperArrowColor(i), p, m, nowMs))
            }
        }
        addSource("OBSERVER", false, d.mapping)
        for (r in d.rptMappings.sortedBy { it.slot }) {
            if (cfg.rptId(r.slot).isBlank()) continue
            addSource(r.label, true, r.state)
        }
        if (rows.isEmpty()) return
        dash.addView(Ui.section(this, "Mapują teraz", "${rows.size} · dotknij → Log", color = Ui.BLUE))
        rows.forEachIndexed { i, v ->
            if (i > 0) dash.addView(Ui.divider(this))
            dash.addView(v)
        }
    }

    private fun mappingNowRow(
        tag: String, rpt: Boolean, nick: String, nickColor: Int, p: MapperPing?, m: MappingState, nowMs: Long
    ): View {
        val box = Ui.item(this).apply {
            isClickable = true
            setOnClickListener { selectTab(TAB_LOG) }
        }
        val tagColor = if (rpt) Ui.ORANGE else Ui.BLUE
        val title = SpannableStringBuilder().apply {
            append("● ")
            // Pulsująca kropka: jak ikona mapowania na widgecie.
            setSpan(ForegroundColorSpan(if (WidgetRenderer.mappingIconOn(nowMs)) tagColor else dim(tagColor)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            var s = length
            append(tag)
            setSpan(ForegroundColorSpan(tagColor), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("  ")
            s = length
            append(nick)
            setSpan(ForegroundColorSpan(nickColor), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val lastTs = (if (p != null) p.tsMs else m.pings.lastOrNull()?.tsMs)
        box.addView(Ui.row(this, Ui.text(this, title, 14f, Ui.TEXT), lastTs?.let { Ui.text(this, ago(it, nowMs), 11f, Ui.MUTED) }))

        val details = SpannableStringBuilder()
        if (p != null && p.lat != null && p.lon != null && m.observerLat != null && m.observerLon != null) {
            val bearing = GeoMath.bearingDeg(m.observerLat, m.observerLon, p.lat, p.lon)
            val dist = GeoMath.distanceKm(m.observerLat, m.observerLon, p.lat, p.lon)
            details.append("➤")
            details.setSpan(ForegroundColorSpan(nickColor), 0, details.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            details.append(" ${GeoMath.compassLabel(bearing)} · ${GeoMath.km(dist)} · ")
        }
        val mine = if (p != null) m.pings.filter { it.userId == p.userId } else m.pings
        details.append(pingCount(mine.size))
        mine.lastOrNull()?.observerSnrDb?.let { details.append(" · SNR ${fmtPl(it)} dB") }
        box.addView(Ui.text(this, details, 12f, Ui.MUTED).apply { setPadding(0, dp(2), 0, 0) })
        return box
    }

    /** Ostatni ping każdego mapującego z GPS, wg userId – kolejność = kolor strzałki na widgecie. */
    private fun latestPerMapper(m: MappingState): List<MapperPing> =
        m.pings
            .filter { it.lat != null && it.lon != null }
            .groupBy { it.userId }
            .map { (_, v) -> v.last() }
            .sortedBy { it.userId }

    private fun monitoringControl(): View {
        val enabled = Config(this).monitoringEnabled
        return Ui.button(this, if (enabled) "Zatrzymaj monitoring" else "Uruchom monitoring", primary = !enabled) {
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
            rerender()
        }
    }

    /**
     * Sekcja „MOJE REPEATERY”: do 5 kluczy repeaterów (RPT1–RPT5).
     * Klucz wpisuje się w okienku (panel przerysowuje się co sekundę, więc pole tekstowe
     * na stałe by znikało). Pod kluczem: czy repeater jest w bazie mappera i jego nazwa.
     */
    private fun rptSection(d: WidgetData): View {
        val cfg = Config(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ids = cfg.rptIds
        var first = true
        for ((slot, id) in ids) {
            if (!first) box.addView(Ui.divider(this))
            first = false
            val item = Ui.item(this)
            val info = MapperMonitor.rptInfo(MapperApi.shortId(id))
            val mapped = d.rptMappings.firstOrNull { it.slot == slot }?.state?.active == true
            item.addView(Ui.row(
                this,
                Ui.text(this, "RPT$slot" + (info?.name?.let { " · $it" } ?: ""), 14f, Ui.TEXT, bold = true).apply {
                    isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                },
                if (mapped) Ui.text(this, "● mapuje", 11f, Ui.ORANGE, bold = true) else null
            ))
            val keyRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            keyRow.addView(TextView(this).apply {
                text = if (id.length > 14) "${id.take(8)}…${id.takeLast(4)}" else id
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextColor(Ui.SUBTEXT)
                setPadding(0, dp(4), dp(4), dp(4))
                contentDescription = "Zmień klucz RPT$slot"
                setOnClickListener { editRptDialog(slot) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            keyRow.addView(copyButton(id, "RPT$slot"))
            keyRow.addView(TextView(this).apply {
                text = "✕"
                textSize = 17f
                setTextColor(Ui.MUTED)
                setPadding(dp(12), dp(2), dp(4), dp(2))
                contentDescription = "Usuń RPT$slot"
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Usunąć RPT$slot?")
                        .setMessage("Klucz ${id.take(8)}… zniknie z widgetu i powiadomień.")
                        .setPositiveButton("Usuń") { _, _ ->
                            Config(this@MainActivity).setRptId(slot, "")
                            rerender()
                        }
                        .setNegativeButton("Anuluj", null)
                        .show()
                }
            })
            item.addView(keyRow)
            val (status, color) = when {
                info == null -> "sprawdzam w bazie mappera…" to Ui.MUTED
                info.found -> "✓ w bazie mappera: „${info.name ?: "bez nazwy"}”" to Ui.GREEN
                info.name != null -> "„${info.name}” bez pozycji w bazie mappera – bez strzałki i odległości" to Ui.ORANGE
                else -> "✕ brak w bazie mappera – bez nazwy, strzałki i odległości" to Ui.ORANGE
            }
            item.addView(Ui.text(this, status, 11f, color))
            box.addView(item)
        }
        val free = (1..Config.RPT_SLOTS).firstOrNull { it !in ids }
        if (free != null) {
            if (!first) box.addView(Ui.divider(this))
            val item = Ui.item(this).apply {
                isClickable = true
                setOnClickListener { editRptDialog(free) }
            }
            item.addView(Ui.row(
                this,
                Ui.text(this, "RPT$free · wolny slot", 14f, Ui.MUTED),
                Ui.text(this, "+ Dodaj", 14f, Ui.GREEN, bold = true)
            ))
            item.addView(Ui.text(this, "Mapowanie przy RPT1–RPT${Config.RPT_SLOTS} (pomarańczowe) na widgecie i w powiadomieniach.", 11f, Ui.MUTED))
            box.addView(item)
        }
        return box
    }

    /** Okienko wpisania/zmiany klucza RPTn (tylko repeater; Observer ma własne pole w Ustawieniach). */
    private fun editRptDialog(slot: Int) {
        val cfg = Config(this)
        val input = android.widget.EditText(this).apply {
            setText(cfg.rptId(slot))
            hint = "klucz repeatera (64 znaki hex)"
            typeface = Typeface.MONOSPACE
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
                rerender()
                // Od razu sprawdzamy repeater w bazie mappera (nazwa + pozycja do strzałki).
                thread {
                    MapperMonitor.lookupRpt(applicationContext, id, 6_000)
                    runOnUiThread { if (!isFinishing) rerender() }
                }
            }
        }
        dialog.show()
    }

    // ───────────────────────────── LOG ─────────────────────────────

    private fun renderLog(d: WidgetData) {
        logDash.removeAllViews()
        val cfg = Config(this)
        val wallClockNow = System.currentTimeMillis()
        logDash.addView(Ui.topBar(this, "LOG", "Kto i kiedy mapował · najnowsze na górze", keepSpinner(cfg)))
        if (!d.configured) {
            logDash.addView(infoItem("Wejdź w Ustawienia i wpisz pełny hash Observera (64 znaki).", Ui.ORANGE))
            return
        }
        val cards = mappingCards(d, cfg, wallClockNow)
        if (cards.isEmpty()) {
            logDash.addView(infoItem("Brak mapowań w wybranym zakresie. Zakres przechowywania zmienisz listą u góry.", Ui.MUTED))
            return
        }
        var lastDay: LocalDate? = null
        var firstCard = true
        for ((ts, card) in cards.sortedByDescending { it.first }) {
            val day = if (ts > 0L) Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate() else null
            if (firstCard || day != lastDay) {
                logDash.addView(Ui.section(this, dayLabel(day)))
                lastDay = day
                firstCard = false
            } else logDash.addView(Ui.divider(this))
            logDash.addView(card)
        }
        logDash.addView(Ui.spacer(this, 16))
    }

    private fun dayLabel(day: LocalDate?): String {
        if (day == null) return "Brak danych"
        val today = LocalDate.now()
        return when (day) {
            today -> "Dziś"
            today.minusDays(1) -> "Wczoraj"
            else -> day.format(DateTimeFormatter.ofPattern("dd.MM"))
        }
    }

    /** Karty mapowań Observera i RPT w zakresie przechowywania: (czas ostatniego pinga, karta). */
    private fun mappingCards(d: WidgetData, cfg: Config, wallClockNow: Long): List<Pair<Long, View>> {
        val keepMs = cfg.mapperKeepEffectiveMs
        val logCards = mutableListOf<Pair<Long, View>>()
        val mapping = d.mapping
        val mappingRecent = mapping?.pings?.lastOrNull()?.let { wallClockNow - it.tsMs < keepMs } == true
        if (showMapping || (mapping != null && (mapping.active || mappingRecent))) {
            logCards.add((mapping?.pings?.lastOrNull()?.tsMs ?: 0L) to mappingCard(
                mapping, wallClockNow, tag = "OBSERVER", rpt = false,
                keyShort = d.observerId.take(4).uppercase(), fullKey = cfg.observerId,
                keyLabel = "Observera", snrLabel = "SNR u Obs"
            ))
        }
        for (r in d.rptMappings.sortedBy { it.slot }) {
            val st = r.state
            val recent = st.pings.lastOrNull()?.let { wallClockNow - it.tsMs < keepMs } == true
            if (!st.active && !recent) continue
            if (cfg.rptId(r.slot).isBlank()) continue
            logCards.add((st.pings.lastOrNull()?.tsMs ?: 0L) to mappingCard(
                st, wallClockNow, tag = r.label, rpt = true,
                keyShort = r.shortId.take(4).uppercase(), fullKey = null,
                keyLabel = r.label, snrLabel = "SNR u RPT"
            ))
        }
        return logCards
    }

    /** Lista zakresu przechowywania śladu mapowania (w pasku tytułu Log). */
    private fun keepSpinner(cfg: Config): View = object : Spinner(this) {
        override fun performClick(): Boolean {
            logSpinnerOpen = true
            return super.performClick()
        }
    }.apply {
        adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, keepOptions.map { it.second }) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    text = "${keepOptions[position].second} ▾"
                    textSize = 13f
                    setTextColor(Ui.SUBTEXT)
                }
        }.also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        background = Ui.rounded(this@MainActivity, 0x00000000, 16, Ui.OUTLINE)
        setPadding(dp(12), dp(4), dp(12), dp(4))
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

    /**
     * Karta mapowania w Log: źródło (OBSERVER / RPTn, bez nazwy repeatera), „● teraz” przy trwającej
     * sesji, każdy mapujący w osobnej linii z kierunkiem i odległością (nick w kolorze strzałki tego
     * mapera na widgecie), na dole liczba pingów, ostatni ping i SNR.
     */
    private fun mappingCard(
        m: MappingState?, nowMs: Long, tag: String, rpt: Boolean,
        keyShort: String, fullKey: String?, keyLabel: String, snrLabel: String
    ): View {
        val box = Ui.item(this)
        val active = m?.active == true
        val tagColor = if (rpt) Ui.ORANGE else Ui.BLUE
        val right = when {
            active -> Ui.text(this, "● teraz", 12f, tagColor, bold = true).apply {
                alpha = if (WidgetRenderer.mappingIconOn(nowMs)) 1f else 0.45f
            }
            else -> m?.pings?.lastOrNull()?.let { Ui.text(this, TimeFormat.hm(it.tsMs), 12f, Ui.MUTED) }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(Ui.text(this, tag, 13f, tagColor, bold = true).apply { letterSpacing = 0.05f })
        head.addView(Ui.text(this, "  $keyShort…", 11f, Ui.MUTED).apply { typeface = Typeface.MONOSPACE })
        if (fullKey != null) head.addView(copyButton(fullKey, keyLabel))
        box.addView(Ui.row(this, head, right))

        if (m == null) {
            box.addView(Ui.text(this, "Brak danych mapowania (mapper meshcoresouth.pl nie odpowiada).", 13f, Ui.MUTED))
            return box
        }
        val label = if (active) "Mapują: " else "Mapował: "
        val latest = latestPerMapper(m)
        if (latest.isEmpty() && m.mapperNicks.isNotEmpty()) {
            box.addView(Ui.text(this, label + m.mapperNicks.joinToString(", "), 14f, Ui.TEXT).apply { setPadding(0, dp(4), 0, 0) })
        }
        latest.forEachIndexed { i, p ->
            val sb = SpannableStringBuilder()
            if (i == 0) {
                sb.append(label)
                sb.setSpan(ForegroundColorSpan(Ui.MUTED), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val nick = MapperMonitor.nick(p.userId) ?: m.mapperNicks.getOrNull(i) ?: "user ${p.userId}"
            val nStart = sb.length
            sb.append(nick)
            sb.setSpan(ForegroundColorSpan(WidgetRenderer.mapperArrowColor(i)), nStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), nStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (m.observerLat != null && m.observerLon != null) {
                val bearing = GeoMath.bearingDeg(m.observerLat, m.observerLon, p.lat!!, p.lon!!)
                val dist = GeoMath.distanceKm(m.observerLat, m.observerLon, p.lat, p.lon)
                sb.append("  ${GeoMath.compassLabel(bearing)} · ${GeoMath.km(dist)}")
            }
            box.addView(Ui.text(this, sb, 14f, Ui.TEXT).apply { setPadding(0, dp(if (i == 0) 4 else 1), 0, 0) })
        }
        val pings = m.pings
        if (pings.isNotEmpty()) {
            val last = pings.last()
            val parts = mutableListOf(
                pingCount(pings.size),
                "ostatni ${timeOf(last.tsMs)} · ${TimeFormat.dayMonth(last.tsMs)}"
            )
            last.observerSnrDb?.let { parts.add("$snrLabel ${fmtPl(it)} dB") }
            box.addView(Ui.text(this, parts.joinToString(" · "), 12f, Ui.MUTED).apply { setPadding(0, dp(3), 0, 0) })
        }
        return box
    }

    private fun copyButton(fullKey: String, label: String) = android.widget.ImageView(this).apply {
        setImageResource(R.drawable.ic_copy)
        setColorFilter(Ui.MUTED)
        setPadding(dp(8), dp(4), dp(4), dp(4))
        contentDescription = "Kopiuj pełny klucz $label"
        setOnClickListener {
            val key = CopyKeyReceiver.fullKey(fullKey) ?: fullKey.takeIf { it.isNotBlank() } ?: return@setOnClickListener
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Klucz $label", key))
            android.widget.Toast.makeText(this@MainActivity, "Skopiowano klucz $label", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ───────────────────────────── pomocnicze ─────────────────────────────

    private fun pingCount(n: Int): String {
        val word = when {
            n == 1 -> "ping"
            n % 10 in 2..4 && n % 100 !in 12..14 -> "pingi"
            else -> "pingów"
        }
        return "$n $word"
    }

    private fun ago(tsMs: Long, nowMs: Long): String {
        val min = (nowMs - tsMs).coerceAtLeast(0L) / 60_000L
        return if (min < 1) "teraz" else "$min min"
    }

    private fun dim(color: Int): Int = (color and 0x00FFFFFF) or 0x73000000

    private fun timeOf(ms: Long): String =
        DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    private fun fmtPl(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v)

    /** Lista rozwinięta → ticker nie przerysowuje panelu (inaczej lista znika przed kliknięciem). */
    private var logSpinnerOpen = false

    /** Opcje retencji śladu mapowania w LOG: (wartość ms, −1 = na stałe) → etykieta. */
    private val keepOptions = listOf(
        24 * 3600_000L to "24 h",
        48 * 3600_000L to "48 h",
        3 * 24 * 3600_000L to "3 dni",
        10 * 24 * 3600_000L to "10 dni",
        30 * 24 * 3600_000L to "30 dni",
        -1L to "na stałe"
    )

    private fun matchParent() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    private fun dp(v: Int) = Ui.dp(this, v)

    companion object {
        const val EXTRA_SHOW_MAPPING = "pl.meshcore.observerwidget.SHOW_MAPPING"
        private const val STATE_TAB = "tab"
        const val TAB_OBSERVER = 0
        const val TAB_MAP = 1
        const val TAB_LOG = 2
        const val TAB_SETTINGS = 3

        /** Ikona mapowania na widgecie: otwiera aplikację na karcie Log z kartą mapowania. */
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
