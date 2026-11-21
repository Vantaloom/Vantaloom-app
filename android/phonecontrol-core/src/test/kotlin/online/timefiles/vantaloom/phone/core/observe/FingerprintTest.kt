package online.timefiles.vantaloom.phone.core.observe

import online.timefiles.vantaloom.phone.core.model.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FingerprintTest {
    private val role = Role.values().first()

    @Test
    fun stableAcrossTinyPixelJitter() {
        val a = Fingerprint.of(1, Rect(100, 200, 300, 260), role, "发送", null, 1080, 2400)
        val b = Fingerprint.of(1, Rect(101, 201, 301, 261), role, "发送", null, 1080, 2400)
        assertEquals(a, b)
    }

    @Test
    fun textAndCheckedChangeTheFingerprint() {
        val base = Fingerprint.of(1, Rect(0, 0, 100, 100), role, "开", false, 1080, 2400)
        assertNotEquals(base, Fingerprint.of(1, Rect(0, 0, 100, 100), role, "关", false, 1080, 2400))
        assertNotEquals(base, Fingerprint.of(1, Rect(0, 0, 100, 100), role, "开", true, 1080, 2400))
    }

    @Test
    fun parseRoundTrips() {
        val fp = Fingerprint.of(3, Rect(10, 20, 30, 40), role, "确定", true, 1080, 2400)
        val parts = Fingerprint.parse(fp)!!
        assertEquals(3, parts.windowId)
        assertEquals(4, parts.bounds.size)
    }
}
