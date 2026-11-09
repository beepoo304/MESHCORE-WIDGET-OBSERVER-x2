package pl.meshcore.observerwidget

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Zakładka Ustawienia: Observer, moje repeatery, API, odświeżanie, progi, okolica, powiadomienia
 * i start/stop monitoringu. Budowana raz (pola nie znikają przy przerysowaniu innych zakładek).
 */
class SettingsPane(private val act: Activity, private val ui: M2Ui, private val onMonitoringChanged: () -> Unit) {
    private val cfg = Config(act)
    val view: LinearLayout = ui.vertical()

    private lateinit var idField: EditText
    private lateinit var m1Field: EditText
    private lateinit var m2Field: EditText
    private lateinit var fbField: EditText
    private lateinit var timeoutField: EditText
    private lateinit var orangeField: EditText
    private lateinit var redField: EditText
    private lateinit var delayField: EditText
    private lateinit var cooldownField: EditText
    private lateinit var intervalBar: SeekBar
    private lateinit var intervalLabel: TextView
    private lateinit var radiusBar: SeekBar
    private lateinit var radiusLabel: TextView
    private lateinit var info: TextView
    private lateinit var apiInfo: TextView
    private lateinit var lockScreen: Switch
    private lateinit var alertObs: Switch
    private lateinit var alertM1: Switch
    private lateinit var alertM2: Switch
    private lateinit var alertMap: Switch
    private lateinit var alertDaily: Switch
    private lateinit var alertNearby: Switch
    private val rptBox: LinearLayout = ui.vertical()
    private lateinit var monitorButton: TextView

    init { build() }

    private fun build() {
        val v = view
        ui.section(v, "OBSERVER")
        idField = ui.field(v, "Pełny hash (64 znaki hex)", cfg.observerId,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, mono = true)
        val idActions = ui.row().apply { setPadding(0, 0, 0, 0) }
        idActions.addView(ui.pill("Sprawdź API i pobierz nazwę") { testApi() })
        idActions.addView(ui.copyIcon("", "Observera").apply {
            setOnClickListener {
                val id = idField.text.toString().trim()
                if (id.isNotEmpty()) ui.copy(id, "Observera")
            }
            layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(30)).apply { topMargin = ui.dp(12) }
        })
        v.addView(idActions)
        apiInfo = ui.muted("", 12f).apply { setPadding(ui.dp(18), ui.dp(6), ui.dp(18), 0) }
        v.addView(apiInfo)

        ui.section(v, "MOJE REPEATERY", "max ${Config.RPT_SLOTS}")
        v.addView(ui.muted("Mapowanie przy nich: RPT1–RPT5 (pomarańczowe) na widgecie, w historii i w push.", 12f).apply {
            setPadding(ui.dp(18), 0, ui.dp(18), ui.dp(4))
        })
        v.addView(rptBox)
        renderRpts()

        ui.section(v, "API")
        m1Field = ui.field(v, "MQTT1 / API główne", cfg.mqtt1Host)
        m2Field = ui.field(v, "MQTT2 / drugie API", cfg.mqtt2Host)
        fbField = ui.field(v, "API zapasowe (gdy oba nie działają)", cfg.fallbackHost)
        timeoutField = ui.field(v, "Timeout zapytań (s, 2–30)", cfg.timeoutSec.toString(), InputType.TYPE_CLASS_NUMBER)

        ui.section(v, "ODŚWIEŻANIE")
        intervalLabel = ui.text("", 14f).apply { setPadding(ui.dp(18), ui.dp(8), ui.dp(18), 0) }
        v.addView(intervalLabel)
        intervalBar = seek(Config.MAX_INTERVAL - Config.MIN_INTERVAL, cfg.intervalSec - Config.MIN_INTERVAL) { updateLabels() }
        v.addView(intervalBar, ui.lp().apply { setMargins(ui.dp(6), 0, ui.dp(6), 0) })
        lockScreen = ui.switchRow(v, "Dane Observera na ekranie blokady", cfg.lockScreenStatus,
            "Stałe powiadomienie pokazuje status, pakiety/h i wiek ostatniego pakietu obu brokerów, a po rozwinięciu także MAPPER. Jeśli go nie widać: Ustawienia telefonu → Powiadomienia → Ekran blokady → pokazuj wszystkie (także ciche).")

