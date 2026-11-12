package pl.meshcore.observerwidget

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.StateListDrawable
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
 * Ekran ustawień (karta „Ustawienia” i [SettingsActivity]): Observer, brokery/API, odświeżanie,
 * progi kolorów i powiadomienia PUSH. Te same pola, walidacja i zapis co dotąd – zmienia się
 * tylko wygląd (sekcje i pola w stylu M2).
 */
class SettingsScreen(private val activity: Activity) {
    private val cfg = Config(activity)
    private lateinit var idField: EditText
    private lateinit var m1Field: EditText
    private lateinit var m2Field: EditText
    private lateinit var fbField: EditText
    private lateinit var timeoutField: EditText
    private lateinit var orangeField: EditText
    private lateinit var redField: EditText
    private lateinit var intervalBar: SeekBar
    private lateinit var intervalLabel: TextView
    private lateinit var info: TextView
    /** Wynik Test API – tuż pod przyciskiem, żeby był widoczny bez przewijania. */
    private lateinit var testResult: LinearLayout
    private lateinit var alertObs: Switch
    private lateinit var alertM1: Switch
    private lateinit var alertM2: Switch
    private lateinit var alertMap: Switch
    private lateinit var lockScreen: Switch
    private lateinit var mapperField: EditText

    /** Zawartość ekranu (bez przewijania – opakowuje ją wywołujący). */
    fun build(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(0, 0, 0, dp(24))
        }
        val version = runCatching {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        }.getOrNull()
        root.addView(Ui.topBar(activity, "USTAWIENIA", version?.let { "Wersja $it" }))

        root.addView(Ui.section(activity, "Observer"))
        idField = field(root, "Pełny hash Observera (64 znaki hex)", cfg.observerId,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, mono = true)

        root.addView(Ui.section(activity, "Brokery"))
        m1Field = field(root, "MQTT1 / API główne", cfg.mqtt1Host)
        m2Field = field(root, "MQTT2 / drugie API", cfg.mqtt2Host)
        fbField = field(root, "API zapasowe (nagłówek, gdy oba powyższe nie działają)", cfg.fallbackHost)
        timeoutField = field(root, "Timeout zapytań (s, 2–30)", cfg.timeoutSec.toString(), InputType.TYPE_CLASS_NUMBER)
        mapperField = field(root, "API mapera (action=points / repeaters)", cfg.mapperApi,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        root.addView(note("Zmiana API mapera czyści dotychczasowy stan mapowania."))
        root.addView(Ui.outlineButton(activity, "Test API · sprawdź i pobierz nazwę Observera") { testApi() })
        testResult = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        root.addView(testResult)

        root.addView(Ui.section(activity, "Odświeżanie i progi"))
        intervalLabel = Ui.text(activity, "", 14f, Ui.TEXT).apply { setPadding(dp(16), dp(4), dp(16), 0) }
        root.addView(intervalLabel)
        intervalBar = SeekBar(activity).apply {
            max = Config.MAX_INTERVAL - Config.MIN_INTERVAL
            progress = cfg.intervalSec - Config.MIN_INTERVAL
            progressTintList = ColorStateList.valueOf(Ui.GREEN)
            thumbTintList = ColorStateList.valueOf(Ui.GREEN)
            progressBackgroundTintList = ColorStateList.valueOf(Ui.OUTLINE)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) = updateIntervalLabel()
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(intervalBar)
        updateIntervalLabel()
        root.addView(note("Dane pobierane są co interwał, także przy wyłączonym ekranie."))
        val thresholds = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        orangeField = field(thresholds, "Pomarańczowy po (min)", cfg.orangeMin.toString(), InputType.TYPE_CLASS_NUMBER, inRow = true)
        redField = field(thresholds, "Czerwony po (min)", cfg.redMin.toString(), InputType.TYPE_CLASS_NUMBER, inRow = true)
        root.addView(thresholds)
        root.addView(note("Kolor wieku ostatniego pakietu: zielony → pomarańczowy → czerwony."))

        root.addView(Ui.section(activity, "Powiadomienia"))
        alertObs = toggle(root, "Observer: brak pakietów (niezależnie od MQTT)", cfg.alertObserver)
        alertM1 = toggle(root, "MQTT1: brak połączenia / brak odbioru", cfg.alertMqtt1)
        alertM2 = toggle(root, "MQTT2: brak połączenia / brak odbioru", cfg.alertMqtt2)
        alertMap = toggle(root, "Mapowanie: ktoś mapuje w zasięgu Observera", cfg.alertMapping)
        root.addView(note("Alarm przychodzi od razu (PUSH), mapowanie – raz na sesję z nickiem."))
        lockScreen = toggle(root, "Dane Observera w powiadomieniu (ekran blokady)", cfg.lockScreenStatus)
        root.addView(note("Brak na ekranie blokady? Włącz w telefonie pokazywanie cichych powiadomień."))

        info = Ui.text(activity, "", 14f, Ui.GREEN).apply { setPadding(dp(16), dp(12), dp(16), 0) }
        root.addView(info)
        root.addView(Ui.button(activity, "Zapisz i odśwież widget", primary = true) { save() })
        return root
    }

