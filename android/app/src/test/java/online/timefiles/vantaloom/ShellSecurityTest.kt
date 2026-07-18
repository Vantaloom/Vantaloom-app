package online.timefiles.vantaloom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class ShellSecurityTest {
    @Test
    fun trustedOriginAcceptsOnlyTheBundledAppOrigin() {
        assertTrue(ShellSecurity.isTrustedAppUrl("http://vantaloom.localhost/index.html"))
        assertTrue(ShellSecurity.isTrustedAppUrl("http://VANTALOOM.LOCALHOST:80/settings"))

        assertFalse(ShellSecurity.isTrustedAppUrl("https://vantaloom.localhost/index.html"))
        assertFalse(ShellSecurity.isTrustedAppUrl("http://vantaloom.localhost:8780/index.html"))
        assertFalse(ShellSecurity.isTrustedAppUrl("http://vantaloom.localhost.evil.test/"))
        assertFalse(ShellSecurity.isTrustedAppUrl("http://vantaloom.localhost@evil.test/"))
        assertFalse(ShellSecurity.isTrustedAppUrl("javascript:alert(1)"))
        assertFalse(ShellSecurity.isTrustedAppUrl(null))
    }

    @Test
    fun externalNavigationIsFailClosedByScheme() {
        assertTrue(ShellSecurity.canOpenExternally("https://example.com/docs"))
        assertTrue(ShellSecurity.canOpenExternally("mailto:support@example.com"))
        assertTrue(ShellSecurity.canOpenExternally("tel:+861234567890"))

        assertFalse(ShellSecurity.canOpenExternally("intent://example.com/#Intent;end"))
        assertFalse(ShellSecurity.canOpenExternally("file:///data/local/tmp/payload.html"))
        assertFalse(ShellSecurity.canOpenExternally("content://example/payload"))
        assertFalse(ShellSecurity.canOpenExternally("javascript:alert(1)"))
    }

    @Test
    fun externalFramesAreRecognizedWithoutBlockingApiRequests() {
        assertTrue(ShellSecurity.isDocumentNavigation(true, emptyMap()))
        assertTrue(
            ShellSecurity.isDocumentNavigation(
                false,
                mapOf("sec-fetch-dest" to "iframe", "sec-fetch-mode" to "navigate"),
            ),
        )
        assertTrue(
            ShellSecurity.isDocumentNavigation(
                false,
                mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
            ),
        )
        assertFalse(
            ShellSecurity.isDocumentNavigation(
                false,
                mapOf("Sec-Fetch-Dest" to "empty", "Sec-Fetch-Mode" to "cors"),
            ),
        )
        assertFalse(
            ShellSecurity.isDocumentNavigation(
                false,
                mapOf("Accept" to "application/json, */*"),
            ),
        )
    }

    @Test
    fun loopbackTokensCarryFull256BitsAndRotate() {
        val first = LoopbackAuth.newToken()
        val second = LoopbackAuth.newToken()

        assertEquals(64, first.length)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(first, second)
    }

    @Test
    fun loopbackCapabilitySignerPreservesTheExactTargetAndDropsFragments() {
        val key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val signed = LoopbackCapabilitySigner.authorize(
            "http://127.0.0.1:8780/v1/files/raw?path=%2Fdata%2Fa.png&name=a%20b#private",
            8780,
            key,
            nowUnixSeconds = 1_700_000_000,
        )

        assertEquals(
            "http://127.0.0.1:8780/v1/files/raw?path=%2Fdata%2Fa.png&name=a%20b&" +
                "__vantaloom_loopback_exp=1700043200&" +
                "__vantaloom_loopback_token=a040738baacc7bcdee1f97274b34b632" +
                "edfd7498f229b4efd2da47537ad5f2aa",
            signed,
        )
        val uri = URI(signed!!)
        assertNull(uri.rawFragment)
        assertNotEquals(key, queryValue(uri.rawQuery, LoopbackAuth.queryParameter))
    }

    @Test
    fun loopbackCapabilitySignerRejectsAuthorityAndCredentialConfusion() {
        val key = "1".repeat(64)
        val rejected = listOf(
            "https://127.0.0.1:8780/v1/files/raw",
            "http://localhost:8780/v1/files/raw",
            "http://127.0.0.1:8781/v1/files/raw",
            "http://127.0.0.1/v1/files/raw",
            "http://user@127.0.0.1:8780/v1/files/raw",
            "http://127.0.0.1:8780/v1/files/raw?__vantaloom_loopback_token=old",
            "http://127.0.0.1:8780/v1/files/raw?%5F%5Fvantaloom_loopback_exp=1",
            "http://127.0.0.1:8780/v1/files/raw?broken=%",
        )
        for (raw in rejected) {
            assertNull(raw, LoopbackCapabilitySigner.authorize(raw, 8780, key, 100))
        }
        assertNull(
            LoopbackCapabilitySigner.authorize(
                "http://127.0.0.1:8780/v1/files/raw",
                8780,
                "not-a-key",
                100,
            ),
        )
        assertNull(
            LoopbackCapabilitySigner.authorize(
                "http://127.0.0.1:8780/v1/files/raw",
                8780,
                key,
                Long.MAX_VALUE,
            ),
        )
    }

    @Test
    fun loopbackCapabilitySignerBindsPathQueryAndWebSocketScheme() {
        val key = "2".repeat(64)
        val first = LoopbackCapabilitySigner.authorize(
            "http://127.0.0.1:8780/v1/files/raw?a=1&b=%2F",
            8780,
            key,
            2_000,
        )!!
        val reordered = LoopbackCapabilitySigner.authorize(
            "http://127.0.0.1:8780/v1/files/raw?b=%2F&a=1",
            8780,
            key,
            2_000,
        )!!
        val modifiedPath = LoopbackCapabilitySigner.authorize(
            "http://127.0.0.1:8780/v1/files/other?a=1&b=%2F",
            8780,
            key,
            2_000,
        )!!
        val websocket = LoopbackCapabilitySigner.authorize(
            "ws://127.0.0.1:8780/v1/ws?channel=agent",
            8780,
            key,
            2_000,
        )!!

        assertNotEquals(signature(first), signature(reordered))
        assertNotEquals(signature(first), signature(modifiedPath))
        assertTrue(websocket.startsWith("ws://127.0.0.1:8780/v1/ws?channel=agent&"))
    }

    private fun signature(raw: String): String =
        queryValue(URI(raw).rawQuery, LoopbackAuth.queryParameter)

    private fun queryValue(rawQuery: String?, name: String): String = rawQuery.orEmpty()
        .split('&')
        .first { it.substringBefore('=') == name }
        .substringAfter('=')
}
