package pl.meshcore.observerwidget

/**
 * Wariant widgetu dobrany do rozmiaru nadanego przez launcher (w dp, wartości MIN z opcji widgetu,
 * więc treść mieści się niezależnie od orientacji ekranu).
 *
 * - compact: niski widget (np. 4x2), jednowierszowe karty brokerów,
 * - showTotal: kolumna "Total packets" jest widoczna tylko, gdy jest dość miejsca na szerokość.
 */
data class WidgetLayout(val compact: Boolean, val showTotal: Boolean) {
    companion object {
        const val FULL_MIN_HEIGHT_DP = 250
        const val TOTAL_MIN_WIDTH_DP = 240

        /** Domyślny rozmiar widgetu to 4x2, więc gdy rozmiar jest nieznany, używamy wariantu kompaktowego. */
        val DEFAULT = WidgetLayout(compact = true, showTotal = true)

        fun choose(minWidthDp: Int, minHeightDp: Int): WidgetLayout {
            if (minWidthDp <= 0 || minHeightDp <= 0) return DEFAULT
            return WidgetLayout(
                compact = minHeightDp < FULL_MIN_HEIGHT_DP,
                showTotal = minWidthDp >= TOTAL_MIN_WIDTH_DP
            )
        }
    }
}
