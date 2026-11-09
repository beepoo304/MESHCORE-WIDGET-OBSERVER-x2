package pl.meshcore.observerwidget

import android.Manifest
import android.app.Activity
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
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Aplikacja w stylu M2 z dolnymi zakładkami: Status (dashboard), Zasięg (rekordy z historii mappera),
 * Ludzie (kto mapuje przy moich węzłach) i Ustawienia. Widget bez zmian.
 */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var ui: M2Ui
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var actionView: TextView
    private lateinit var content: LinearLayout
    private lateinit var contentScroll: ScrollView
    private lateinit var settingsScroll: ScrollView
    private var settings: SettingsPane? = null
    private val navItems = mutableMapOf<String, Pair<FrameLayout, Pair<ImageView, TextView>>>()
    private var tab = TAB_STATUS
    private var dashboardData: WidgetData? = null
    /** Otwarto z ikony mapowania na widgecie – pokaż kartę mapowania także bez danych z 24 h. */
    private var showMapping = false

    /** Co sekundę: licznik w nagłówku i przerysowanie zakładki Status z danych w pamięci. */
    private val ticker = object : Runnable {
        override fun run() {
            if (!Config(this@MainActivity).monitoringEnabled) { updateHeader(); return }
            if (!MonitorService.isRunning && ObserverRefresh.isRefreshDue()) ObserverRefresh.refreshAll(applicationContext)
            if (tab == TAB_STATUS) renderStatus()
            else if (tab == TAB_PEOPLE && System.currentTimeMillis() / 1000 % 10 == 0L) renderPeople()
            updateHeader()
            val now = System.currentTimeMillis()
            handler.postDelayed(this, 1_000L - (now % 1_000L) + 15L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = M2Ui(this)
        Alerts.ensureChannels(this)
        RefreshWorker.sync(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val root = ui.vertical().apply { setBackgroundColor(M2Ui.BG) }
        // Nagłówek: duży tytuł zakładki, podtytuł i akcja po prawej (licznik odświeżania).
        val header = ui.row().apply {
            setBackgroundColor(M2Ui.HEADER)
            setPadding(ui.dp(18), ui.dp(14), ui.dp(14), ui.dp(12))
        }
        val titles = ui.vertical()
        titleView = ui.text("", 24f, M2Ui.WHITE, bold = true).apply { letterSpacing = 0.03f }
        subtitleView = ui.text("", 13f, M2Ui.MUTED).apply { maxLines = 2 }
        titles.addView(titleView)
        titles.addView(subtitleView)
        header.addView(titles, ui.weight())
        actionView = ui.text("", 14f, M2Ui.GREEN, bold = true).apply {
            setPadding(ui.dp(10), ui.dp(6), ui.dp(4), ui.dp(6))
            setOnClickListener { onHeaderAction() }
        }
        header.addView(actionView)
        root.addView(header)

        content = ui.vertical().apply { setPadding(0, 0, 0, ui.dp(16)) }
        contentScroll = ScrollView(this).apply { addView(content) }
        settingsScroll = ScrollView(this).apply { visibility = View.GONE }
        val body = FrameLayout(this)
        body.addView(contentScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(settingsScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomNav())
        setContentView(root)
        root.applySystemBarsPadding()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        showMapping = i?.getBooleanExtra(EXTRA_SHOW_MAPPING, false) == true
        val t = i?.getStringExtra(EXTRA_TAB)
        select(if (showMapping) TAB_PEOPLE else t?.takeIf { it in TABS } ?: tab)
    }

    override fun onResume() {
        super.onResume()
        HistoryJobs.listener = { runOnUiThread { if (!isFinishing && (tab == TAB_RANGE || tab == TAB_PEOPLE)) renderTab() } }
        handler.removeCallbacks(ticker)
        if (Config(this).monitoringEnabled) {
            // Aplikacja na pierwszym planie: Android pozwala uruchomić usługę tykania widgetu.
            ObserverRefresh.ensureTicking(this)
            handler.post(ticker)
        }
        HistoryJobs.kick(this)
        renderTab()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        HistoryJobs.listener = null
    }

    // ---------------------------------------------------------------- zakładki

    private fun bottomNav(): View {
        val nav = ui.row().apply {
            setBackgroundColor(M2Ui.NAV)
            setPadding(0, ui.dp(8), 0, ui.dp(8))
        }
        val defs = listOf(
            Triple(TAB_STATUS, "Status", R.drawable.ic_tab_status),
            Triple(TAB_RANGE, "Zasięg", R.drawable.ic_tab_range),
            Triple(TAB_PEOPLE, "Ludzie", R.drawable.ic_tab_people),
            Triple(TAB_SETTINGS, "Ustawienia", R.drawable.ic_tab_settings)
        )
        for ((key, label, icon) in defs) {
            val item = ui.vertical().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setOnClickListener { select(key) }
            }
            val pill = FrameLayout(this)
            val img = ImageView(this).apply { setImageResource(icon) }
            pill.addView(img, FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
            item.addView(pill, LinearLayout.LayoutParams(ui.dp(60), ui.dp(30)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            val txt = ui.text(label, 12f, M2Ui.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(3), 0, 0) }
            item.addView(txt)
            nav.addView(item, ui.weight())
            navItems[key] = pill to (img to txt)
        }
        return nav
    }

    private fun select(key: String) {
        tab = key
        for ((k, v) in navItems) {
            val on = k == key
            val (pill, it) = v
            val (img, txt) = it
            pill.background = if (on) ui.rounded(M2Ui.PILL, 999) else null
            img.setColorFilter(if (on) M2Ui.WHITE else M2Ui.MUTED)
            txt.setTextColor(if (on) M2Ui.WHITE else M2Ui.MUTED)
            txt.typeface = if (on) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.DEFAULT
        }
        if (key == TAB_SETTINGS) {
            if (settings == null) {
                settings = SettingsPane(this, ui) { onMonitoringChanged() }
                settingsScroll.addView(settings!!.view)
            } else {
                settings!!.renderRpts()
                settings!!.updateMonitorButton()
            }
            settingsScroll.visibility = View.VISIBLE
            contentScroll.visibility = View.GONE
        } else {
            settingsScroll.visibility = View.GONE
            contentScroll.visibility = View.VISIBLE
            contentScroll.scrollTo(0, 0)
        }
        if (key == TAB_RANGE || key == TAB_PEOPLE) HistoryJobs.kick(this, force = MapperHistory.lastSyncMs(this) == 0L)
        renderTab()
    }

    private fun renderTab() {
        when (tab) {
            TAB_STATUS -> renderStatus()
            TAB_RANGE -> renderRange()
            TAB_PEOPLE -> renderPeople()
        }
        updateHeader()
    }

    private fun onMonitoringChanged() {
        handler.removeCallbacks(ticker)
        if (Config(this).monitoringEnabled) handler.post(ticker)
        updateHeader()
    }

    private fun updateHeader() {
        val cfg = Config(this)
        when (tab) {
            TAB_STATUS -> {
                titleView.text = "STATUS"
                val d = dashboardData
                val obs = d?.observer
                subtitleView.text = if (d == null || !d.configured) "Observer nieustawiony"
                    else listOfNotNull(obs?.name ?: "Observer", obs?.model, obs?.firmware?.let { "fw $it" }).joinToString(" · ")
                actionView.text = if (cfg.monitoringEnabled) "↻ ${ObserverRefresh.remainingSeconds(this)}s" else "⏸"
            }
            TAB_RANGE -> {
                titleView.text = "ZASIĘG"
                subtitleView.text = "Rekordy z całej historii w bazie mappera"
                actionView.text = if (HistoryJobs.isRunning) "…" else "↻"
            }
            TAB_PEOPLE -> {
                titleView.text = "LUDZIE"
                subtitleView.text = "Kto mapuje przy Twoich węzłach"
                actionView.text = if (HistoryJobs.isRunning) "…" else "↻"
            }
            TAB_SETTINGS -> {
                titleView.text = "USTAWIENIA"
                subtitleView.text = "Observer, repeatery, API i powiadomienia"
                actionView.text = ""
            }
        }
    }

    private fun onHeaderAction() {
        when (tab) {
            TAB_STATUS -> if (Config(this).monitoringEnabled) ObserverRefresh.refreshAll(applicationContext)
            TAB_RANGE, TAB_PEOPLE -> { HistoryJobs.kick(this, force = true); updateHeader() }
        }
    }

    // ---------------------------------------------------------------- Status

    private fun renderStatus() {
        val cfg = Config(this)
        val d = if (!cfg.monitoringEnabled) dashboardData ?: ObserverRefresh.emptyData(this)
            else ObserverRefresh.latest() ?: ObserverRefresh.emptyData(this, busy = true)
        dashboardData = d
        content.removeAllViews()
        val wallClockNow = System.currentTimeMillis()
        val now = if (cfg.monitoringEnabled) wallClockNow else cfg.monitoringStoppedAtMs.takeIf { it > 0L } ?: 0L
        if (!cfg.monitoringEnabled) {
            content.addView(ui.text("Monitoring zatrzymany. Dane nie są odświeżane, alarmy są wyłączone. Uruchom go w Ustawieniach.", 14f, WidgetRenderer.ORANGE).apply {
                setPadding(ui.dp(18), ui.dp(14), ui.dp(18), 0)
            })
        }
        if (!d.configured) {
            content.addView(ui.text("Wejdź w Ustawienia i wpisz pełny hash Observera (64 znaki).", 17f, WidgetRenderer.ORANGE).apply {
                setPadding(ui.dp(18), ui.dp(16), ui.dp(18), 0)
            })
            return
        }
        ui.section(content, "BROKERY")
        brokerItem("MQTT1", d.mqtt1Host, d.broker1, now, d.mqtt1Health)
        brokerItem("MQTT2", d.mqtt2Host, d.broker2, now, d.mqtt2Health)

        d.observer?.let { o ->
            content.addView(ui.muted("Dane z: ${Config.displayHost(o.host)}", 12f).apply { setPadding(ui.dp(18), ui.dp(10), ui.dp(18), 0) })
        }
        val fetchedAt = d.observer?.fetchedAtMs ?: now
        content.addView(ui.muted(
            "Pobrano ${DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(fetchedAt).atZone(ZoneId.systemDefault()))}, " +
                "następne za ${TimeFormat.countdown(ObserverRefresh.remainingSeconds(this, wallClockNow), cfg.monitoringEnabled)}", 12f
        ).apply { setPadding(ui.dp(18), ui.dp(4), ui.dp(18), 0) })
    }

    private fun brokerItem(title: String, host: String, r: FetchResult?, now: Long, health: Health) {
        ui.item(content) {
            val row = ui.row()
            row.addView(ui.text(title, 17f, WidgetRenderer.brokerHealthColor(health, r), bold = true))
            row.addView(ui.text("  ${Config.displayHost(host)}", 14f, M2Ui.MUTED), ui.weight())
            addView(row)
            when (r) {
                is FetchResult.Ok -> {
                    val s = r.snapshot
                    addView(ui.text("${s.packetsPerHour} pkt/h · razem ${Status.formatCount(s.totalPackets)}", 15f, M2Ui.TEXT).apply { setPadding(0, ui.dp(3), 0, 0) })
                    if (s.lastPacketMs == null) addView(ui.muted("Ostatni pakiet: brak danych", 14f))
                    else {
                        val age = s.lastPacketAgeMs(now) ?: 0L
                        val cfg = Config(this@MainActivity)
                        val color = when (Status.level(age, cfg.orangeMin, cfg.redMin)) {
                            Level.GREEN -> M2Ui.GREEN; Level.ORANGE -> WidgetRenderer.ORANGE; else -> WidgetRenderer.RED
                        }
                        addView(ui.text("Ostatni pakiet: ${TimeFormat.packetAge(age)} temu", 16f, color, bold = true).apply { setPadding(0, ui.dp(2), 0, 0) })
                    }
                }
                is FetchResult.Fail -> addView(ui.text("API niedostępne: ${r.reason}", 15f, WidgetRenderer.RED))
                null -> addView(ui.muted("…", 15f))
            }
        }
    }

    // ---------------------------------------------------------------- Zasięg

    /** Brak historii: komunikat ładowania albo wskazówka. true = można rysować dane. */
    private fun historyOrPlaceholder(): MapperHistory.Snapshot? {
        val cfg = Config(this)
        if (MapperHistory.myShorts(cfg).isEmpty()) {
            content.addView(ui.text("Wpisz hash Observera albo dodaj repeater w Ustawieniach.", 15f, WidgetRenderer.ORANGE).apply {
                setPadding(ui.dp(18), ui.dp(16), ui.dp(18), 0)
            })
            return null
        }
        val snap = MapperHistory.snapshot(this)
        if (snap == null) {
            val msg = if (HistoryJobs.isRunning) "Pobieram historię mappera…\nPierwsze pobranie całej bazy może potrwać do minuty."
                else "Nie udało się pobrać historii mappera. Dotknij ↻, żeby spróbować ponownie."
            content.addView(ui.text(msg, 15f, M2Ui.MUTED).apply { setPadding(ui.dp(18), ui.dp(18), ui.dp(18), 0) })
        }
        return snap
    }

    private fun renderRange() {
        content.removeAllViews()
        val snap = historyOrPlaceholder() ?: return
        val cfg = Config(this)
        val nodes = HistoryJobs.nodes(cfg, snap)
        val records = nodes.associateWith { MapperStats.record(snap.points, it) }
        val best = records.values.filterNotNull().maxByOrNull { it.km }

        ui.section(content, "REKORD ZASIĘGU")
        for (n in nodes) {
            val rec = records[n]
            val color = if (n.isObserver) M2Ui.OBS_BLUE else M2Ui.RPT_ORANGE
            ui.item(content) {
                val top = ui.row()
                top.addView(ui.tag(n.label, color))
                top.addView(ui.text(n.name ?: n.shortId.take(4).uppercase() + "…", 14f, M2Ui.MUTED).apply {
                    setPadding(ui.dp(8), 0, 0, 0)
                }, ui.weight())
                if (rec != null && rec === best) top.addView(ui.text("★ NAJDALEJ", 12f, M2Ui.GREEN, bold = true))
                addView(top)
                when {
                    !n.hasPos -> addView(ui.muted("Brak pozycji węzła w bazie mappera – nie da się policzyć odległości.", 14f))
                    rec == null -> addView(ui.muted("Jeszcze żaden ping mappera nie został usłyszany.", 14f))
                    else -> {
                        addView(ui.text("${GeoMath.km(rec.km)} ${GeoMath.compassLabel(rec.bearingDeg)}", 22f, M2Ui.WHITE, bold = true).apply {
                            setPadding(0, ui.dp(4), 0, 0)
                        })
                        val nick = snap.nick(rec.ping.userId)
                        val snr = rec.ping.observerSnrDb?.let { " · SNR ${fmt(it)} dB" } ?: ""
                        addView(ui.muted("${fmtTime(rec.ping.tsMs, "dd.MM.yyyy HH:mm")} · $nick$snr", 14f))
                        addView(ui.keyRow(CompanionKeys.keyFor(this@MainActivity, nick), nick))
                    }
                }
            }
        }

        if (nodes.size > 1) {
            ui.section(content, "OBSERVER vs RPT")
            val stats = nodes.map { MapperStats.nodeStats(snap.points, it) }
            val bestPings = stats.maxOfOrNull { it.pings }
            val bestKm = stats.mapNotNull { it.maxKm }.maxOrNull()
            val bestSnr = stats.mapNotNull { it.avgSnr }.maxOrNull()
            tableRow(listOf("", "pingów", "max", "śr. SNR"), header = true)
            for (s in stats) {
                tableRow(
                    listOf(
                        s.node.label,
                        s.pings.toString(),
                        s.maxKm?.let { GeoMath.km(it) } ?: "—",
                        s.avgSnr?.let { "${fmt(it)} dB" } ?: "—"
                    ),
                    greens = listOf(false, s.pings == bestPings && s.pings > 0, s.maxKm != null && s.maxKm == bestKm, s.avgSnr != null && s.avgSnr == bestSnr),
                    firstColor = if (s.node.isObserver) M2Ui.OBS_BLUE else M2Ui.RPT_ORANGE
                )
            }
        }
        syncFooter(snap)
    }

    private fun tableRow(cells: List<String>, header: Boolean = false, greens: List<Boolean> = emptyList(), firstColor: Int = M2Ui.WHITE) {
        val row = ui.row().apply { setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10)) }
        cells.forEachIndexed { i, c ->
            val color = when {
                header -> M2Ui.MUTED
                i == 0 -> firstColor
                greens.getOrNull(i) == true -> M2Ui.GREEN
                else -> M2Ui.WHITE
            }
            val size = when { header -> 12f; i == 0 -> 11f; else -> 15f }
            row.addView(ui.text(c, size, color, bold = !header && (i == 0 || greens.getOrNull(i) == true)).apply {
                gravity = if (i == 0) Gravity.START else Gravity.END
                isSingleLine = true
            }, ui.weight(if (i == 0) 1.3f else 1f))
        }
        content.addView(row)
        content.addView(View(this).apply { setBackgroundColor(M2Ui.LINE) }, ui.lp(h = ui.dp(1)))
    }

    private fun syncFooter(snap: MapperHistory.Snapshot) {
        val t = if (snap.syncedAtMs > 0) fmtTime(snap.syncedAtMs, "dd.MM HH:mm") else "—"
        content.addView(ui.muted("Historia: ${snap.points.size} punktów · synchronizacja $t" + if (HistoryJobs.isRunning) " · odświeżam…" else "", 12f).apply {
            setPadding(ui.dp(18), ui.dp(12), ui.dp(18), 0)
        })
    }

    // ---------------------------------------------------------------- Ludzie

    private fun renderPeople() {
        content.removeAllViews()
        mappingSection()
        val snap = historyOrPlaceholder() ?: return
        val cfg = Config(this)

        ui.section(content, "DZIENNY RAPORT · 8:00")
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val from = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val report = HistoryJobs.report(cfg, snap, from, to)
        val box = ui.vertical().apply {
            background = ui.rounded(M2Ui.PUSH_BG, 14)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        }
        box.addView(ui.text("Raport ${today.minusDays(1).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))}", 14f, M2Ui.WHITE, bold = true))
        box.addView(ui.text(MapperStats.dailyText(report), 14f, M2Ui.TEXT).apply { setPadding(0, ui.dp(4), 0, 0) })
        box.addView(ui.muted(if (cfg.alertDaily) "Push codziennie o 8:00 za poprzedni dzień." else "Push wyłączony w Ustawieniach.", 12f))
        content.addView(box, ui.lp().apply { setMargins(ui.dp(18), ui.dp(8), ui.dp(18), 0) })
        syncFooter(snap)
    }

    /**
     * „MAPOWANIE · 24 H”: każdy mapujący usłyszany przez moje węzły w ostatnich 24 h osobno,
     * z jego ostatnią sesją. Łączy historię mapera z bieżącymi danymi monitoringu (świeższe).
     */
    private fun mappingSection() {
        val cfg = Config(this)
        val obsShort = MapperApi.shortId(cfg.observerId)
        val labels = LinkedHashMap<String, String>()
        if (obsShort.length == 16) labels[obsShort] = "OBSERVER"
        for ((slot, id) in cfg.rptIds.toSortedMap()) {
            val sh = MapperApi.shortId(id)
            if (sh.length == 16 && sh !in labels) labels[sh] = "RPT$slot"
        }
        if (labels.isEmpty()) return
        val now = System.currentTimeMillis()
        val from = now - MapperApi.KEEP_MS
        val snap = MapperHistory.snapshot(this)
        val d = dashboardData ?: ObserverRefresh.latest()
        val pool = mutableListOf<MapperPing>()
        snap?.points?.filterTo(pool) { it.tsMs >= from && it.rptId != null && it.rptId in labels }
        d?.mapping?.pings?.forEach { if (it.tsMs >= from) pool.add(if (it.rptId == null) it.copy(rptId = obsShort) else it) }
        d?.rptMappings?.forEach { r -> r.state.pings.forEach { if (it.tsMs >= from) pool.add(if (it.rptId == null) it.copy(rptId = r.shortId) else it) } }
        val pings = pool.filter { it.rptId in labels }.distinctBy { Triple(it.userId, it.tsMs, it.rptId) }

        val center = labels.keys.firstNotNullOfOrNull { id ->
            snap?.repeaters?.get(id)?.let { r -> if (r.lat != null && r.lon != null) r.lat to r.lon else null }
        } ?: d?.mapping?.let { m -> if (m.observerLat != null && m.observerLon != null) m.observerLat to m.observerLon else null }

        ui.section(content, "MAPOWANIE · 24 H")
        val sessions = pings.groupBy { it.userId }
            .map { (user, list) -> user to MapperStats.splitSessions(list.sortedBy { it.tsMs }).last() }
            .sortedByDescending { it.second.last().tsMs }
        if (sessions.isEmpty()) {
            ui.item(content) { addView(ui.muted("Nikt nie mapował przy Twoich węzłach w ciągu ostatnich 24 h.", 14f)) }
            return
        }
        sessions.forEachIndexed { i, (user, s) ->
            val nick = snap?.nick(user) ?: MapperMonitor.nick(user) ?: "user $user"
            val first = s.first(); val last = s.last()
            val active = now - last.tsMs <= MapperApi.SESSION_GAP_MS
            ui.item(content) {
                setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))
                val row = ui.row()
                row.addView(ui.text(nick, 16f, WidgetRenderer.mapperArrowColor(i), bold = true), ui.weight())
                row.addView(ui.text(if (active) "● W TOKU" else "OSTATNIE", 12f, if (active) M2Ui.GREEN else M2Ui.MUTED, bold = true))
                addView(row)
                val minutes = ((last.tsMs - first.tsMs) / 60_000L).coerceAtLeast(0L)
                val time = if (active) "od ${fmtTime(first.tsMs, "HH:mm")} · ostatni ${TimeFormat.packetAge(now - last.tsMs)} temu"
                    else "${fmtTime(first.tsMs, "dd.MM HH:mm")}–${fmtTime(last.tsMs, "HH:mm")} ($minutes min)"
                addView(ui.text(time, 14f, M2Ui.TEXT).apply { setPadding(0, ui.dp(4), 0, 0) })
                val parts = mutableListOf(MapperStats.pingsLabel(s.distinctBy { it.tsMs }.size))
                if (center != null) {
                    s.filter { it.lat != null && it.lon != null }
                        .minByOrNull { GeoMath.distanceKm(center.first, center.second, it.lat!!, it.lon!!) }
                        ?.let { p ->
                            val km = GeoMath.distanceKm(center.first, center.second, p.lat!!, p.lon!!)
                            parts.add("najbliżej ${GeoMath.km(km)} ${GeoMath.compassLabel(GeoMath.bearingDeg(center.first, center.second, p.lat, p.lon))}")
                        }
                }
                s.mapNotNull { it.observerSnrDb }.maxOrNull()?.let { parts.add("SNR max ${fmt(it)} dB") }
                addView(ui.muted(parts.joinToString(" · "), 13f))
                // Które moje węzły go słyszały w tej sesji.
                val byNode = s.groupBy { it.rptId!! }.mapValues { (_, l) -> l.distinctBy { it.tsMs }.size }
                val nr = ui.row().apply { setPadding(0, ui.dp(6), 0, 0) }
                for (id in labels.keys) {
                    val n = byNode[id] ?: continue
                    val label = labels.getValue(id)
                    nr.addView(ui.tag("$label · $n", if (label == "OBSERVER") M2Ui.OBS_BLUE else M2Ui.RPT_ORANGE).apply {
                        textSize = 12f
                        setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
                    }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ui.dp(8) })
                }
                addView(nr)
                addView(ui.keyRow(CompanionKeys.keyFor(this@MainActivity, nick), nick).apply { setPadding(0, ui.dp(6), 0, 0) })
            }
        }
    }

    // ---------------------------------------------------------------- pomocnicze

    private fun fmtTime(ms: Long, pattern: String): String =
        DateTimeFormatter.ofPattern(pattern).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    private fun fmt(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v).replace('.', ',')

    companion object {
        const val EXTRA_SHOW_MAPPING = "pl.meshcore.observerwidget.SHOW_MAPPING"
        const val EXTRA_TAB = "pl.meshcore.observerwidget.TAB"
        const val TAB_STATUS = "status"
        const val TAB_RANGE = "range"
        const val TAB_PEOPLE = "people"
        const val TAB_SETTINGS = "settings"
        private val TABS = setOf(TAB_STATUS, TAB_RANGE, TAB_PEOPLE, TAB_SETTINGS)

        /** Ikona „M” na widgecie: otwiera aplikację na zakładce Status z kartą mapowania. */
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