        ui.section(v, "PROGI KOLORU CZASU PAKIETU")
        val (o, r) = ui.fieldPair(v, "Pomarańczowy po (min)", cfg.orangeMin.toString(), "Czerwony po (min)", cfg.redMin.toString(), InputType.TYPE_CLASS_NUMBER)
        orangeField = o; redField = r

        ui.section(v, "OKOLICA")
        radiusLabel = ui.text("", 14f).apply { setPadding(ui.dp(18), ui.dp(8), ui.dp(18), 0) }
        v.addView(radiusLabel)
        radiusBar = seek((Config.MAX_RADIUS_M - Config.MIN_RADIUS_M) / 100, (Math.round(cfg.nearbyRadiusKm * 10) - 1).toInt()) { updateLabels() }
        v.addView(radiusBar, ui.lp().apply { setMargins(ui.dp(6), 0, ui.dp(6), 0) })
        v.addView(ui.muted("Promień od Observera dla „Mapował obok · nie słyszałeś” (zakładka Ludzie, push i raport).", 12f).apply {
            setPadding(ui.dp(18), 0, ui.dp(18), 0)
        })
        updateLabels()

        ui.section(v, "POWIADOMIENIA PUSH")
        alertObs = ui.switchRow(v, "Observer: brak pakietów", cfg.alertObserver, "Natychmiast po czerwonym progu, niezależnie od MQTT.")
        alertM1 = ui.switchRow(v, "MQTT1: brak połączenia / odbioru", cfg.alertMqtt1)
        alertM2 = ui.switchRow(v, "MQTT2: brak połączenia / odbioru", cfg.alertMqtt2)
        alertMap = ui.switchRow(v, "Mapowanie w zasięgu", cfg.alertMapping, "Nick mapującego raz na sesję (baza mapera meshcoresouth.pl).")
        alertDaily = ui.switchRow(v, "Dzienny raport o 8:00", cfg.alertDaily, "Podsumowanie wczorajszego dnia przy Twoich węzłach.")
        alertNearby = ui.switchRow(v, "Mapował obok – nie słyszałeś", cfg.alertNearby, "Ktoś mapował w okolicy, a żaden Twój węzeł go nie usłyszał.")
        val (d, c) = ui.fieldPair(v, "Opóźnienie MQTT (min)", cfg.alertDelayMin.toString(), "Cooldown (min, min. 1)", cfg.alertCooldownMin.toString(), InputType.TYPE_CLASS_NUMBER)
        delayField = d; cooldownField = c

