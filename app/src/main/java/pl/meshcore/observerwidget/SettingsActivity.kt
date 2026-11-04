package pl.meshcore.observerwidget

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.concurrent.thread

/** Ekran ustawień: Observer, API, odświeżanie, progi kolorów i powiadomienia PUSH. */
class SettingsActivity : Activity() {
    private lateinit var cfg: Config
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
    private lateinit var alertObs: CheckBox
    private lateinit var alertM1: CheckBox
    private lateinit var alertM2: CheckBox
    private lateinit var delayField: EditText
    private lateinit var cooldownField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            setBackgroundColor(Color.BLACK)
        }
        root.addView(title("Observer"))
        root.addView(label("Pełny hash (64 znaki hex)"))
        idField = field(cfg.observerId, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        root.addView(idField)

        root.addView(title("API"))
        root.addView(label("MQTT1 / API główne"))
        m1Field = field(cfg.mqtt1Host); root.addView(m1Field)
        root.addView(label("MQTT2 / drugie API"))
        m2Field = field(cfg.mqtt2Host); root.addView(m2Field)
        root.addView(label("API zapasowe (nagłówek, gdy oba powyższe nie działają)"))
        fbField = field(cfg.fallbackHost); root.addView(fbField)
        root.addView(label("Timeout zapytań (s, 2–30)"))
        timeoutField = field(cfg.timeoutSec.toString(), InputType.TYPE_CLASS_NUMBER); root.addView(timeoutField)

        root.addView(title("Odświeżanie"))
        intervalLabel = label("")
        root.addView(intervalLabel)
        intervalBar = SeekBar(this).apply {
            max = Config.MAX_INTERVAL - Config.MIN_INTERVAL
            progress = cfg.intervalSec - Config.MIN_INTERVAL
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) = updateIntervalLabel()
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        root.addView(intervalBar)
        updateIntervalLabel()

        root.addView(title("Progi koloru czasu pakietu"))
        root.addView(label("Pomarańczowy po (min)"))
        orangeField = field(cfg.orangeMin.toString(), InputType.TYPE_CLASS_NUMBER); root.addView(orangeField)
        root.addView(label("Czerwony po (min)"))
        redField = field(cfg.redMin.toString(), InputType.TYPE_CLASS_NUMBER); root.addView(redField)

        root.addView(title("Powiadomienia PUSH"))
        alertObs = check("Observer: brak pakietów (niezależnie od MQTT)", cfg.alertObserver); root.addView(alertObs)
        alertM1 = check("MQTT1: brak połączenia / brak odbioru", cfg.alertMqtt1); root.addView(alertM1)
        alertM2 = check("MQTT2: brak połączenia / brak odbioru", cfg.alertMqtt2); root.addView(alertM2)
        root.addView(label("Opóźnienie alarmu (min)"))
        delayField = field(cfg.alertDelayMin.toString(), InputType.TYPE_CLASS_NUMBER); root.addView(delayField)
        root.addView(label("Cooldown między powiadomieniami (min, min. 1)"))
        cooldownField = field(cfg.alertCooldownMin.toString(), InputType.TYPE_CLASS_NUMBER); root.addView(cooldownField)
        root.addView(label("Alarm Observera jest niezależny od statusów MQTT. Opóźnienie alarmu (min) dotyczy każdego włączonego alarmu; 0 = natychmiast. Sprawdzanie w tle co ok. 15 min (ograniczenie Androida). Opóźnienie alarmu (min) dotyczy każdego włączonego alarmu; 0 = natychmiast. Sprawdzanie w tle co ok. 15 min (ograniczenie Androida)."))

        info = label("").apply { setTextColor(Color.parseColor("#6EF015")) }
        root.addView(info)

        root.addView(Button(this).apply {
            text = "Zapisz i odśwież widget"
            setOnClickListener { save() }
        })
        root.addView(Button(this).apply {
            text = "Sprawdź API i pobierz nazwę Observera"
            setOnClickListener { testApi() }
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(root)
            applySystemBarsPadding()
        })
    }

    private fun updateIntervalLabel() {
        intervalLabel.text = "Interwał odświeżania: ${intervalBar.progress + Config.MIN_INTERVAL} s (tylko przy aktywnej aplikacji, w tle co 15 min)"
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
            info.setTextColor(Color.parseColor("#FF3B30"))
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
            info.setTextColor(Color.parseColor("#FF3B30"))
            info.text = "Podaj poprawne adresy hostów API (bez spacji)"
            return
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
        cfg.alertDelayMin = delay
        cfg.alertCooldownMin = cooldown
        Alerts.ensureChannels(this)
        RefreshWorker.sync(this)
        info.setTextColor(Color.parseColor("#6EF015"))
        info.text = "Zapisano"
        ObserverRefresh.refreshAll(this)
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
        info.setTextColor(Color.WHITE)
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
                    Health.GOOD -> "MQTT: CONNECTED"
                    Health.BAD -> "MQTT: DISCONNECTED"
                    Health.UNKNOWN -> "MQTT: UNKNOWN (brak /api/mqtt/status lub nieaktualny status)"
                }
                "${Config.displayHost(h)}\n$observerLine\n$mqttLine"
            }
            runOnUiThread {
                info.setTextColor(Color.WHITE)
                info.text = lines.joinToString("\n")
            }
        }
    }

    private fun check(t: String, on: Boolean) = CheckBox(this).apply {
        text = t; isChecked = on; setTextColor(Color.WHITE)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 20f; setTextColor(Color.parseColor("#6EF015"))
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(Color.WHITE)
        setPadding(0, dp(6), 0, 0)
    }

    private fun field(value: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(this).apply {
        setText(value); inputType = type; setTextColor(Color.WHITE); setSingleLine(true)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
