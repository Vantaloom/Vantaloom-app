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

    /**
     * Stops the overlay node (loopback proxy, sessions, Hub client). The Bridge
     * instance is retained and can be restarted with StartNode again.
     */
    @Synchronized
    fun stop() {
        bridge?.stop()
    }
}
