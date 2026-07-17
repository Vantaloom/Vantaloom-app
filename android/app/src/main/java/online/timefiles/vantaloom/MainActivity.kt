package online.timefiles.vantaloom

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File

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
 * Display: edge-to-edge. The window is full-bleed; the safe areas (status bar /
 * gesture-nav bar / display cutout / IME) are yielded back as PADDING ON A ROOT
 * FrameLayout that wraps the WebView — NOT on the WebView itself: WebView does
 * not reliably honor setPadding (0.14.6 padded the WebView and real devices
 * still rendered the page under the status bar, leaving the top bar covered
 * and unclickable). The exposed strips take the root's background color —
 * which the web pushes via __loomBridge.setChrome so they always match the
 * page theme (the old fixed theme left a BLACK navigation-bar strip on
 * full-screen phones).
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var bridge: LoomJsBridge

    // In-flight image pick (composer 加号键). One at a time; the classic
    // startActivityForResult flow is used because this Activity is a bare
    // android.app.Activity (no androidx.activity Result API available).
    private var pendingPickCallId: String? = null
    private var pendingCameraUri: Uri? = null
    private var pendingCameraFile: File? = null

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
        root = FrameLayout(this)
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)
        WebView.setWebContentsDebuggingEnabled(true)

        // Initial chrome: match the launch theme (light) until the web reports its
        // real theme through setChrome.
        applyChrome("#ffffff", dark = false)

        // Safe areas → ROOT padding (the WebView shrinks inside it). The page
        // viewport = safe area, so no web layout ever hides under the bars —
        // padding the WebView itself does NOT work (WebView ignores padding on
        // real devices; the 0.14.6 build left the top bar under the status bar,
        // unclickable). The padded strips show the root background (kept
        // theme-colored by applyChrome). IME included: with
        // decorFitsSystemWindows(false) the window no longer auto-resizes for the
        // keyboard, so the bottom inset takes the larger of nav bar vs keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
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
        bridge = LoomJsBridge(
            applicationContext,
            evalJs = { js -> webView.post { webView.evaluateJavascript(js, null) } },
            onChrome = { color, dark -> applyChrome(color, dark) },
            onPickImages = { callId, source ->
                runOnUiThread { launchImagePick(callId, source) }
            },
        )
        webView.addJavascriptInterface(bridge, "__loomNative")

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
            // The ROOT paints the safe-area strips (it carries the insets
            // padding); the WebView + window get the same color so there is no
            // flash of mismatch during load/resize.
            if (::root.isInitialized) root.setBackgroundColor(color)
            webView.setBackgroundColor(color)
            window.setBackgroundDrawable(ColorDrawable(color))
            val controller = WindowCompat.getInsetsController(window, webView)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    /**
     * 拉起图片选择（composer 加号键）。source="camera" → ACTION_IMAGE_CAPTURE
     * 写 FileProvider 缓存文件（未声明 CAMERA 权限——相机 intent 无需权限，一旦
     * 声明反而必须先授权）；其余 → Android 13+ 系统 Photo Picker（应用内底部
     * 弹层、免存储权限），旧系统回退 ACTION_GET_CONTENT。
     */
    private fun launchImagePick(callId: String, source: String) {
        if (pendingPickCallId != null) {
            bridge.rejectFromShell(callId, "已有图片选择进行中")
            return
        }
        pendingPickCallId = callId
        try {
            if (source == "camera") {
                val dir = File(cacheDir, "camera").apply { mkdirs() }
                val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                pendingCameraFile = file
                pendingCameraUri = uri
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    .addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_PICK_CAMERA)
            } else {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Intent(MediaStore.ACTION_PICK_IMAGES)
                        .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, LoomJsBridge.pickMaxImages)
                } else {
                    Intent(Intent.ACTION_GET_CONTENT)
                        .setType("image/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_PICK_GALLERY)
            }
        } catch (e: Throwable) {
            pendingPickCallId = null
            pendingCameraUri = null
            pendingCameraFile?.delete()
            pendingCameraFile = null
            bridge.rejectFromShell(callId, e.message ?: "无法打开图片选择器")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_PICK_CAMERA && requestCode != REQ_PICK_GALLERY) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val callId = pendingPickCallId ?: return
        pendingPickCallId = null
        val cameraUri = pendingCameraUri
        val cameraFile = pendingCameraFile
        pendingCameraUri = null
        pendingCameraFile = null

        if (resultCode != RESULT_OK) {
            // 用户取消 = 空结果（正常路径,不是错误）。
            cameraFile?.delete()
            bridge.resolveFromShell(callId, """{"images":[]}""")
            return
        }
        val uris = mutableListOf<Uri>()
        if (requestCode == REQ_PICK_CAMERA) {
            if (cameraUri != null) uris.add(cameraUri)
        } else {
            val clip = data?.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            } else {
                data?.data?.let { uris.add(it) }
            }
        }
        // 解码/降采样/base64 在桥 worker 线程做，完成后清相机临时文件。
        bridge.encodeImagesAsync(callId, uris) { cameraFile?.delete() }
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
        private const val REQ_PICK_CAMERA = 101
        private const val REQ_PICK_GALLERY = 102

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
    stop: function (callbackId) { N.stopNode(callbackId); },
    pickImages: function (source, callbackId) {
      N.pickImages(callbackId, String(source || "gallery"));
    },
    startLocalRuntime: function (callbackId) { N.startLocalRuntime(callbackId); }
  };
})();
"""
    }
}
