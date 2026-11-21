package online.timefiles.vantaloom.phone.core.observe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskingTest {
    @Test
    fun luhnValidCardIsMasked() {
        val r = Masking.mask("卡号 4111 1111 1111 1111 已绑定")
        assertTrue(r.masked)
        assertFalse(r.text.contains("4111 1111"))
        assertTrue(r.text.contains("****1111"))
    }

    @Test
    fun luhnInvalidNumberIsKept() {
        val r = Masking.mask("订单 4111111111111112")
        assertFalse(r.masked)
        assertEquals("订单 4111111111111112", r.text)
    }

    @Test
    fun idCardIsMasked() {
        // GB 11643 校验位正确的示例号码。
        val r = Masking.mask("身份证 11010519491231002X")
        assertTrue(r.masked)
        assertTrue(r.text.endsWith("****002X"))
    }

    @Test
    fun shortTextFastPath() {
        assertFalse(Masking.mask("13800138000").masked)
        assertFalse(Masking.mask(null).masked)
        assertEquals("", Masking.mask("").text)
    }
}
