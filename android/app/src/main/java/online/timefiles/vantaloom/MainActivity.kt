package online.timefiles.vantaloom

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Single-Activity WebView shell. It serves the compiled Next.js export from
 * assets over a vantaloom.localhost origin (WebViewAssetLoader) and injects
 * window.__loomBridge, backed by the gomobile loomnet facade, so the web client
 * can bring up the overlay node and drive a chosen peer's runtime through the
 * loopback proxy.
 *
 * Origin choice is load-bearing: the runtime's CORS only allows loopback /
 * *.localhost origins (isAllowedLocalOrigin), so the page MUST be served from a
 * *.localhost host — exactly as the retired Tauri shell used http://tauri.localhost.
 *
 * Display: edge-to-edge. The WebView is laid out full-bleed; the safe areas
 * (status bar / gesture-nav bar / display cutout / IME) are yielded back as
 * WebView padding from the insets listener, and the exposed strips take the
 * WebView's background color — which the web pushes via __loomBridge.setChrome
 * so they always match the page theme (the old fixed theme left a BLACK
 * navigation-bar strip on full-screen phones).
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()

        // Edge-to-edge: we manage the safe areas ourselves (insets listener below).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Without this some OEMs draw a translucent scrim over the nav area.
            window.isNavigationBarContrastEnforced = false
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(LOOM_DOMAIN)
            .setHttpAllowed(true) // http://vantaloom.localhost — a *.localhost secure context
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this)
        setContentView(webView)
        WebView.setWebContentsDebuggingEnabled(true)

        // Initial chrome: match the launch theme (light) until the web reports its
        // real theme through setChrome.
        applyChrome("#ffffff", dark = false)

        // Safe areas → WebView padding. The page viewport = safe area, so no web
        // layout ever hides under the bars; the padded strips show the WebView
        // background (kept theme-colored by applyChrome). IME included: with
        // decorFitsSystemWindows(false) the window no longer auto-resizes for the
        // keyboard, so the bottom inset takes the larger of nav bar vs keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(webView) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            allowContentAccess = false
            allowFileAccess = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Fallback injection for WebViews without DOCUMENT_START_SCRIPT.
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view.evaluateJavascript(BRIDGE_SHIM, null)
                }
            }
        }

        // Native bridge: primitive-only methods the JS shim adapts (contract v2 —
        // see loom-bridge.ts; the TS side owns the promise registry).
        webView.addJavascriptInterface(
            LoomJsBridge(
                applicationContext,
                evalJs = { js -> webView.post { webView.evaluateJavascript(js, null) } },
                onChrome = { color, dark -> applyChrome(color, dark) },
            ),
            "__loomNative",
        )

        // Guaranteed-early injection so window.__loomBridge exists before any page
        // script runs (preferred over onPageStarted).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, BRIDGE_SHIM, setOf("*"))
        }

        if (savedInstanceState == null) {
            webView.loadUrl("http://$LOOM_DOMAIN/index.html")
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    /**
     * Color the safe-area strips + flip system-bar icon appearance to match the
     * web theme. Called from the web (native-chrome.ts → __loomBridge.setChrome)
     * on mount and on every theme flip; also once at startup with the launch
     * default. Any thread.
     */
    private fun applyChrome(cssColor: String, dark: Boolean) {
        val fallback = if (dark) 0xFF0A0A0A.toInt() else Color.WHITE
        val color = parseCssColor(cssColor) ?: fallback
        runOnUiThread {
            if (!::webView.isInitialized) return@runOnUiThread
            webView.setBackgroundColor(color)
            window.setBackgroundDrawable(ColorDrawable(color))
            val controller = WindowCompat.getInsetsController(window, webView)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        // Deliberately do NOT stop the overlay node here: the foreground service
        // keeps it alive across Activity teardown (config change / warm restart);
        // the node is torn down only via __loomBridge.stop().
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    companion object {
        private const val LOOM_DOMAIN = "vantaloom.localhost"
        private const val REQ_NOTIF = 100

        /**
         * Parse a CSS color the web reports: "#rgb", "#rrggbb", "rgb(r, g, b)" or
         * "rgba(r, g, b, a)" (alpha ignored — the strip must be opaque). Null on
         * anything else so the caller falls back to a sane theme constant.
         */
        internal fun parseCssColor(raw: String): Int? {
            val s = raw.trim()
            if (s.startsWith("#")) {
                val hex = s.substring(1)
                return try {
                    when (hex.length) {
                        3 -> Color.parseColor("#" + hex.map { "$it$it" }.joinToString(""))
                        6, 8 -> Color.parseColor(s)
                        else -> null
                    }
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            val m = Regex("""rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)""").find(s) ?: return null
            val (r, g, b) = m.destructured
            val ri = r.toIntOrNull() ?: return null
            val gi = g.toIntOrNull() ?: return null
            val bi = b.toIntOrNull() ?: return null
            if (ri > 255 || gi > 255 || bi > 255) return null
            return Color.rgb(ri, gi, bi)
        }

        /**
         * Bridge contract v2 (keep in lockstep with loom-bridge.ts, which owns the
         * ONLY promise registry): this shim is a THIN argument-order adapter over
         * window.__loomNative — async methods take a trailing callbackId that
         * native fulfils by evaluating window.__loomResolve(id, payloadJson) /
         * window.__loomReject(id, message). v1 double-wrapped (the shim held its
         * own registry + a (callId, ok, payload) resolver the web side clobbered),
         * so every async call timed out while native had actually succeeded.
         */
        private const val BRIDGE_SHIM = """
(function () {
  if (window.__loomBridge) return;
  var N = window.__loomNative;
  if (!N) return;
  window.__loomBridge = {
    isNative: function () { return true; },
    deviceId: function () { return N.deviceId(); },
    loopbackPort: function () { return N.loopbackPort(); },
    statusJSON: function () { return N.statusJSON(); },
    setToken: function (t) { N.setToken(t); },
    setChrome: function (c, d) { N.setChrome(String(c || ""), !!d); },
    startNode: function (hubBaseUrl, hubToken, machineId, callbackId) {
      N.startNode(callbackId, hubBaseUrl, hubToken, machineId || "");
    },
    connect: function (machineId, callbackId) { N.connect(callbackId, machineId); },
    disconnect: function (callbackId) { N.disconnect(callbackId); },
    stop: function (callbackId) { N.stopNode(callbackId); }
  };
})();
"""
    }
}