        info = ui.text("", 14f, M2Ui.GREEN).apply { setPadding(ui.dp(18), ui.dp(12), ui.dp(18), 0) }
        v.addView(info)
        v.addView(ui.pill("Zapisz i odśwież widget", full = true) { save() })
        monitorButton = ui.pill("", full = true) { toggleMonitoring() }
        v.addView(monitorButton)
        updateMonitorButton()
        v.addView(ui.spacer(24))
    }

    private fun seek(max: Int, progress: Int, onChange: () -> Unit) = SeekBar(act).apply {
        this.max = max
        this.progress = progress.coerceIn(0, max)
        progressTintList = android.content.res.ColorStateList.valueOf(M2Ui.GREEN)
        thumbTintList = android.content.res.ColorStateList.valueOf(M2Ui.GREEN)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) = onChange()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun updateLabels() {
        if (::intervalBar.isInitialized) intervalLabel.text = "Interwał odświeżania: ${intervalBar.progress + Config.MIN_INTERVAL} s"
        if (::radiusBar.isInitialized) radiusLabel.text = "Promień okolicy: ${radiusText()} km"
    }

    private fun radiusKm() = (radiusBar.progress + 1) / 10.0
    private fun radiusText() = String.format(java.util.Locale.ROOT, "%.1f", radiusKm()).replace('.', ',')

    fun updateMonitorButton() {
        val on = Config(act).monitoringEnabled
        monitorButton.text = if (on) "Zatrzymaj monitoring" else "Uruchom monitoring"
        monitorButton.background = ui.rounded(if (on) M2Ui.STOP else M2Ui.GREEN, 999)
    }

    private fun toggleMonitoring() {
        if (Config(act).monitoringEnabled) {
            ObserverRefresh.stop(act)
        } else {
            ObserverRefresh.start(act)
            MonitorService.ensureChannel(act)
        }
        updateMonitorButton()
        onMonitoringChanged()
    }

    /** Lista RPT1–RPT5: klucz (klik = zmiana), kopiowanie, usuwanie, status w bazie mappera. */
    fun renderRpts() {
        rptBox.removeAllViews()
        val ids = cfg.rptIds
        for ((slot, id) in ids.toSortedMap()) {
            ui.item(rptBox) {
                val row = ui.row()
                row.addView(ui.tag("RPT$slot", M2Ui.RPT_ORANGE))
                row.addView(ui.text(ui.shortKey(id), 14f, M2Ui.TEXT, mono = true).apply {
                    setPadding(ui.dp(10), ui.dp(4), ui.dp(4), ui.dp(4))
                    setOnClickListener { editRptDialog(slot) }
                }, ui.weight())
                row.addView(ui.copyIcon(id, "RPT$slot"))
                row.addView(ui.text("✕", 18f).apply {
                    setPadding(ui.dp(12), ui.dp(2), ui.dp(4), ui.dp(2))
                    contentDescription = "Usuń RPT$slot"
                    setOnClickListener {
                        AlertDialog.Builder(act)
                            .setTitle("Usunąć RPT$slot?")
                            .setMessage("Klucz ${id.take(8)}… zniknie z widgetu i powiadomień.")
                            .setPositiveButton("Usuń") { _, _ ->
                                cfg.setRptId(slot, "")
                                nodesChanged()
                                renderRpts()
                            }
                            .setNegativeButton("Anuluj", null)
                            .show()
                    }
                })
                addView(row)
                val rInfo = MapperMonitor.rptInfo(MapperApi.shortId(id))
                val (status, color) = when {
                    rInfo == null -> "sprawdzam w bazie mappera…" to M2Ui.MUTED
                    rInfo.found -> "✓ w bazie mappera: „${rInfo.name ?: "bez nazwy"}”" to M2Ui.GREEN
                    rInfo.name != null -> "„${rInfo.name}” bez pozycji w bazie mappera – bez strzałki i odległości" to M2Ui.RPT_ORANGE
                    else -> "✕ brak w bazie mappera – bez nazwy, strzałki i odległości" to M2Ui.RPT_ORANGE
                }
                addView(ui.text(status, 12f, color).apply { setPadding(0, ui.dp(4), 0, 0) })
            }
        }
        val free = (1..Config.RPT_SLOTS).firstOrNull { it !in ids }
        if (free != null) rptBox.addView(ui.pill("+ Dodaj repeater") { editRptDialog(free) })
    }

    private fun editRptDialog(slot: Int) {
        val input = EditText(act).apply {
            setText(cfg.rptId(slot))
            hint = "klucz repeatera (64 znaki hex)"
            typeface = Typeface.MONOSPACE
            textSize = 14f
            isSingleLine = true
        }
        val wrap = LinearLayout(act).apply {
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(act)
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
                val changed = MapperApi.shortId(cfg.rptId(slot)) != short
                cfg.setRptId(slot, id)
                dialog.dismiss()
                if (changed) nodesChanged()
                renderRpts()
                thread {
                    MapperMonitor.lookupRpt(act.applicationContext, id, 6_000)
                    act.runOnUiThread { if (!act.isFinishing) renderRpts() }
                }
            }
        }
        dialog.show()
    }

    /** Zmiana listy węzłów: historia mappera pobierze się od nowa. */
    private fun nodesChanged() {
        MapperHistory.invalidate(act)
        HistoryJobs.kick(act, force = true)
    }

    private fun error(t: String) {
        info.setTextColor(M2Ui.STOP)
        info.text = t
    }

    private fun save() {
        val id = idField.text.toString().trim()
        if (!Config.isValidId(id)) {
            idField.error = "Hash musi mieć dokładnie 64 znaki 0-9 a-f"
            return
        }
        val orange = orangeField.text.toString().toIntOrNull()
        val red = redField.text.toString().toIntOrNull()
        if (orange == null || red == null || orange < 1 || red <= orange) {
            error("Progi: liczby całkowite; czerwony musi być większy niż pomarańczowy")
            return
        }
        val timeout = timeoutField.text.toString().toIntOrNull()
        if (timeout == null || timeout !in 2..30) {
            timeoutField.error = "Timeout musi wynosić 2–30 s"
            return
        }
        val delay = delayField.text.toString().toIntOrNull()
        if (delay == null || delay !in 0..1440) {
            delayField.error = "Opóźnienie musi wynosić 0–1440 min"
            return
        }
        val cooldown = cooldownField.text.toString().toIntOrNull()
        if (cooldown == null || cooldown !in 1..10080) {
            cooldownField.error = "Cooldown musi wynosić 1–10080 min"
            return
        }
        val hosts = listOf(m1Field.text.toString().trim(), m2Field.text.toString().trim())
        val fallback = fbField.text.toString().trim()
        val validHost: (String) -> Boolean = { host ->
            host.isNotBlank() && !host.any { it.isWhitespace() } &&
                (host.startsWith("https://") || host.startsWith("http://") || !host.contains("://"))
        }
        if (hosts.any { !validHost(it) } || (fallback.isNotBlank() && !validHost(fallback))) {
            error("Podaj poprawne adresy hostów API (bez spacji)")
            return
        }
        val observerChanged = id != cfg.observerId
        // Zmiana Observera = nowy ID w bazie mapera: reset stanu mapowania i pozycji RPT.
        if (observerChanged) {
            MapperMonitor.reset(act.applicationContext)
            cfg.mapperLastId = 0L
            cfg.mapperRev = 0
            cfg.mapperObsLat = Float.NaN
            cfg.mapperObsLon = Float.NaN
            cfg.mapperObsPosOk = false
            cfg.mapperObsPosAtMs = 0L
        }
        val radiusChanged = Math.round(radiusKm() * 10) != Math.round(cfg.nearbyRadiusKm * 10)
        cfg.observerId = id
        cfg.mqtt1Host = hosts[0]
        cfg.mqtt2Host = hosts[1]
        cfg.fallbackHost = fallback
        cfg.timeoutSec = timeout
        cfg.intervalSec = intervalBar.progress + Config.MIN_INTERVAL
        cfg.orangeMin = orange
        cfg.redMin = red
        cfg.nearbyRadiusKm = radiusKm()
        cfg.alertObserver = alertObs.isChecked
        cfg.alertMqtt1 = alertM1.isChecked
        cfg.alertMqtt2 = alertM2.isChecked
        cfg.alertMapping = alertMap.isChecked
        cfg.alertDaily = alertDaily.isChecked
        cfg.alertNearby = alertNearby.isChecked
        cfg.lockScreenStatus = lockScreen.isChecked
        cfg.alertDelayMin = delay
        cfg.alertCooldownMin = cooldown
        Alerts.ensureChannels(act)
        RefreshWorker.sync(act)
        info.setTextColor(M2Ui.GREEN)
        info.text = "Zapisano"
        ObserverRefresh.ensureTicking(act)
        ObserverRefresh.refreshAll(act)
        if (observerChanged) nodesChanged() else if (radiusChanged) HistoryJobs.listener?.invoke()
    }

    private fun testApi() {
        val id = idField.text.toString().trim()
        if (!Config.isValidId(id)) {
            idField.error = "Najpierw wpisz poprawny hash (64 znaki)"
            return
        }
        val hosts = listOf(m1Field.text.toString(), m2Field.text.toString(), fbField.text.toString())
            .filter { it.isNotBlank() }
        val timeout = (timeoutField.text.toString().toIntOrNull() ?: 6).coerceIn(2, 30) * 1000
        apiInfo.setTextColor(M2Ui.WHITE)
        apiInfo.text = "Sprawdzam…"
        thread {
            val lines = hosts.map { h ->
                val observerLine = when (val r = ObserverApi.fetch(h, id, timeout)) {
                    is FetchResult.Ok -> "Observer ✓: ${r.snapshot.name ?: "(brak nazwy)"}" +
                        (r.snapshot.model?.let { " / $it" } ?: "") + (r.snapshot.firmware?.let { " / fw $it" } ?: "")
                    is FetchResult.Fail -> "Observer ✕: ${r.reason}"
                }
                val mqttLine = when (MqttStatusApi.fetch(h, timeout)) {
                    Health.GOOD -> "Broker: ŻYJE"
                    Health.BAD -> "Broker: MARTWY (strona nie odpowiada, status zamrożony lub brak połączenia MQTT)"
                    Health.UNKNOWN -> "Broker: NIEZNANY (niezrozumiała odpowiedź /api/mqtt/status)"
                }
                "${Config.displayHost(h)}\n$observerLine\n$mqttLine"
            }
            act.runOnUiThread {
                apiInfo.setTextColor(M2Ui.WHITE)
                apiInfo.text = lines.joinToString("\n\n")
            }
        }
    }

    @Suppress("unused")
    private fun divider() = View(act).apply { setBackgroundColor(M2Ui.LINE) }
}