    private fun updateIntervalLabel() {
        intervalLabel.text = "Interwał odświeżania: ${intervalBar.progress + Config.MIN_INTERVAL} s"
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
            info.setTextColor(Ui.RED)
            info.text = "Progi: liczby całkowite; czerwony musi być większy niż pomarańczowy"
            return
        }
        val timeout = timeoutField.text.toString().toIntOrNull()
        if (timeout == null || timeout !in 2..30) {
            timeoutField.error = "Timeout musi wynosić 2–30 s"
            return
        }
        val hosts = listOf(m1Field.text.toString().trim(), m2Field.text.toString().trim())
        val fallback = fbField.text.toString().trim()
        val validHost: (String) -> Boolean = { host ->
            host.isNotBlank() && !host.any { it.isWhitespace() } &&
                (host.startsWith("https://") || host.startsWith("http://") || !host.contains("://"))
        }
        if (hosts.any { !validHost(it) } || (fallback.isNotBlank() && !validHost(fallback))) {
            info.setTextColor(Ui.RED)
            info.text = "Podaj poprawne adresy hostów API (bez spacji)"
            return
        }
        val mapperRaw = mapperField.text.toString().trim()
        if (mapperRaw.isNotEmpty() && !validHost(mapperRaw)) {
            mapperField.error = "Podaj poprawny adres API mapera (bez spacji)"
            return
        }
        val mapperApi = MapperApi.normalizeBase(mapperRaw)
        // Zmiana Observera albo API mapera = inna baza/ID: reset stanu mapowania i pozycji RPT.
        if (id != cfg.observerId || mapperApi != MapperApi.normalizeBase(cfg.mapperApi)) {
            MapperMonitor.reset(activity.applicationContext)
            cfg.mapperLastId = 0L
            cfg.mapperRev = 0
            cfg.mapperObsLat = Float.NaN
            cfg.mapperObsLon = Float.NaN
            cfg.mapperObsPosOk = false
            cfg.mapperObsPosAtMs = 0L
        }
        cfg.observerId = id
        cfg.mapperApi = mapperApi
        MapperApi.base = mapperApi
        cfg.mqtt1Host = hosts[0]
        cfg.mqtt2Host = hosts[1]
        cfg.fallbackHost = fallback
        cfg.timeoutSec = timeout
        cfg.intervalSec = intervalBar.progress + Config.MIN_INTERVAL
        cfg.orangeMin = orange
        cfg.redMin = red
        cfg.alertObserver = alertObs.isChecked
        cfg.alertMqtt1 = alertM1.isChecked
        cfg.alertMqtt2 = alertM2.isChecked
        cfg.alertMapping = alertMap.isChecked
        cfg.lockScreenStatus = lockScreen.isChecked
        Alerts.ensureChannels(activity)
        RefreshWorker.sync(activity)
        info.setTextColor(Ui.GREEN)
        info.text = "Zapisano"
        ObserverRefresh.ensureTicking(activity)
        ObserverRefresh.refreshAll(activity)
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
        val mapperApi = MapperApi.normalizeBase(mapperField.text.toString())
        testResult.removeAllViews()
        testResult.addView(Ui.text(activity, "Sprawdzam ${hosts.size} ${if (hosts.size == 1) "host" else "hosty"} i API mapera…", 14f, Ui.SUBTEXT)
            .apply { setPadding(0, dp(8), 0, dp(4)) })
        thread {
            val results = hosts.map { h -> Triple(h, ObserverApi.fetch(h, id, timeout), MqttStatusApi.fetch(h, timeout)) }
            val mapperOk = MapperApi.check(mapperApi, timeout)
            activity.runOnUiThread {
                testResult.removeAllViews()
                for ((h, obs, mqtt) in results) {
                    val box = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        background = Ui.rounded(activity, Ui.PANEL, 8)
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                    }
                    box.addView(Ui.text(activity, Config.displayHost(h), 14f, Ui.TEXT, bold = true))
                    val (obsText, obsColor) = when (obs) {
                        is FetchResult.Ok -> ("✓ Observer: ${obs.snapshot.name ?: "(brak nazwy)"}" +
                            (obs.snapshot.model?.let { " / $it" } ?: "") + (obs.snapshot.firmware?.let { " / fw $it" } ?: "")) to Ui.GREEN
                        is FetchResult.Fail -> "✕ Observer: ${obs.reason}" to Ui.RED
                    }
                    box.addView(Ui.text(activity, obsText, 13f, obsColor))
                    val (mqttText, mqttColor) = when (mqtt) {
                        Health.GOOD -> "✓ Broker: ŻYJE" to Ui.GREEN
                        Health.BAD -> "✕ Broker: MARTWY (strona nie odpowiada, status zamrożony lub brak połączenia MQTT)" to Ui.RED
                        Health.UNKNOWN -> "? Broker: NIEZNANY (niezrozumiała odpowiedź /api/mqtt/status)" to Ui.ORANGE
                    }
                    box.addView(Ui.text(activity, mqttText, 13f, mqttColor))
                    testResult.addView(box, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) })
                }
                val box = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = Ui.rounded(activity, Ui.PANEL, 8)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                box.addView(Ui.text(activity, "Mapper · ${MapperApi.host(mapperApi)}", 14f, Ui.TEXT, bold = true))
                box.addView(Ui.text(activity, if (mapperOk) "✓ API mapera: odpowiada" else "✕ API mapera: brak odpowiedzi lub błąd",
                    13f, if (mapperOk) Ui.GREEN else Ui.RED))
                testResult.addView(box, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) })
            }
        }
    }

    private fun dp(v: Int) = Ui.dp(activity, v)

    /** Tło pola: kontur szary, przy edycji zielony (jak pola M2). */
    private fun fieldBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(activity, 0x00000000, 6, Ui.GREEN))
        addState(intArrayOf(), Ui.rounded(activity, 0x00000000, 6, Ui.OUTLINE))
    }

    /**
     * Pole z etykietą nad ramką. [inRow] = jedno z kilku pól w poziomym rzędzie (równa szerokość),
     * inaczej pole na całą szerokość z marginesem ekranu.
     */
    private fun field(
        parent: LinearLayout, label: String, value: String,
        type: Int = InputType.TYPE_CLASS_TEXT, mono: Boolean = false, inRow: Boolean = false
    ): EditText {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(if (inRow) 0 else dp(16), dp(6), if (inRow) 0 else dp(16), dp(4))
        }
        val lbl = Ui.text(activity, label, 11f, Ui.MUTED).apply { setPadding(dp(2), 0, 0, dp(4)) }
        box.addView(lbl)
        val edit = EditText(activity).apply {
            setText(value)
            inputType = type
            setSingleLine(true)
            textSize = 14f
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED)
            if (mono) typeface = android.graphics.Typeface.MONOSPACE
            background = fieldBackground()
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setOnFocusChangeListener { _, has -> lbl.setTextColor(if (has) Ui.GREEN else Ui.MUTED) }
        }
        box.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (inRow) {
            parent.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (parent.childCount > 0) marginStart = dp(8)
            })
        } else parent.addView(box)
        return edit
    }

    private fun toggle(parent: LinearLayout, label: String, on: Boolean): Switch {
        val sw = Switch(activity).apply {
            text = label
            isChecked = on
            textSize = 14f
            setTextColor(Ui.TEXT)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Ui.GREEN, Ui.MUTED))
            trackTintList = ColorStateList(states, intArrayOf(0x8867D78A.toInt(), Ui.OUTLINE))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            switchPadding = dp(12)
        }
        parent.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return sw
    }

    private fun note(t: String) = Ui.text(activity, t, 12f, Ui.MUTED).apply {
        setPadding(dp(16), dp(4), dp(16), dp(4))
    }
}
