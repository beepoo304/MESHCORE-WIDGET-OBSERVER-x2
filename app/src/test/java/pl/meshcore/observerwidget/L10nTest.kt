package pl.meshcore.observerwidget

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class L10nTest {
    @After fun reset() = L10n.set(L10n.PL)

    @Test fun polishByDefault() {
        L10n.set(L10n.PL)
        assertEquals("Ustawienia", tr("Ustawienia", "Settings"))
    }

    @Test fun englishWhenSelected() {
        L10n.set(L10n.EN)
        assertEquals("Settings", tr("Ustawienia", "Settings"))
    }

    @Test fun unknownCodeFallsBackToPolish() {
        L10n.set("de")
        assertEquals("Przestoje", tr("Przestoje", "Outages"))
    }
}
