package pl.meshcore.observerwidget

import android.content.Context

/**
 * Język aplikacji (PL / EN), wybierany w Ustawieniach. Teksty w kodzie: [tr] ("polski", "English").
 * Widget nie jest tłumaczony – ma własne, polskie napisy w WidgetRenderer.
 */
object L10n {
    const val PL = "pl"
    const val EN = "en"

    @Volatile var en = false
        private set

    fun init(ctx: Context) = set(Config(ctx.applicationContext).appLanguage)

    fun set(lang: String) {
        en = lang == EN
    }
}

/** Tekst w bieżącym języku aplikacji. */
fun tr(pl: String, en: String): String = if (L10n.en) en else pl
