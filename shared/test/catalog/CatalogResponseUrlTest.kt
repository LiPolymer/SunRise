package ink.lipoly.app.sunrise.catalog

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CatalogResponseUrlTest {
    @Test fun rawChineseSpacesAndPercentSignsAreEncodedOnceForBothOfficialCdns() {
        val path = "频响/Space Travel 100%/literal%20name%2F.txt"
        val encoded = "%E9%A2%91%E5%93%8D/Space%20Travel%20100%25/literal%2520name%252F.txt"
        for ((cdn, base) in listOf(
            CatalogCdn.CHINA to CATALOG_CHINA_CDN_URL,
            CatalogCdn.OVERSEAS to CATALOG_OVERSEAS_CDN_URL,
        )) {
            val url = catalogResponseUrl(cdn, path)
            assertEquals(base + encoded, url)
            assertEquals(listOf("", "频响", "Space Travel 100%", "literal%20name%2F.txt"), Url(url).rawSegments)
        }
    }

    @Test fun pathSeparatorsEmptySegmentsAndTrailingSlashRemainUnchanged() {
        assertEquals(CATALOG_CHINA_CDN_URL + "files//curve/", catalogResponseUrl(CatalogCdn.CHINA, "files//curve/"))
        assertEquals(CATALOG_CHINA_CDN_URL + "files/%252e%252e/curve.txt", catalogResponseUrl(CatalogCdn.CHINA, "files/%2e%2e/curve.txt"))
    }

    @Test fun unsafeRawPathsCannotChangeHostQueryOrFragment() {
        val unsafe = listOf(
            "", " \t", "/curve.txt", "//evil.example/curve.txt", "https://evil.example/curve.txt",
            "C:/curve.txt", "files\\curve.txt", "files/./curve.txt", "files/../curve.txt",
            "../curve.txt", "curve.txt?host=evil", "curve.txt#fragment", "curve\u0000.txt",
            "curve\u001f.txt", "curve\u007f.txt", "curve\u0085.txt", "curve\uD800.txt", "curve\uDC00.txt",
        )
        for (cdn in CatalogCdn.entries) {
            for (path in unsafe) assertFailsWith<IllegalArgumentException> { catalogResponseUrl(cdn, path) }
        }
    }
}
