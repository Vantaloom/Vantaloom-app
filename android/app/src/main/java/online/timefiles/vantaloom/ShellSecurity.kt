package online.timefiles.vantaloom

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Security-critical constants shared by the privileged WebView shell. */
internal object ShellSecurity {
    const val appHost = "vantaloom.localhost"
    const val appOrigin = "http://$appHost"
    const val documentStartOriginRule = appOrigin

    private val externallyHandledSchemes = setOf("http", "https", "mailto", "tel")
    private val secureRandom = SecureRandom()

    fun isTrustedAppUrl(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        if (!uri.scheme.equals("http", ignoreCase = true)) return false
        if (!uri.host.equals(appHost, ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        return uri.port == -1 || uri.port == 80
    }

    fun canOpenExternally(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val scheme = runCatching { URI(raw).scheme?.lowercase() }.getOrNull() ?: return false
        return scheme in externallyHandledSchemes
    }

    /**
     * A per-launch secret gating the privileged native bridge methods. It is
     * templated ONLY into the origin-scoped bridge shim (which is injected into
     * the app frame alone), so an untrusted preview iframe — which shares the raw
     * window.__loomNative object but never receives the shim — cannot supply it.
     * The native side rejects identity/capability calls that don't present it.
     */
    fun newBridgeSecret(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        val hex = CharArray(bytes.size * 2)
        val digits = "0123456789abcdef"
        bytes.forEachIndexed { index, value ->
            val unsigned = value.toInt() and 0xff
            hex[index * 2] = digits[unsigned ushr 4]
            hex[index * 2 + 1] = digits[unsigned and 0x0f]
        }
        bytes.fill(0)
        return String(hex)
    }

    /**
     * True for a SUBFRAME (iframe) URL the shell may load in-place for a preview:
     * a loopback or private-LAN http(s) document — the local runtime, an agent
     * dev server, or a design-preview proxy. Everything else (a real website,
     * javascript:/data:/intent: URLs) is still externalized / blocked. A previewed
     * document runs in its own (non-app) origin, so it never receives the bridge
     * shim and its access to window.__loomNative is neutered by the guard script.
     */
    fun isPreviewableSubframeUrl(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (uri.userInfo != null) return false
        val host = uri.host?.lowercase() ?: return false
        return isLoopbackOrPrivateHost(host)
    }

    private fun isLoopbackOrPrivateHost(host: String): Boolean {
        if (host == "localhost" || host == "::1" || host == "[::1]") return true
        val octets = host.split('.').map { it.toIntOrNull() }
        if (octets.size == 4 && octets.all { it != null && it in 0..255 }) {
            val a = octets[0]!!
            val b = octets[1]!!
            if (a == 127) return true // 127.0.0.0/8 loopback
            if (a == 10) return true // 10.0.0.0/8
            if (a == 192 && b == 168) return true // 192.168.0.0/16
            if (a == 172 && b in 16..31) return true // 172.16.0.0/12
        }
        return false
    }

    fun isDocumentNavigation(isMainFrame: Boolean, headers: Map<String, String>): Boolean {
        if (isMainFrame) return true
        val mode = headers.caseInsensitiveValue("Sec-Fetch-Mode")
        val destination = headers.caseInsensitiveValue("Sec-Fetch-Dest")
        val accept = headers.caseInsensitiveValue("Accept").orEmpty()
        return mode.equals("navigate", ignoreCase = true) ||
            destination.equals("document", ignoreCase = true) ||
            destination.equals("frame", ignoreCase = true) ||
            destination.equals("iframe", ignoreCase = true) ||
            accept.split(',').any { mediaRange ->
                val mediaType = mediaRange.substringBefore(';').trim()
                mediaType.equals("text/html", ignoreCase = true) ||
                    mediaType.equals("application/xhtml+xml", ignoreCase = true)
            }
    }

    private fun Map<String, String>.caseInsensitiveValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** Ephemeral authentication contract for the child runtime bound to loopback. */
internal object LoopbackAuth {
    const val bearerEnvironmentVariable = "VANTALOOM_LOOPBACK_BEARER_TOKEN"
    const val capabilityEnvironmentVariable = "VANTALOOM_LOOPBACK_CAPABILITY_TOKEN"
    const val credentialsFileEnvironmentVariable = "VANTALOOM_LOOPBACK_CREDENTIALS_FILE"
    const val queryParameter = "__vantaloom_loopback_token"
    const val expirationQueryParameter = "__vantaloom_loopback_exp"
    private const val tokenBytes = 32
    private val secureRandom = SecureRandom()
    private val hex = "0123456789abcdef".toCharArray()

    @Synchronized
    fun newToken(): String {
        val bytes = ByteArray(tokenBytes)
        secureRandom.nextBytes(bytes)
        val encoded = CharArray(tokenBytes * 2)
        bytes.forEachIndexed { index, value ->
            val unsigned = value.toInt() and 0xff
            encoded[index * 2] = hex[unsigned ushr 4]
            encoded[index * 2 + 1] = hex[unsigned and 0x0f]
        }
        bytes.fill(0)
        return String(encoded)
    }
}

/** Produces path/query-scoped, expiring URLs without exposing the capability key to JavaScript. */
internal object LoopbackCapabilitySigner {
    private const val MAX_URL_LENGTH = 32 * 1024
    private const val CAPABILITY_TTL_SECONDS = 12 * 60 * 60L

    fun authorize(
        raw: String,
        port: Int,
        capabilityKey: String,
        nowUnixSeconds: Long = System.currentTimeMillis() / 1000,
    ): String? {
        if (raw.isBlank() || raw.length > MAX_URL_LENGTH || port !in 1..65535) return null
        if (!capabilityKey.matches(Regex("[0-9a-f]{64}"))) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "ws") return null
        if (uri.host != "127.0.0.1" || uri.port != port || uri.userInfo != null) return null
        val rawPath = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        if (!rawPath.startsWith('/')) return null
        val rawQuery = uri.rawQuery?.takeIf { it.isNotEmpty() }
        if (containsReservedQueryParameter(rawQuery)) return null

        val requestTarget = rawPath + (rawQuery?.let { "?$it" } ?: "")
        val expires = runCatching {
            Math.addExact(nowUnixSeconds, CAPABILITY_TTL_SECONDS)
        }.getOrNull() ?: return null
        val signature = hmacHex(capabilityKey, "$requestTarget\n$expires") ?: return null
        val separator = if (rawQuery == null) "?" else "&"
        return "$scheme://127.0.0.1:$port$requestTarget$separator" +
            "${LoopbackAuth.expirationQueryParameter}=$expires&" +
            "${LoopbackAuth.queryParameter}=$signature"
    }

    private fun containsReservedQueryParameter(rawQuery: String?): Boolean {
        if (rawQuery == null) return false
        return rawQuery.split('&').any { component ->
            val rawName = component.substringBefore('=')
            val name = runCatching {
                URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())
            }.getOrNull() ?: return true
            name == LoopbackAuth.queryParameter || name == LoopbackAuth.expirationQueryParameter
        }
    }

    private fun hmacHex(keyHex: String, payload: String): String? = runCatching {
        val key = ByteArray(32)
        for (index in key.indices) {
            key[index] = keyHex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val signature = mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
        key.fill(0)
        val encoded = signature.joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        signature.fill(0)
        encoded
    }.getOrNull()
}
