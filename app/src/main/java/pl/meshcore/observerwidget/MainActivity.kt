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
 * Aplikacja z dolnym paskiem (styl M2): Observer · Mapa · Log · Statystyki · Ustawienia.
 * Observer = stan Observera, brokery MQTT, „Mapują teraz”, moje repeatery; Log = wpisy mapowań
 * (osobno dla każdego mapującego); Statystyki = [RepeaterStats] + rejestr [Outages]; Mapa = [MapScreen];
 * Ustawienia = [SettingsScreen].
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
    private lateinit var outScroll: ScrollView
    private lateinit var outDash: LinearLayout
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
        outDash = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outScroll = ScrollView(this).apply { addView(outDash) }
        content.addView(outScroll, matchParent())
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        nav = BottomNav(this) { selectTab(it) }
        root.addView(nav)
        setContentView(root)
        root.applySystemBarsPadding()

        val openSettings = intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true
        selectTab(when {
            openSettings -> TAB_SETTINGS
            showMapping -> TAB_LOG
            else -> savedInstanceState?.getInt(STATE_TAB, TAB_OBSERVER) ?: TAB_OBSERVER
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_MAPPING, false)) {
            showMapping = true
            selectTab(TAB_LOG)
            logScroll.scrollTo(0, 0)
        } else if (intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)) {
            selectTab(TAB_SETTINGS)
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
        refreshCompanionKeys()
    }

    /** Lista kluczy companionów (klucz mapującego po nicku) – w tle, co 6 h. */
    private fun refreshCompanionKeys() {
        thread {
            if (CompanionKeys.refreshIfStale(applicationContext)) runOnUiThread { if (!isFinishing) rerender() }
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
                TAB_STATS -> outScroll.smoothScrollTo(0, 0)
                TAB_SETTINGS -> settingsScroll?.smoothScrollTo(0, 0)
            }
            return
        }
        tab = t
        nav.select(t)
        // Trwający przestój (czerwona kropka na pasku) → od razu widok Przestoje.
        if (t == TAB_STATS && Outages.anyOngoing(this, System.currentTimeMillis())) statsMode = 1
        hideKeyboard()
        obsScroll.visibility = if (t == TAB_OBSERVER) View.VISIBLE else View.GONE
        logScroll.visibility = if (t == TAB_LOG) View.VISIBLE else View.GONE
        outScroll.visibility = if (t == TAB_STATS) View.VISIBLE else View.GONE
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
        nav.setBadge(TAB_STATS, Outages.anyOngoing(this, System.currentTimeMillis()))
        when (tab) {
            TAB_OBSERVER -> renderDashboard(d)
            TAB_LOG -> renderLog(d)
            TAB_STATS -> renderStats()
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
            if (cfg.monitoringEnabled) tr("Monitoring aktywny · co ${cfg.intervalSec} s", "Monitoring active · every ${cfg.intervalSec} s") else tr("Monitoring zatrzymany", "Monitoring stopped"),
            live?.let { Ui.text(this, it.first, 13f, appColor(it.second), bold = true).apply { letterSpacing = 0.05f } }
        ))
        if (!cfg.monitoringEnabled) {
            dash.addView(infoItem(tr("Monitoring zatrzymany. Dane nie są odświeżane, alarmy są wyłączone.", "Monitoring stopped. Data is not refreshed, alerts are off."), Ui.ORANGE))
        }
        if (!d.configured) {
            dash.addView(infoItem(tr("Wejdź w Ustawienia i wpisz pełny hash Observera (64 znaki).", "Go to Settings and enter the full Observer hash (64 characters)."), Ui.ORANGE))
            dash.addView(Ui.button(this, tr("Otwórz Ustawienia", "Open Settings"), primary = true) { selectTab(TAB_SETTINGS) })
            dash.addView(monitoringControl())
            return
        }

        mappingNowSection(d, cfg, wallClockNow)

        dash.addView(Ui.section(this, "Observer"))
        dash.addView(observerItem(d))

        val ok = listOf(d.mqtt1Health, d.mqtt2Health).count { it == Health.GOOD }
        dash.addView(Ui.section(this, tr("Brokery MQTT", "MQTT brokers"), "$ok / 2 OK", rightColor = when (ok) {
            2 -> Ui.GREEN
            1 -> Ui.ORANGE
            else -> Ui.RED
        }))
        dash.addView(refreshRow(cfg, wallClockNow))
        dash.addView(brokerItem("MQTT1", d.mqtt1Host, d.broker1, now, d.mqtt1Health, d.broker1FrozenAgeMs, cfg))
        dash.addView(Ui.divider(this))
        dash.addView(brokerItem("MQTT2", d.mqtt2Host, d.broker2, now, d.mqtt2Health, d.broker2FrozenAgeMs, cfg))
        if (cfg.fallbackHost.isNotBlank()) {
            dash.addView(Ui.divider(this))
            dash.addView(fallbackItem(cfg, d.fallbackHealth))
        }
        dash.addView(thresholdLegend(cfg))

        dash.addView(monitoringControl())

        dash.addView(Ui.section(this, tr("Moje repeatery", "My repeaters"), "RPT1–RPT${Config.RPT_SLOTS}"))
        dash.addView(rptSection(d))

        val fetchedAt = d.observer?.fetchedAtMs ?: now
        dash.addView(Ui.text(
            this,
            tr("Pobrano ${timeOf(fetchedAt)} · następne za ${TimeFormat.countdown(ObserverRefresh.remainingSeconds(this, wallClockNow), cfg.monitoringEnabled)}",
                "Fetched ${timeOf(fetchedAt)} · next in ${TimeFormat.countdown(ObserverRefresh.remainingSeconds(this, wallClockNow), cfg.monitoringEnabled)}"),
            11f, Ui.MUTED
        ).apply { setPadding(dp(16), dp(12), dp(16), dp(16)) })
    }

    private fun observerItem(d: WidgetData): View {
        val box = Ui.item(this)
        val obs = d.observer
        box.addView(Ui.text(this, obs?.name ?: "—", 17f, Ui.TEXT, bold = true))
        box.addView(keyRow(Config(this).observerId, tr("Observera", "Observer"), 13f))
        listOfNotNull(obs?.model, obs?.firmware?.let { "fw $it" }).takeIf { it.isNotEmpty() }?.let {
            box.addView(Ui.text(this, it.joinToString(" · "), 12f, Ui.SUBTEXT).apply { setPadding(0, dp(1), 0, 0) })
        }
        obs?.let { box.addView(Ui.text(this, tr("Dane z: ${Config.displayHost(it.host)}", "Data from: ${Config.displayHost(it.host)}"), 11f, Ui.MUTED)) }
        return box
    }

    /** Skrót klucza (6 znaków) + ikona kopiowania pełnego klucza; null → szare „brak klucza”. */
    private fun keyRow(fullKey: String?, label: String, size: Float = 12f): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val key = fullKey?.trim()?.takeIf { it.isNotEmpty() }
        if (key == null) {
            row.addView(Ui.text(this, tr("brak klucza", "no key"), size - 1f, Ui.MUTED).apply { setPadding(0, dp(2), 0, dp(2)) })
            return row
        }
        row.addView(Ui.text(this, "${key.take(6).uppercase()}…", size, Ui.SUBTEXT).apply { typeface = Typeface.MONOSPACE })
        row.addView(copyButton(key, label))
        return row
    }

    /** Odliczanie do następnego pobrania (jak na widgecie) + odświeżenie na żądanie. */
    private fun refreshRow(cfg: Config, nowMs: Long): View {
        val busyNow = ObserverRefresh.refreshInProgress
        val left = Ui.text(
            this,
            when {
                !cfg.monitoringEnabled -> tr("Odświeżanie zatrzymane", "Refreshing stopped")
                busyNow -> tr("Odświeżam…", "Refreshing…")
                else -> tr("Następne odświeżenie za ${TimeFormat.countdown(ObserverRefresh.remainingSeconds(this, nowMs), true)}",
                    "Next refresh in ${TimeFormat.countdown(ObserverRefresh.remainingSeconds(this, nowMs), true)}")
            },
            12f, Ui.SUBTEXT
        )
        val btn = Ui.text(this, tr("⟳ Odśwież teraz", "⟳ Refresh now"), 13f, if (cfg.monitoringEnabled && !busyNow) Ui.GREEN else Ui.MUTED, bold = true).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = Ui.rounded(this@MainActivity, 0x00000000, 16, Ui.OUTLINE)
            contentDescription = tr("Odśwież teraz", "Refresh now")
            setOnClickListener {
                if (!Config(this@MainActivity).monitoringEnabled) {
                    android.widget.Toast.makeText(this@MainActivity, tr("Monitoring zatrzymany – uruchom go, aby odświeżać", "Monitoring stopped – start it to refresh"), android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                ObserverRefresh.refreshAll(applicationContext)
                rerender()
            }
        }
        return Ui.row(this, left, btn).apply { setPadding(dp(16), dp(4), dp(16), dp(4)) }
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
            Ui.kv(this, tr("Pakiety / h", "Packets / h"), t.hour, valueColor),
            Ui.kv(this, tr("Razem", "Total"), t.total, valueColor),
            Ui.kv(this, tr("Ostatni", "Last"), lastText, lastColor)
        ))
        if (r is FetchResult.Fail) {
            box.addView(Ui.text(this, tr("API niedostępne: ${r.reason}", "API unavailable: ${r.reason}"), 11f, Ui.RED).apply { setPadding(0, dp(6), 0, 0) })
        }
        return box
    }

    private fun fallbackItem(cfg: Config, health: Health): View {
        val box = Ui.item(this)
        val head = SpannableStringBuilder().apply {
            append(tr("Zapasowy", "Fallback"))
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("  ${Config.displayHost(cfg.fallbackHost)}")
        }
        box.addView(Ui.row(this, Ui.text(this, head, 13f, Ui.MUTED).apply {
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
        }, healthLabel(health)))
        box.addView(Ui.text(this, tr("Używany, gdy oba brokery milczą", "Used when both brokers are silent"), 11f, Ui.MUTED))
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
        dash.addView(Ui.section(this, tr("Mapują teraz", "Mapping now"), tr("${rows.size} · dotknij → Log", "${rows.size} · tap → Log"), color = Ui.BLUE))
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
        val sessionPings = m.session.ifEmpty { m.pings }
        val mine = if (p != null) sessionPings.filter { it.userId == p.userId } else sessionPings
        details.append(pingCount(mine.size))
        mine.lastOrNull()?.observerSnrDb?.let { details.append(" · SNR ${fmtPl(it)} dB") }
        box.addView(Ui.text(this, details, 12f, Ui.MUTED).apply { setPadding(0, dp(2), 0, 0) })
        if (p != null) box.addView(keyRow(CompanionKeys.keyFor(this, nick), nick))
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
        return Ui.button(this, if (enabled) tr("Zatrzymaj monitoring", "Stop monitoring") else tr("Uruchom monitoring", "Start monitoring"), primary = !enabled) {
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
                if (mapped) Ui.text(this, tr("● mapuje", "● mapping"), 11f, Ui.ORANGE, bold = true) else null
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
                contentDescription = tr("Zmień klucz RPT$slot", "Change RPT$slot key")
                setOnClickListener { editRptDialog(slot) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            keyRow.addView(copyButton(id, "RPT$slot"))
            keyRow.addView(TextView(this).apply {
                text = "✕"
                textSize = 17f
                setTextColor(Ui.MUTED)
                setPadding(dp(12), dp(2), dp(4), dp(2))
                contentDescription = tr("Usuń RPT$slot", "Remove RPT$slot")
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(tr("Usunąć RPT$slot?", "Remove RPT$slot?"))
                        .setMessage(tr("Klucz ${id.take(8)}… zniknie z widgetu i powiadomień.", "Key ${id.take(8)}… will be removed from the widget and notifications."))
                        .setPositiveButton(tr("Usuń", "Remove")) { _, _ ->
                            Config(this@MainActivity).setRptId(slot, "")
                            rerender()
                        }
                        .setNegativeButton(tr("Anuluj", "Cancel"), null)
                        .show()
                }
            })
            item.addView(keyRow)
            val (status, color) = when {
                info == null -> tr("sprawdzam w bazie mappera…", "checking the mapper database…") to Ui.MUTED
                info.found -> tr("✓ w bazie mappera: „${info.name ?: "bez nazwy"}”", "✓ in mapper database: “${info.name ?: "unnamed"}”") to Ui.GREEN
                info.name != null -> tr("„${info.name}” bez pozycji w bazie mappera – bez strzałki i odległości", "“${info.name}” has no position in mapper database – no arrow or distance") to Ui.ORANGE
                else -> tr("✕ brak w bazie mappera – bez nazwy, strzałki i odległości", "✕ not in mapper database – no name, arrow or distance") to Ui.ORANGE
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
                Ui.text(this, tr("RPT$free · wolny slot", "RPT$free · free slot"), 14f, Ui.MUTED),
                Ui.text(this, tr("+ Dodaj", "+ Add"), 14f, Ui.GREEN, bold = true)
            ))
            item.addView(Ui.text(this, tr("Mapowanie przy RPT1–RPT${Config.RPT_SLOTS} (pomarańczowe) na widgecie i w powiadomieniach.", "Mapping at RPT1–RPT${Config.RPT_SLOTS} (orange) on the widget and in notifications."), 11f, Ui.MUTED))
            box.addView(item)
        }
        return box
    }

    /** Okienko wpisania/zmiany klucza RPTn (tylko repeater; Observer ma własne pole w Ustawieniach). */
    private fun editRptDialog(slot: Int) {
        val cfg = Config(this)
        val input = android.widget.EditText(this).apply {
            setText(cfg.rptId(slot))
            hint = tr("klucz repeatera (64 znaki hex)", "repeater key (64 hex characters)")
            typeface = Typeface.MONOSPACE
            textSize = 14f
            isSingleLine = true
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(tr("RPT$slot: klucz repeatera", "RPT$slot: repeater key"))
            .setView(wrap)
            .setPositiveButton(tr("Zapisz", "Save"), null)
            .setNegativeButton(tr("Anuluj", "Cancel"), null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = input.text.toString().filterNot { it.isWhitespace() }
                val short = MapperApi.shortId(id)
                val err = when {
                    !Config.isValidRptId(id) -> tr("Klucz: znaki 0-9 i A-F, od 16 do 64 znaków.", "Key: characters 0-9 and A-F, 16 to 64 characters.")
                    short == MapperApi.shortId(cfg.observerId) -> tr("To klucz Observera – wpisz klucz innego repeatera.", "This is the Observer key – enter another repeater's key.")
                    cfg.rptIds.any { (s, other) -> s != slot && MapperApi.shortId(other) == short } -> tr("Ten repeater jest już na liście.", "This repeater is already on the list.")
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
        logDash.addView(Ui.topBar(this, "LOG", tr("Kto i kiedy mapował · najnowsze na górze", "Who mapped and when · newest on top"), keepSpinner(cfg)))
        if (!d.configured) {
            logDash.addView(infoItem(tr("Wejdź w Ustawienia i wpisz pełny hash Observera (64 znaki).", "Go to Settings and enter the full Observer hash (64 characters)."), Ui.ORANGE))
            return
        }
        val cards = mappingCards(d, cfg, wallClockNow)
        if (cards.isEmpty()) {
            logDash.addView(infoItem(tr("Brak mapowań w wybranym zakresie. Zakres przechowywania zmienisz listą u góry.", "No mappings in the selected range. Change the retention range with the list at the top."), Ui.MUTED))
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
        if (day == null) return tr("Brak danych", "No data")
        val today = LocalDate.now()
        return when (day) {
            today -> tr("Dziś", "Today")
            today.minusDays(1) -> tr("Wczoraj", "Yesterday")
            else -> day.format(DateTimeFormatter.ofPattern("dd.MM"))
        }
    }

    /**
     * Wpisy mapowań Observera i RPT w zakresie przechowywania: (czas ostatniego pinga, wpis).
     * Każdy mapujący ma osobny wpis – dwóch mapujących przy jednym źródle = dwa wpisy.
     */
    private fun mappingCards(d: WidgetData, cfg: Config, wallClockNow: Long): List<Pair<Long, View>> {
        val keepMs = cfg.mapperKeepEffectiveMs
        val logCards = mutableListOf<Pair<Long, View>>()
        val obsSource = MappingSource("OBSERVER", rpt = false, srcKeyShort = d.observerId.take(4).uppercase(),
            srcFullKey = cfg.observerId, srcLabel = tr("Observera", "Observer"), snrLabel = tr("SNR u Obs", "SNR at Obs"))
        val mapping = d.mapping
        val obsEntries = mapping?.let { mapperEntries(it, obsSource, wallClockNow, keepMs) }.orEmpty()
        if (obsEntries.isEmpty() && showMapping) {
            logCards.add(0L to emptyMappingCard(obsSource, mapping == null))
        }
        logCards.addAll(obsEntries)
        for (r in d.rptMappings.sortedBy { it.slot }) {
            if (cfg.rptId(r.slot).isBlank()) continue
            val src = MappingSource(r.label, rpt = true, srcKeyShort = r.shortId.take(4).uppercase(),
                srcFullKey = null, srcLabel = r.label, snrLabel = tr("SNR u RPT", "SNR at RPT"))
            logCards.addAll(mapperEntries(r.state, src, wallClockNow, keepMs))
        }
        return logCards
    }

    private class MappingSource(
        val tag: String, val rpt: Boolean, val srcKeyShort: String, val srcFullKey: String?,
        val srcLabel: String, val snrLabel: String
    )

    /** Osobny wpis dla każdego mapującego przy danym źródle (pingi, czas i SNR liczone dla tej osoby). */
    private fun mapperEntries(m: MappingState, src: MappingSource, nowMs: Long, keepMs: Long): List<Pair<Long, View>> {
        val withGps = latestPerMapper(m)
        return m.pings.groupBy { it.userId }.mapNotNull { (userId, mine) ->
            val sorted = mine.sortedBy { it.tsMs }
            val last = sorted.last()
            val active = m.active && nowMs - last.tsMs < MapperApi.SESSION_GAP_MS
            if (!active && nowMs - last.tsMs >= keepMs) return@mapNotNull null
            val colorIndex = withGps.indexOfFirst { it.userId == userId }
            last.tsMs to mapperEntry(m, src, userId, sorted, colorIndex, active, nowMs)
        }
    }

    /** Nagłówek wpisu: źródło (OBSERVER / RPTn) + skrót jego klucza, po prawej „● teraz” albo godzina. */
    private fun sourceHeader(src: MappingSource, right: View?): View {
        val tagColor = if (src.rpt) Ui.ORANGE else Ui.BLUE
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(Ui.text(this, src.tag, 13f, tagColor, bold = true).apply { letterSpacing = 0.05f })
        head.addView(Ui.text(this, "  ${src.srcKeyShort}…", 11f, Ui.MUTED).apply { typeface = Typeface.MONOSPACE })
        if (src.srcFullKey != null) head.addView(copyButton(src.srcFullKey, src.srcLabel))
        return Ui.row(this, head, right)
    }

    private fun emptyMappingCard(src: MappingSource, noData: Boolean): View = Ui.item(this).apply {
        addView(sourceHeader(src, null))
        addView(Ui.text(
            this@MainActivity,
            if (noData) tr("Brak danych mapowania (API mapera ${MapperApi.host(Config(this@MainActivity).mapperApi)} nie odpowiada).",
                "No mapping data (mapper API ${MapperApi.host(Config(this@MainActivity).mapperApi)} not responding).")
            else tr("Brak mapowań w wybranym zakresie.", "No mappings in the selected range."),
            13f, Ui.MUTED
        ))
    }

    /**
     * Wpis jednego mapującego: nagłówek źródła, nick (kolor strzałki tego mapera na widgecie),
     * niżej kierunek i odległość, klucz mapującego (z list companionów, ściśle po nicku)
     * i statystyki z datą: liczba pingów, od–do, SNR ostatniego pinga.
     */
    private fun mapperEntry(
        m: MappingState, src: MappingSource, userId: Long, pings: List<MapperPing>,
        colorIndex: Int, active: Boolean, nowMs: Long
    ): View {
        val box = Ui.item(this)
        val tagColor = if (src.rpt) Ui.ORANGE else Ui.BLUE
        val last = pings.last()
        val right = if (active) Ui.text(this, tr("● teraz", "● now"), 12f, tagColor, bold = true).apply {
            alpha = if (WidgetRenderer.mappingIconOn(nowMs)) 1f else 0.45f
        } else Ui.text(this, TimeFormat.hm(last.tsMs), 12f, Ui.MUTED)
        box.addView(sourceHeader(src, right))

        val nickColor = if (colorIndex >= 0) WidgetRenderer.mapperArrowColor(colorIndex) else Ui.TEXT
        val nick = MapperMonitor.nick(userId) ?: "user $userId"
        val nickLine = SpannableStringBuilder().apply {
            append(if (active) tr("Mapuje: ", "Mapping: ") else tr("Mapował: ", "Mapped: "))
            setSpan(ForegroundColorSpan(Ui.MUTED), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val s = length
            append(nick)
            setSpan(ForegroundColorSpan(nickColor), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        box.addView(Ui.text(this, nickLine, 15f, Ui.TEXT).apply { setPadding(0, dp(4), 0, 0) })

        val pos = pings.lastOrNull { it.lat != null && it.lon != null }
        if (pos != null && m.observerLat != null && m.observerLon != null) {
            val bearing = GeoMath.bearingDeg(m.observerLat, m.observerLon, pos.lat!!, pos.lon!!)
            val dist = GeoMath.distanceKm(m.observerLat, m.observerLon, pos.lat, pos.lon)
            val dir = SpannableStringBuilder().apply {
                append("➤")
                setSpan(ForegroundColorSpan(nickColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(" ${GeoMath.compassLabel(bearing)} · ${GeoMath.km(dist)}")
            }
            box.addView(Ui.text(this, dir, 13f, Ui.SUBTEXT).apply { setPadding(0, dp(1), 0, 0) })
        }

        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(this@MainActivity, tr("klucz ", "key "), 12f, Ui.MUTED))
            addView(keyRow(CompanionKeys.keyFor(this@MainActivity, nick), nick))
        })

        val first = pings.first()
        val range = if (TimeFormat.dayMonth(first.tsMs) == TimeFormat.dayMonth(last.tsMs))
            "${TimeFormat.dayMonth(last.tsMs)} ${TimeFormat.hm(first.tsMs)}–${TimeFormat.hm(last.tsMs)}"
        else "${TimeFormat.dayMonth(first.tsMs)} ${TimeFormat.hm(first.tsMs)} – ${TimeFormat.dayMonth(last.tsMs)} ${TimeFormat.hm(last.tsMs)}"
        val parts = mutableListOf(pingCount(pings.size), range)
        last.observerSnrDb?.let { parts.add("${src.snrLabel} ${fmtPl(it)} dB") }
        box.addView(Ui.text(this, parts.joinToString(" · "), 12f, Ui.MUTED).apply { setPadding(0, dp(2), 0, 0) })
        return box
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

    private fun copyButton(fullKey: String, label: String) = android.widget.ImageView(this).apply {
        setImageResource(R.drawable.ic_copy)
        setColorFilter(Ui.MUTED)
        setPadding(dp(8), dp(4), dp(4), dp(4))
        contentDescription = tr("Kopiuj pełny klucz $label", "Copy full $label key")
        setOnClickListener {
            val key = CopyKeyReceiver.fullKey(fullKey) ?: fullKey.takeIf { it.isNotBlank() } ?: return@setOnClickListener
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(tr("Klucz $label", "$label key"), key))
            android.widget.Toast.makeText(this@MainActivity, tr("Skopiowano klucz $label", "Copied $label key"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ───────────────────────────── STATYSTYKI ─────────────────────────────

    /** Statystyki: 0 = Repeatery (z bazy mapera), 1 = Przestoje (rejestr [Outages]). */
    private var statsMode = 0
    /** Indeks okresu w [STATS_PERIODS] — wspólny dla obu widoków. */
    private var statsPeriod = 1
    /** Wybrany węzeł: 0 = Observer, 1–5 = slot RPT. */
    private var statsNode = 0

    /** Baza punktów mapera w pamięci: pobierana raz, potem tylko dociągane nowe punkty. */
    private var statsRaw: MapperHistory.Raw? = null
    private var statsReps: Map<String, MapperHistory.Repeater> = emptyMap()
    private var statsLoadedAt = 0L
    private var statsLoading = false
    private var statsError = false
    /** Ostatni wynik i jego klucz (węzeł, okres, wersja danych) — bez liczenia co sekundę. */
    private var statsKey: Any? = null
    private var statsResult: RepeaterStats.Result? = null

    private val statsPeriods get() = listOf(
        24 * 3600_000L to "24 h",
        7 * 24 * 3600_000L to tr("7 dni", "7 days"),
        30 * 24 * 3600_000L to tr("30 dni", "30 days"),
        0L to tr("Całość", "All")
    )

    /** Pobranie bazy mapera w tle: pełne przy pierwszym razie, potem tylko punkty nowsze od ostatniego ID. */
    private fun loadStatsData(force: Boolean = false) {
        if (statsLoading) return
        val now = System.currentTimeMillis()
        if (!force && statsRaw != null && now - statsLoadedAt < STATS_RELOAD_MS) return
        statsLoading = true
        statsError = false
        val old = statsRaw
        thread(name = "repeater-stats") {
            val timeout = 15_000
            MapperApi.configure(applicationContext)
            val reps = MapperHistory.repeaters(timeout)
            val fresh = MapperHistory.fetchPoints(timeout, sinceId = old?.lastId ?: 0L)
            runOnUiThread {
                statsLoading = false
                if (fresh == null) statsError = true
                else {
                    statsRaw = if (old == null) fresh else old + fresh
                    if (reps.isNotEmpty()) statsReps = reps
                    statsLoadedAt = System.currentTimeMillis()
                    statsKey = null
                }
                if (!isFinishing && tab == TAB_STATS) rerender()
            }
        }
    }

    private fun renderStats() {
        outDash.removeAllViews()
        val now = System.currentTimeMillis()
        val outages = statsMode == 1
        val right: View = if (outages) {
            val list = Outages.all(this, now)
            Ui.text(this, tr("Wyczyść", "Clear"), 13f, if (list.isEmpty()) Ui.MUTED else Ui.RED, bold = true).apply {
                setPadding(dp(12), dp(4), dp(12), dp(4))
                background = Ui.rounded(this@MainActivity, 0x00000000, 16, Ui.OUTLINE)
                contentDescription = tr("Wyczyść przestoje", "Clear outages")
                setOnClickListener {
                    if (list.isEmpty()) return@setOnClickListener
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(tr("Wyczyścić przestoje?", "Clear outages?"))
                        .setMessage(tr("Usunie ${list.size} ${entriesWord(list.size)}. Trwający przestój będzie liczony od nowa.",
                            "This removes ${list.size} ${entriesWord(list.size)}. An ongoing outage will be counted from scratch."))
                        .setPositiveButton(tr("Wyczyść", "Clear")) { _, _ ->
                            Outages.clear(this@MainActivity, System.currentTimeMillis())
                            rerender()
                        }
                        .setNegativeButton(tr("Anuluj", "Cancel"), null)
                        .show()
                }
            }
        } else {
            val label = when {
                statsLoading -> "⟳ …"
                statsLoadedAt > 0L -> "⟳ ${TimeFormat.hm(statsLoadedAt)}"
                else -> "⟳"
            }
            Ui.text(this, label, 13f, Ui.MUTED, bold = true).apply {
                setPadding(dp(12), dp(4), dp(12), dp(4))
                background = Ui.rounded(this@MainActivity, 0x00000000, 16, Ui.OUTLINE)
                contentDescription = tr("Odśwież dane mappera", "Refresh mapper data")
                setOnClickListener { loadStatsData(force = true); rerender() }
            }
        }
        outDash.addView(Ui.topBar(
            this, tr("STATYSTYKI", "STATS"),
            if (outages) tr("Przerwy w pracy MQTT1, MQTT2 i Observera", "Downtime of MQTT1, MQTT2 and Observer")
            else tr("Dane z mappera · ${MapperApi.host(Config(this).mapperApi)}", "Mapper data · ${MapperApi.host(Config(this).mapperApi)}"),
            right
        ))
        outDash.addView(chipRow(listOf(tr("Repeatery", "Repeaters"), tr("Przestoje", "Outages")), statsMode, segmented = true) {
            statsMode = it
            outScroll.scrollTo(0, 0)
            rerender()
        })
        // Przestoje mają historię 30 dni: „Całość” wyszarzona, liczona jak 30 dni.
        val period = if (outages && statsPeriods[statsPeriod].first == 0L) 2 else statsPeriod
        outDash.addView(chipRow(statsPeriods.map { it.second }, period, disabled = if (outages) setOf(3) else emptySet()) {
            statsPeriod = it
            rerender()
        })
        if (outages) renderOutages(statsPeriods[period].first, statsPeriods[period].second, now)
        else renderRepeaterStats(now)
        outDash.addView(Ui.spacer(this, 16))
    }

    /** Rząd przełączników (chipy); [segmented] = dwa pola na całą szerokość. */
    private fun chipRow(labels: List<String>, selected: Int, segmented: Boolean = false, disabled: Set<Int> = emptySet(), onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(if (segmented) 10 else 4), dp(16), dp(4))
        }
        labels.forEachIndexed { i, label ->
            val on = i == selected
            val off = i in disabled
            val chip = Ui.text(this, label, if (segmented) 13f else 12f, when {
                on -> Ui.GREEN
                off -> Ui.OUTLINE
                else -> Ui.SUBTEXT
            }, bold = on).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                background = if (on) Ui.rounded(this@MainActivity, 0xFF1F3A2A.toInt(), if (segmented) 18 else 8, 0xFF2F5A3E.toInt())
                    else Ui.rounded(this@MainActivity, 0x00000000, if (segmented) 18 else 8, Ui.OUTLINE)
                setPadding(dp(8), dp(if (segmented) 8 else 5), dp(8), dp(if (segmented) 8 else 5))
                if (!off) {
                    isClickable = true
                    setOnClickListener { if (!on) onPick(i) }
                }
            }
            val lp = if (segmented) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (i > 0) lp.marginStart = dp(6)
            row.addView(chip, lp)
        }
        if (segmented) return row
        return android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    /** Statystyki repeatera jak na /mapper: podsumowanie, bilans łącza, top mapujący. */
    private fun renderRepeaterStats(now: Long) {
        val cfg = Config(this)
        val nodes = buildList {
            if (cfg.observerId.isNotBlank()) add(0 to cfg.observerId)
            cfg.rptIds.forEach { (slot, id) -> add(slot to id) }
        }
        if (nodes.isEmpty()) {
            outDash.addView(infoItem(tr("Wpisz w Ustawieniach klucz Observera albo swoich RPT, żeby zobaczyć ich statystyki.",
                "Enter the Observer key or your RPT keys in Settings to see their stats."), Ui.ORANGE))
            return
        }
        if (nodes.none { it.first == statsNode }) statsNode = nodes.first().first
        val nodeLabel = { slot: Int -> if (slot == 0) "OBSERVER" else "RPT$slot" }
        outDash.addView(chipRow(nodes.map { nodeLabel(it.first) }, nodes.indexOfFirst { it.first == statsNode }) {
            statsNode = nodes[it].first
            rerender()
        })
        loadStatsData()
        val raw = statsRaw
        if (raw == null) {
            outDash.addView(infoItem(
                if (statsError) tr("Nie udało się pobrać danych mappera. Dotknij ⟳, żeby spróbować ponownie.", "Could not download mapper data. Tap ⟳ to try again.")
                else tr("Wczytuję bazę mappera… (pierwsze pobranie trwa chwilę)", "Loading the mapper database… (the first download takes a moment)"),
                if (statsError) Ui.ORANGE else Ui.MUTED))
            return
        }
        val short = MapperApi.shortId(nodes.first { it.first == statsNode }.second)
        val rep = statsReps[short]
        val (periodMs, periodLabel) = statsPeriods[statsPeriod]
        val key = listOf(short, statsPeriod, statsLoadedAt, rep?.lat, rep?.lon)
        if (key != statsKey) {
            statsResult = RepeaterStats.compute(raw.points, raw.users, short, rep?.lat, rep?.lon,
                if (periodMs > 0L) now - periodMs else 0L)
            statsKey = key
        }
        val r = statsResult
        outDash.addView(Ui.section(this, tr("Podsumowanie", "Summary"), "${rep?.name ?: nodeLabel(statsNode)} · $periodLabel"))
        if (statsError) outDash.addView(infoItem(tr("Odświeżenie się nie udało – pokazuję wcześniejsze dane.", "Refresh failed – showing earlier data."), Ui.ORANGE))
        if (r == null) {
            outDash.addView(infoItem(tr("Brak odpowiedzi tego repeatera w bazie mappera w wybranym okresie.",
                "No responses from this repeater in the mapper database for the selected period."), Ui.MUTED))
            return
        }
        val silentDays = (now - r.lastMs) / (24 * 3600_000L)
        if (silentDays >= 2 && r.silentPings >= 5) {
            // Mapper widzi tylko przekazanie pingu radiem – węzeł może dalej odbierać i wysyłać do brokerów MQTT.
            val lastAt = DateTimeFormatter.ofPattern("dd.MM HH:mm").format(Instant.ofEpochMilli(r.lastMs).atZone(ZoneId.systemDefault()))
            outDash.addView(infoItem(tr("⚠ Nie odpowiada na pingi mapera od $lastAt ($silentDays dni), choć w jego zasięgu było od tego czasu ${pingCount(r.silentPings)}.",
                "⚠ No response to mapper pings since $lastAt ($silentDays days), although there were ${pingCount(r.silentPings)} in its range since then."), Ui.ORANGE))
        }
        val noPos = tr("brak pozycji RPT", "no RPT position")
        val tiles = listOf(
            statTile(tr("Pingi z odpowiedzią", "Pings answered"), "${r.pings}", tr("${r.measurements} ${measureWord(r.measurements)}", "${r.measurements} measurements"), Ui.GREEN),
            statTile(tr("Mapujący", "Mappers"), "${r.users}", tr("${r.days} ${if (r.days == 1) "dzień" else "dni"} z odpowiedzią", "${r.days} ${if (r.days == 1) "day" else "days"} with responses")),
            statTile(tr("Maks. zasięg", "Max range"), r.far?.let { GeoMath.km(it.km) } ?: "—", r.far?.let { f -> f.snr?.let { "SNR ${db(it)}" } ?: "" } ?: noPos),
            statTile(tr("Praktyczny zasięg", "Practical range"), r.practicalKm?.let { "~" + GeoMath.km(it) } ?: "—",
                if (r.practicalKm != null) tr("¾ odpowiedzi z SNR ≥ −5 dB", "¾ of responses with SNR ≥ −5 dB")
                else if (rep?.lat == null) noPos else tr("za mało danych", "not enough data"), Ui.GREEN),
            statTile(tr("Słyszymy RPT", "We hear RPT"), r.downAvg?.let { db(it) } ?: "—", r.downBest?.let { tr("najlepszy ${db(it)}", "best ${db(it)}") } ?: ""),
            statTile(tr("RPT słyszy nas", "RPT hears us"), r.upAvg?.let { db(it) } ?: "—", r.upBest?.let { tr("najlepszy ${db(it)}", "best ${db(it)}") } ?: ""),
            statTile(tr("RSSI średni", "Avg RSSI"), r.rssiAvg?.let { "${Math.round(it)} dBm" } ?: "—", ""),
            statTile(tr("Pokrycie", "Coverage"), "${num(r.coverKm2)} km²", tr("tylko on: ${num(r.onlyKm2)} km²", "only it: ${num(r.onlyKm2)} km²"))
        )
        for (i in tiles.indices step 2) {
            outDash.addView(Ui.kvRow(this, tiles[i], tiles[i + 1]).apply { setPadding(dp(16), dp(6), dp(16), 0) })
        }

        r.balanceDiff?.let { diff ->
            outDash.addView(Ui.section(this, tr("Bilans łącza", "Link balance")))
            outDash.addView(Ui.kvRow(this,
                Ui.kv(this, tr("my → RPT", "us → RPT"), db(r.upAvg!!), Ui.BLUE),
                Ui.kv(this, tr("RPT → my", "RPT → us"), db(r.downAvg!!), Ui.GREEN),
                Ui.kv(this, tr("różnica", "difference"), db(diff), if (kotlin.math.abs(diff) <= RepeaterStats.BALANCED_DB) Ui.GREEN else Ui.ORANGE)
            ).apply { setPadding(dp(16), 0, dp(16), 0) })
            outDash.addView(Ui.text(this, when {
                kotlin.math.abs(diff) <= RepeaterStats.BALANCED_DB -> tr("Łącze zrównoważone – repeater słyszy nas podobnie jak my jego.", "Balanced link – the repeater hears us about as well as we hear it.")
                diff > 0 -> tr("Repeater słyszy nas wyraźnie słabiej niż my jego – warto sprawdzić jego odbiór (antena, kabel, zakłócenia).", "The repeater hears us clearly worse than we hear it – check its reception (antenna, cable, interference).")
                else -> tr("My słyszymy repeater wyraźnie słabiej niż on nas – możliwa słaba moc nadawania albo antena.", "We hear the repeater clearly worse than it hears us – possibly low TX power or antenna.")
            }, 11f, Ui.MUTED).apply { setPadding(dp(16), dp(6), dp(16), 0) })
        }

        if (r.topUsers.isNotEmpty()) {
            outDash.addView(Ui.section(this, tr("Top mapujący", "Top mappers"), tr("pomiary", "measurements")))
            val max = r.topUsers.first().second.coerceAtLeast(1)
            r.topUsers.forEachIndexed { i, (name, count) ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(6), dp(16), dp(6))
                }
                row.addView(Ui.text(this, "${i + 1}", 13f, Ui.MUTED, bold = true), LinearLayout.LayoutParams(dp(20), ViewGroup.LayoutParams.WRAP_CONTENT))
                row.addView(Ui.text(this, name, 14f, Ui.TEXT).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val bar = FrameLayout(this).apply { background = Ui.rounded(this@MainActivity, Ui.PANEL, 3) }
                bar.addView(View(this).apply { background = Ui.rounded(this@MainActivity, Ui.GREEN, 3) },
                    FrameLayout.LayoutParams(dp(70) * count / max, ViewGroup.LayoutParams.MATCH_PARENT))
                row.addView(bar, LinearLayout.LayoutParams(dp(70), dp(5)).apply { marginStart = dp(8); marginEnd = dp(10) })
                row.addView(Ui.text(this, "$count", 14f, Ui.TEXT, bold = true).apply { gravity = Gravity.END },
                    LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT))
                outDash.addView(row)
            }
        }
        outDash.addView(Ui.text(this, tr("Ostatnia odpowiedź: ${TimeFormat.dayMonth(r.lastMs)} ${TimeFormat.hm(r.lastMs)} · liczone jak „Statystyki repeatera” na stronie mappera.",
            "Last response: ${TimeFormat.dayMonth(r.lastMs)} ${TimeFormat.hm(r.lastMs)} · computed like “Repeater stats” on the mapper website."), 10f, Ui.MUTED)
            .apply { setPadding(dp(16), dp(12), dp(16), 0) })
    }

    /** Kafelek statystyki: etykieta, duża wartość, dopisek. */
    private fun statTile(label: String, value: String, sub: String, color: Int = Ui.TEXT): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(this@MainActivity, Ui.PANEL, 10)
        setPadding(dp(9), dp(8), dp(9), dp(8))
        addView(Ui.text(this@MainActivity, label.uppercase(), 9f, Ui.MUTED).apply { letterSpacing = 0.06f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        addView(Ui.text(this@MainActivity, value, 17f, color, bold = true).apply { maxLines = 1 })
        addView(Ui.text(this@MainActivity, sub.ifEmpty { " " }, 10f, Ui.MUTED).apply { maxLines = 2 })
    }

    /** „+4,2 dB” (przecinek po polsku). */
    private fun db(v: Double): String = (if (v > 0) "+" else "") + num(v) + " dB"

    private fun num(v: Double): String {
        val s = String.format(java.util.Locale.US, "%.1f", v)
        return if (L10n.en) s else s.replace('.', ',')
    }

    private fun measureWord(n: Int) = when {
        n == 1 -> "pomiar"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "pomiary"
        else -> "pomiarów"
    }

    /**
     * Rejestr przestojów: niedostępność MQTT1/MQTT2 i przerwy w wysyłaniu pakietów przez Observera.
     * U góry suma czasu i dostępność w wybranym okresie, niżej historia (30 dni, przerwy ≥ 1 min).
     */
    private fun renderOutages(periodMs: Long, periodLabel: String, now: Long) {
        val cfg = Config(this)
        val list = Outages.all(this, now)
        if (!cfg.monitoringEnabled) {
            outDash.addView(infoItem(tr("Monitoring zatrzymany – przestoje nie są teraz rejestrowane.", "Monitoring stopped – outages are not being recorded now."), Ui.ORANGE))
        }

        outDash.addView(Ui.section(this, tr("Podsumowanie", "Summary"), tr("ostatnie $periodLabel", "last $periodLabel")))
        val totals = OutageKind.values().map { k -> k to OutageEngine.totalInWindow(list, k, now, periodMs) }
        val summary = totals.map { (k, total) ->
            val ongoing = list.any { it.kind == k && it.endMs == null }
            Ui.kv(this, k.label, if (total == 0L) "0 min" else duration(total), when {
                ongoing -> Ui.RED
                total > 0L -> Ui.ORANGE
                else -> Ui.GREEN
            })
        }
        outDash.addView(Ui.kvRow(this, summary[0], summary[1], summary[2]).apply { setPadding(dp(16), dp(4), dp(16), 0) })
        val avail = totals.map { (k, total) ->
            val pct = 100.0 * (1.0 - total.toDouble() / periodMs).coerceIn(0.0, 1.0)
            val txt = if (total == 0L) "100%" else num(minOf(pct, 99.9)) + "%"
            Ui.kv(this, k.label, txt, if (total == 0L) Ui.GREEN else Ui.ORANGE)
        }
        outDash.addView(Ui.text(this, tr("Dostępność w okresie", "Uptime in period"), 11f, Ui.MUTED).apply { setPadding(dp(16), dp(10), dp(16), 0) })
        outDash.addView(Ui.kvRow(this, avail[0], avail[1], avail[2]).apply { setPadding(dp(16), dp(2), dp(16), dp(4)) })

        outDash.addView(Ui.section(this, tr("Historia", "History"), tr("30 dni · przerwy ≥ 1 min", "30 days · gaps ≥ 1 min")))
        if (list.isEmpty()) {
            outDash.addView(infoItem(tr("Brak przestojów. Każda niedostępność MQTT1/MQTT2 i przerwa w pakietach Observera (dłuższa niż próg czerwony) trafi tutaj.",
                "No outages. Every MQTT1/MQTT2 unavailability and gap in Observer packets (longer than the red threshold) will show up here."), Ui.MUTED))
            return
        }
        var lastDay: LocalDate? = null
        var first = true
        for (o in list) {
            val d = Instant.ofEpochMilli(o.startMs).atZone(ZoneId.systemDefault()).toLocalDate()
            if (first || d != lastDay) {
                outDash.addView(Ui.section(this, dayLabel(d), color = Ui.MUTED))
                lastDay = d
                first = false
            } else outDash.addView(Ui.divider(this))
            outDash.addView(outageItem(o, now))
        }
    }

    private fun outageItem(o: Outage, now: Long): View {
        val box = Ui.item(this)
        val ongoing = o.endMs == null
        val title = SpannableStringBuilder().apply {
            append(o.kind.label)
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(if (ongoing) Ui.RED else Ui.ORANGE), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val s = length
            val end = o.endMs
            append("   ${TimeFormat.hm(o.startMs)} → ")
            append(when {
                end == null -> tr("teraz", "now")
                TimeFormat.dayMonth(end) != TimeFormat.dayMonth(o.startMs) -> "${TimeFormat.dayMonth(end)} ${TimeFormat.hm(end)}"
                else -> TimeFormat.hm(end)
            })
            setSpan(ForegroundColorSpan(Ui.TEXT), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val right = if (ongoing) Ui.text(this, tr("● trwa ${duration(o.durationMs(now))}", "● ongoing ${duration(o.durationMs(now))}"), 12f, Ui.RED, bold = true)
            else Ui.text(this, duration(o.durationMs(now)), 13f, Ui.SUBTEXT, bold = true)
        box.addView(Ui.row(this, Ui.text(this, title, 14f, Ui.TEXT), right))
        val detail = when (o.kind) {
            OutageKind.OBSERVER -> tr("Brak pakietów od Observera", "No packets from Observer")
            OutageKind.MQTT1 -> tr("Broker ${Config.displayHost(Config(this).mqtt1Host)} niedostępny", "Broker ${Config.displayHost(Config(this).mqtt1Host)} unavailable")
            OutageKind.MQTT2 -> tr("Broker ${Config.displayHost(Config(this).mqtt2Host)} niedostępny", "Broker ${Config.displayHost(Config(this).mqtt2Host)} unavailable")
        } + (o.note?.let { " · $it" } ?: "")
        box.addView(Ui.text(this, detail, 11f, Ui.MUTED).apply { setPadding(0, dp(2), 0, 0) })
        return box
    }

    /** „45 s”, „26 min”, „3 h 05 min”, „2 d 4 h”. */
    private fun duration(ms: Long): String {
        val min = ms / 60_000L
        return when {
            ms < 60_000L -> "${ms / 1000L} s"
            min < 60 -> "$min min"
            min < 24 * 60 -> "${min / 60} h ${(min % 60).toString().padStart(2, '0')} min"
            else -> "${min / (24 * 60)} d ${(min % (24 * 60)) / 60} h"
        }
    }

    private fun entriesWord(n: Int) = when {
        L10n.en -> if (n == 1) "entry" else "entries"
        n == 1 -> "wpis"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "wpisy"
        else -> "wpisów"
    }

    // ───────────────────────────── pomocnicze ─────────────────────────────

    private fun pingCount(n: Int): String {
        val word = when {
            L10n.en -> if (n == 1) "ping" else "pings"
            n == 1 -> "ping"
            n % 10 in 2..4 && n % 100 !in 12..14 -> "pingi"
            else -> "pingów"
        }
        return "$n $word"
    }

    private fun ago(tsMs: Long, nowMs: Long): String {
        val min = (nowMs - tsMs).coerceAtLeast(0L) / 60_000L
        return if (min < 1) tr("teraz", "now") else "$min min"
    }

    private fun dim(color: Int): Int = (color and 0x00FFFFFF) or 0x73000000

    private fun timeOf(ms: Long): String =
        DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    private fun fmtPl(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v)

    /** Lista rozwinięta → ticker nie przerysowuje panelu (inaczej lista znika przed kliknięciem). */
    private var logSpinnerOpen = false

    /** Opcje retencji śladu mapowania w LOG: (wartość ms, −1 = na stałe) → etykieta. */
    private val keepOptions get() = listOf(
        24 * 3600_000L to "24 h",
        48 * 3600_000L to "48 h",
        3 * 24 * 3600_000L to tr("3 dni", "3 days"),
        10 * 24 * 3600_000L to tr("10 dni", "10 days"),
        30 * 24 * 3600_000L to tr("30 dni", "30 days"),
        -1L to tr("na stałe", "forever")
    )

    private fun matchParent() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    private fun dp(v: Int) = Ui.dp(this, v)

    companion object {
        const val EXTRA_SHOW_MAPPING = "pl.meshcore.observerwidget.SHOW_MAPPING"
        /** Otwarcie aplikacji od razu na karcie Ustawienia (dwuklik widgetu przez [SettingsActivity]). */
        const val EXTRA_OPEN_SETTINGS = "pl.meshcore.observerwidget.OPEN_SETTINGS"
        private const val STATE_TAB = "tab"
        const val TAB_OBSERVER = 0
        const val TAB_MAP = 1
        const val TAB_LOG = 2
        const val TAB_STATS = 3
        const val TAB_SETTINGS = 4
        /** Dociągnięcie nowych punktów mappera najwcześniej po tylu ms (wejście na kartę). */
        private const val STATS_RELOAD_MS = 10 * 60_000L

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
