package pl.meshcore.observerwidget

import org.junit.Assert.assertEquals
import org.junit.Test

class MapperApiUrlTest {
    @Test fun emptyFallsBackToDefault() {
        assertEquals(MapperApi.DEFAULT_BASE, MapperApi.normalizeBase(""))
        assertEquals(MapperApi.DEFAULT_BASE, MapperApi.normalizeBase(null))
        assertEquals(MapperApi.DEFAULT_BASE, MapperApi.normalizeBase("   "))
    }

    @Test fun addsSchemeAndDropsQuery() {
        assertEquals("https://example.org/mapper/api.php", MapperApi.normalizeBase(" example.org/mapper/api.php?action=points "))
        assertEquals("http://10.0.0.5/api.php", MapperApi.normalizeBase("http://10.0.0.5/api.php"))
    }

    @Test fun buildsUrls() {
        assertEquals("https://meshcoresouth.pl/mapper/api.php?action=points&since=42&limit=20000",
            MapperApi.pointsUrl(MapperApi.DEFAULT_BASE, 42))
        assertEquals("https://x.pl/api.php?action=repeaters", MapperApi.repeatersUrl("x.pl/api.php"))
    }

    @Test fun hostForDisplay() {
        assertEquals("meshcoresouth.pl", MapperApi.host(MapperApi.DEFAULT_BASE))
    }
}
