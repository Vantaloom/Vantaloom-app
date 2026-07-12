package online.timefiles.vantaloom

import android.content.Context
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The native half of window.__loomBridge (docs/loomnet-design.md §8.1), exposed to
 * the WebView via addJavascriptInterface as `window.__loomNative`. A thin JS shim
 * (injected at document start by MainActivity) wraps these primitive-only methods
 * into the async Promise API the web client consumes.
 *
 * Async methods take a callId and resolve/reject the JS promise by evaluating
 * `window.__loomResolve(callId, ok, payload)` back on the WebView. They run off the
 * WebView's JavaBridge thread on a single-thread executor so the blocking overlay
 * work never stalls the bridge; sync getters return directly.
 *
 * Deviation from the §8.1 sketch: startNode takes a THIRD arg, machineId — the
 * Hub-assigned machine.id (from device registration), which the overlay uses as
 * its mTLS certificate CN and which the Hub peer list keys on. The sketch omitted
 * it assuming native owns identity, but the Hub assigns machine.id at registration
 * (≠ the device SSAID), so the frontend must supply it.
 */
class LoomJsBridge(
    private val context: Context,
    private val evalJs: (String) -> Unit,
) {
    private val worker = Executors.newSingleThreadExecutor()

    @JavascriptInterface
    fun isNative(): Boolean = true

    /** The device hardware id (`android-<ssaid>`), for Hub registration. */
    @JavascriptInterface
    fun deviceId(): String = DeviceId.get(context)

    /** The loopback proxy port, or 0 before StartNode. */
    @JavascriptInterface
    fun loopbackPort(): Int = Loom.get()?.loopbackPort()?.toInt() ?: 0

    /** The connection status JSON ({state,path?,error?}); sync (in-memory). */
    @JavascriptInterface
    fun statusJSON(): String = Loom.get()?.statusJSON() ?: """{"state":"idle"}"""

    /** Rotate the Hub JWT used by the mini Hub client + relay + signaling WS. */
    @JavascriptInterface
    fun setToken(token: String) {
        Loom.get()?.setToken(token)
    }

    /**
     * Start the overlay node + loopback proxy. Brings up the foreground service so
     * the node survives backgrounding. Idempotent (a second call while running is a
     * no-op inside the facade).
     */
    @JavascriptInterface
    fun startNode(callId: String, hubBaseUrl: String, hubToken: String, machineId: String) {
        worker.execute {
            try {
                LoomForegroundService.start(context)
                val dataDir = context.filesDir.absolutePath
                Loom.ensure().startNode(dataDir, hubBaseUrl, machineId, hubToken)
                resolve(callId, true, "null")
            } catch (e: Throwable) {
                resolve(callId, false, quote(e.message ?: "startNode failed"))
            }
        }
    }

    /**
     * Point the loopback at a peer and warm the overlay dial. Resolves with
     * {localUrl:"http://127.0.0.1:<port>"} — the base the web client aims its
     * local-API calls at (setRuntimeTarget).
     */
    @JavascriptInterface
    fun connect(callId: String, machineId: String) {
        worker.execute {
            try {
                val b = Loom.get() ?: throw IllegalStateException("node not started")
                b.connect(machineId)
                val port = b.loopbackPort().toInt()
                val payload = JSONObject().put("localUrl", "http://127.0.0.1:$port").toString()
                resolve(callId, true, payload)
            } catch (e: Throwable) {
                resolve(callId, false, quote(e.message ?: "connect failed"))
            }
        }
    }

    /** Clear the loopback target; the node + cached sessions stay up. */
    @JavascriptInterface
    fun disconnect(callId: String) {
        worker.execute {
            try {
                Loom.get()?.disconnect()
                resolve(callId, true, "null")
            } catch (e: Throwable) {
                resolve(callId, false, quote(e.message ?: "disconnect failed"))
            }
        }
    }

    /** Tear the node down and stop the foreground service. */
    @JavascriptInterface
    fun stopNode(callId: String) {
        worker.execute {
            try {
                Loom.get()?.stop()
                LoomForegroundService.stop(context)
                resolve(callId, true, "null")
            } catch (e: Throwable) {
                resolve(callId, false, quote(e.message ?: "stop failed"))
            }
        }
    }

    /**
     * Resolve/reject the JS promise for callId. payloadExpr must be a valid JS
     * expression: "null" (void), a JSON object literal (success value), or a
     * JSON-quoted string (error message).
     */
    private fun resolve(callId: String, ok: Boolean, payloadExpr: String) {
        val js = "window.__loomResolve(${quote(callId)}, $ok, $payloadExpr)"
        evalJs(js)
    }

    // JSONObject.quote yields a properly escaped JSON string literal, which is also
    // a valid JS string literal.
    private fun quote(s: String): String = JSONObject.quote(s)
}
