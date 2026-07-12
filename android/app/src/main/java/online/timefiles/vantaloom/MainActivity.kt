package online.timefiles.vantaloom

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
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
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermissionIfNeeded()

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(LOOM_DOMAIN)
            .setHttpAllowed(true) // http://vantaloom.localhost — a *.localhost secure context
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this)
        setContentView(webView)
        WebView.setWebContentsDebuggingEnabled(true)

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

        // Native bridge: primitive-only methods the JS shim wraps into Promises.
        webView.addJavascriptInterface(
            LoomJsBridge(applicationContext) { js -> webView.post { webView.evaluateJavascript(js, null) } },
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
         * The JS shim wrapping the primitive `window.__loomNative` methods into the
         * async window.__loomBridge contract (docs/loomnet-design.md §8.1). Async
         * calls carry a callId; native fulfils them via window.__loomResolve.
         */
        private const val BRIDGE_SHIM = """
(function () {
  if (window.__loomBridge) return;
  var N = window.__loomNative;
  if (!N) return;
  var pending = {};
  var seq = 0;
  window.__loomResolve = function (callId, ok, payload) {
    var p = pending[callId];
    if (!p) return;
    delete pending[callId];
    if (ok) p.resolve(payload);
    else p.reject(new Error(typeof payload === "string" ? payload : "native error"));
  };
  function call(method, args) {
    return new Promise(function (resolve, reject) {
      var id = "c" + (++seq);
      pending[id] = { resolve: resolve, reject: reject };
      try {
        N[method].apply(N, [id].concat(args || []));
      } catch (e) {
        delete pending[id];
        reject(e);
      }
    });
  }
  var tokenTimer = null;
  window.__loomBridge = {
    isNative: function () { return true; },
    deviceId: function () { return N.deviceId(); },
    loopbackPort: function () { return N.loopbackPort(); },
    startNode: function (hubBaseUrl, hubToken, machineId) {
      return call("startNode", [hubBaseUrl, hubToken, machineId || ""]);
    },
    connect: function (machineId) { return call("connect", [machineId]); },
    disconnect: function () { return call("disconnect", []); },
    stop: function () { return call("stopNode", []); },
    status: function () {
      try { return Promise.resolve(JSON.parse(N.statusJSON())); }
      catch (e) { return Promise.resolve({ state: "error", error: String(e) }); }
    },
    onToken: function (refresh) {
      var push = function () {
        try { var t = refresh(); if (t) N.setToken(t); } catch (e) {}
      };
      push();
      if (tokenTimer) clearInterval(tokenTimer);
      tokenTimer = setInterval(push, 30000);
    }
  };
})();
"""
    }
}
