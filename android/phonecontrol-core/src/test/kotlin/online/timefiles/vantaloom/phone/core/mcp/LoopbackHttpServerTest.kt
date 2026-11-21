package online.timefiles.vantaloom.phone.core.mcp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket

class LoopbackHttpServerTest {
    private val token = "t".repeat(43)
    private var lastCaller: String? = "unset"
    private var calls = 0
    private lateinit var server: LoopbackHttpServer
    private var port = -1

    @Before
    fun setUp() {
        server = LoopbackHttpServer(token, { body, caller ->
            calls++
            lastCaller = caller
            "{\"echo\":$body}"
        })
        port = server.start()
    }

    @After
    fun tearDown() = server.stop()

    private fun raw(request: String): Int {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().apply { write(request.toByteArray()); flush() }
            val status = s.getInputStream().bufferedReader().readLine()
            return status.split(' ')[1].toInt()
        }
    }

    private fun post(
        host: String = "127.0.0.1:$port",
        auth: String? = "Bearer $token",
        extra: String = "",
        path: String = "/mcp",
        body: String = "{}",
    ): Int {
        val bytes = body.toByteArray()
        val sb = StringBuilder("POST $path HTTP/1.1\r\nHost: $host\r\nContent-Type: application/json\r\n")
        if (auth != null) sb.append("Authorization: $auth\r\n")
        sb.append(extra)
        sb.append("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").append(body)
        return raw(sb.toString())
    }

    @Test
    fun bindsLoopbackOnly() {
        assertTrue(port > 0)
        assertEquals(port, server.localPort)
    }

    @Test
    fun validRequestReachesHandler() {
        assertEquals(200, post())
        assertEquals(1, calls)
        assertNull(lastCaller)
    }

    @Test
    fun missingOrWrongBearerIs401() {
        assertEquals(401, post(auth = null))
        assertEquals(401, post(auth = "Bearer wrong"))
        assertEquals(401, post(auth = token))
        assertEquals(0, calls)
    }

    @Test
    fun browserOriginIsRefused() {
        assertEquals(403, post(extra = "Origin: http://evil.example\r\n"))
        assertEquals(403, post(extra = "Origin: null\r\n"))
        assertEquals(0, calls)
    }

    @Test
    fun foreignHostIsRefused() {
        assertEquals(421, post(host = "evil.example:$port"))
        assertEquals(421, post(host = "127.0.0.1:${port + 1}"))
        assertEquals(0, calls)
    }

    @Test
    fun localhostHostIsAccepted() {
        assertEquals(200, post(host = "localhost:$port"))
    }

    @Test
    fun unknownPathIs404WithoutToken() {
        assertEquals(404, post(path = "/other", auth = null))
        assertEquals(0, calls)
    }

    @Test
    fun chunkedBodyIsRejected() {
        val code = raw(
            "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $token\r\n" +
                "Content-Type: application/json\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n2\r\n{}\r\n0\r\n\r\n",
        )
        assertEquals(411, code)
        assertEquals(0, calls)
    }

    @Test
    fun remoteCallerHeaderIsForwarded() {
        assertEquals(200, post(extra = "${LoopbackHttpServer.REMOTE_CALLER_HEADER}: desk-01\r\n"))
        assertEquals("desk-01", lastCaller)
    }
}
