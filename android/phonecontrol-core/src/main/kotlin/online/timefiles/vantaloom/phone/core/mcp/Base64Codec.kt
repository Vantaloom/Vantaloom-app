package online.timefiles.vantaloom.phone.core.mcp

/**
 * Base64 编码（标准 / URL 安全）。
 *
 * 为什么不用 java.util.Base64：它在 Android API 26 才有，而 APK minSdk 是 24；android.util.Base64 又不能在
 * core 里引用。只需要编码（截图、token），十几行就够。
 */
object Base64Codec {
    private val STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray()
    private val URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray()

    /** 标准 Base64，带填充（MCP image.data）。 */
    fun encode(data: ByteArray): String = encode(data, STD, pad = true)

    /** base64url 无填充（token）。 */
    fun encodeUrlNoPad(data: ByteArray): String = encode(data, URL, pad = false)

    private fun encode(data: ByteArray, table: CharArray, pad: Boolean): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val n = (data[i].toInt() and 0xff shl 16) or (data[i + 1].toInt() and 0xff shl 8) or (data[i + 2].toInt() and 0xff)
            sb.append(table[n ushr 18 and 63]).append(table[n ushr 12 and 63]).append(table[n ushr 6 and 63]).append(table[n and 63])
            i += 3
        }
        val rest = data.size - i
        if (rest == 1) {
            val n = data[i].toInt() and 0xff shl 16
            sb.append(table[n ushr 18 and 63]).append(table[n ushr 12 and 63])
            if (pad) sb.append("==")
        } else if (rest == 2) {
            val n = (data[i].toInt() and 0xff shl 16) or (data[i + 1].toInt() and 0xff shl 8)
            sb.append(table[n ushr 18 and 63]).append(table[n ushr 12 and 63]).append(table[n ushr 6 and 63])
            if (pad) sb.append('=')
        }
        return sb.toString()
    }
}
