package online.timefiles.vantaloom

import online.timefiles.mobile.Bridge
import online.timefiles.mobile.Mobile

/**
 * Process-lifetime holder for the single overlay node (the gomobile AAR Bridge).
 *
 * The node must outlive any single Activity instance — the WebView (and its
 * LoomJsBridge) is recreated on configuration changes / process warm restarts,
 * but the Go node keeps running (kept alive by the foreground service). Holding
 * the Bridge here, rather than as an Activity field, also prevents the gomobile
 * finalizer from releasing the Go-side object if the Java wrapper were collected.
 */
object Loom {
    @Volatile
    private var bridge: Bridge? = null
    @Volatile
    private var started = false

    /** Returns the node bridge, creating it on first use. */
    @Synchronized
    fun ensure(): Bridge {
        bridge?.let { return it }
        val b = Mobile.newBridge()
        bridge = b
        return b
    }

    /** The node bridge if it has been created, else null. */
    fun get(): Bridge? = bridge

    /** Starts (or idempotently reuses) the overlay and records FGS ownership. */
    @Synchronized
    fun startNode(dataDir: String, hubBaseUrl: String, machineId: String, hubToken: String) {
        ensure().startNode(dataDir, hubBaseUrl, machineId, hubToken)
        started = true
    }

    fun isStarted(): Boolean = started

    /**
     * Stops the overlay node (loopback proxy, sessions, Hub client). The Bridge
     * instance is retained and can be restarted with StartNode again.
     */
    @Synchronized
    fun stop() {
        try {
            bridge?.stop()
        } finally {
            started = false
        }
    }
}
