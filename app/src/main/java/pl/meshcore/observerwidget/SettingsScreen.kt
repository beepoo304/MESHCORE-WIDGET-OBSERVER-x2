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
    private lateinit var alertObs: Switch
    private lateinit var alertM1: Switch
    private lateinit var alertM2: Switch
    private lateinit var alertMap: Switch
    private lateinit var lockScreen: Switch
    private lateinit var delayField: EditText
    private lateinit var cooldownField: EditText

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
        root.addView(Ui.outlineButton(activity, "Test API · sprawdź i pobierz nazwę Observera") { testApi() })

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
        root.addView(note("Licznik odświeżania i wiek pakietu zmieniają się co sekundę dzięki usłudze w tle (stałe, ciche powiadomienie „Monitoring Observera aktywny”). Przy wyłączonym ekranie dane nadal są pobierane co interwał, a widget dorysowuje się w ciągu sekundy po włączeniu ekranu (częściowy wakelock chroni pętlę przed Doze)."))
        val thresholds = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        orangeField = field(thresholds, "Pomarańczowy po (min)", cfg.orangeMin.toString(), InputType.TYPE_CLASS_NUMBER, inRow = true)
        redField = field(thresholds, "Czerwony po (min)", cfg.redMin.toString(), InputType.TYPE_CLASS_NUMBER, inRow = true)
        root.addView(thresholds)
        root.addView(note("Progi koloru czasu ostatniego pakietu (zielony → pomarańczowy → czerwony)."))

        root.addView(Ui.section(activity, "Powiadomienia"))
        alertObs = toggle(root, "Observer: brak pakietów (niezależnie od MQTT)", cfg.alertObserver)
        alertM1 = toggle(root, "MQTT1: brak połączenia / brak odbioru", cfg.alertMqtt1)
        alertM2 = toggle(root, "MQTT2: brak połączenia / brak odbioru", cfg.alertMqtt2)
        alertMap = toggle(root, "Mapowanie: ktoś mapuje w zasięgu Observera (pingi mapera meshcoresouth.pl)", cfg.alertMapping)
        val timing = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        delayField = field(timing, "Opóźnienie alarmu (min)", cfg.alertDelayMin.toString(), InputType.TYPE_CLASS_NUMBER, inRow = true)
        cooldownField = field(timing, "Cooldown (min, min. 1)", cfg.alertCooldownMin.toString(), InputType.TYPE_CLASS_NUMBER, inRow = true)
        root.addView(timing)
        root.addView(note("Alarm Observera jest niezależny od statusów MQTT i przychodzi natychmiast po przekroczeniu czerwonego progu, a w treści podaje nazwę API, z którego przyszły dane (gdy oba nie odpowiadają – obie). Opóźnienie (min) dotyczy tylko alarmów MQTT; 0 = natychmiast. Powiadomienie o mapowaniu przychodzi z nickiem mapującego raz na sesję (i przy zmianie mapującego), gdy ktoś mapuje w zasięgu Observera – dane z publicznej bazy mapera meshcoresouth.pl."))
        lockScreen = toggle(root, "Dane Observera w powiadomieniu (ekran blokady)", cfg.lockScreenStatus)
        root.addView(note("Stałe powiadomienie pokazuje status, pakiety/h i wiek ostatniego pakietu obu brokerów, a po rozwinięciu także MAPPER z kierunkiem i odległością. Treść odświeża się po każdym pobraniu danych. Jeśli nie widać go na ekranie blokady: Ustawienia telefonu → Powiadomienia → Ekran blokady → pokazuj wszystkie (także ciche)."))

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
            info.setTextColor(Ui.RED)
            info.text = "Podaj poprawne adresy hostów API (bez spacji)"
            return
        }
        // Zmiana Observera = nowy ID w bazie mapera: reset stanu mapowania i pozycji RPT.
        if (id != cfg.observerId) {
            MapperMonitor.reset(activity.applicationContext)
            cfg.mapperLastId = 0L
            cfg.mapperRev = 0
            cfg.mapperObsLat = Float.NaN
            cfg.mapperObsLon = Float.NaN
            cfg.mapperObsPosOk = false
            cfg.mapperObsPosAtMs = 0L
        }
        cfg.observerId = id
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
        cfg.alertDelayMin = delay
        cfg.alertCooldownMin = cooldown
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
        info.setTextColor(Ui.TEXT)
        info.text = "Sprawdzam…"
        thread {
            val lines = hosts.map { h ->
                val observerResult = ObserverApi.fetch(h, id, timeout)
                val observerLine = when (observerResult) {
                    is FetchResult.Ok -> "Observer ✓: ${observerResult.snapshot.name ?: "(brak nazwy)"}" +
                        (observerResult.snapshot.model?.let { " / $it" } ?: "") + (observerResult.snapshot.firmware?.let { " / fw $it" } ?: "")
                    is FetchResult.Fail -> "Observer ✕: ${observerResult.reason}"
                }
                val mqttLine = when (MqttStatusApi.fetch(h, timeout)) {
                    Health.GOOD -> "Broker: ŻYJE"
                    Health.BAD -> "Broker: MARTWY (strona nie odpowiada, status zamrożony lub brak połączenia MQTT)"
                    Health.UNKNOWN -> "Broker: NIEZNANY (niezrozumiała odpowiedź /api/mqtt/status)"
                }
                "${Config.displayHost(h)}\n$observerLine\n$mqttLine"
            }
            activity.runOnUiThread {
                info.setTextColor(Ui.TEXT)
                info.text = lines.joinToString("\n")
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
