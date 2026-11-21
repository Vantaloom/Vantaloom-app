package online.timefiles.vantaloom.phone.core.mcp

import online.timefiles.vantaloom.phone.core.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRpcTest {
    private fun obj(s: String?): Map<*, *> = Json.parse(s!!) as Map<*, *>
    private fun code(resp: Map<*, *>): Long = ((resp["error"] as Map<*, *>)["code"] as Number).toLong()

    @Test
    fun resultEchoesId() {
        val r = obj(JsonRpc.dispatch("""{"jsonrpc":"2.0","id":7,"method":"ping","params":{}}""") { "pong" })
        assertEquals(7L, (r["id"] as Number).toLong())
        assertEquals("pong", r["result"])
    }

    @Test
    fun parseErrorHasNullId() {
        val r = obj(JsonRpc.dispatch("{not json") { error("unreachable") })
        assertEquals(-32700L, code(r))
        assertTrue(r.containsKey("id") && r["id"] == null)
    }

    @Test
    fun notificationGetsNoResponse() {
        var seen = false
        val r = JsonRpc.dispatch("""{"jsonrpc":"2.0","method":"notifications/initialized"}""") { seen = true; null }
        assertTrue(seen)
        assertNull(r)
    }

    @Test
    fun rpcExceptionMapsToError() {
        val r = obj(
            JsonRpc.dispatch("""{"jsonrpc":"2.0","id":"a","method":"x"}""") {
                throw JsonRpc.RpcException(-32601, "nope")
            },
        )
        assertEquals(-32601L, code(r))
        assertEquals("a", r["id"])
    }

    @Test
    fun internalErrorDoesNotLeakMessage() {
        val r = obj(
            JsonRpc.dispatch("""{"jsonrpc":"2.0","id":1,"method":"x"}""") {
                throw IllegalStateException("secret-token-123")
            },
        )
        assertEquals(-32603L, code(r))
        assertTrue(!(r["error"] as Map<*, *>)["message"].toString().contains("secret-token-123"))
    }

    @Test
    fun emptyBatchIsInvalid() {
        assertEquals(-32600L, code(obj(JsonRpc.dispatch("[]") { null })))
    }
}
