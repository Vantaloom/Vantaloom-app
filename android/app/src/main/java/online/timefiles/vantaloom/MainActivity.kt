package online.timefiles.vantaloom

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
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
import java.io.ByteArrayInputStream
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
            .setDomain(ShellSecurity.appHost)
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
        val isDebuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        WebView.setWebContentsDebuggingEnabled(isDebuggable)

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
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val raw = request.url.toString()
                if (ShellSecurity.isTrustedAppUrl(raw)) return false
                // The privileged bridge must never accompany an external document,
                // including an untrusted iframe. Main-frame links are handed to a
                // separate app; subframe navigations fail closed.
                if (request.isForMainFrame) openExternalNavigation(raw)
                return true
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                if (ShellSecurity.isTrustedAppUrl(request.url.toString())) {
                    return assetLoader.shouldInterceptRequest(request.url)
                }
                // Some WebView versions do not consistently invoke
                // shouldOverrideUrlLoading for subframes. Block navigation-shaped
                // requests here too, while leaving CORS fetches/images untouched.
                if (ShellSecurity.isDocumentNavigation(request.isForMainFrame, request.requestHeaders)) {
                    return WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        403,
                        "Blocked external document",
                        emptyMap(),
                        ByteArrayInputStream(ByteArray(0)),
                    )
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!ShellSecurity.isTrustedAppUrl(url)) {
                    view.stopLoading()
                    openExternalNavigation(url)
                    return
                }
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
            onPickFiles = { callId ->
                runOnUiThread { launchFilePick(callId) }
            },
            onPickFolder = { callId ->
                runOnUiThread { launchFolderPick(callId) }
            },
        )
        webView.addJavascriptInterface(bridge, "__loomNative")

        // Guaranteed-early injection so window.__loomBridge exists before any page
        // script runs (preferred over onPageStarted).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                BRIDGE_SHIM,
                setOf(ShellSecurity.documentStartOriginRule),
            )
        }

        if (savedInstanceState == null) {
            loadBundledApp()
        } else {
            val restored = webView.restoreState(savedInstanceState)
            if (!ShellSecurity.isTrustedAppUrl(restored?.currentItem?.url)) {
                loadBundledApp()
            }
        }
    }

    private fun loadBundledApp() {
        webView.loadUrl("${ShellSecurity.appOrigin}/index.html")
    }

    private fun openExternalNavigation(raw: String?) {
        if (!ShellSecurity.canOpenExternally(raw)) return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(raw)))
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

    /**
     * 文件/文件夹选择的「所有文件访问」权限门（0.14.31）：未授权时拉系统授权页
     * 并 resolve {"needPermission":true}（前端提示后用户授权完重试）。同 UID 的
     * 运行时子进程共享该授权，draft 导入由运行时对真实路径原生复制。
     */
    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun launchAllFilesAccessRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            } catch (_: Throwable) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
                REQ_STORAGE_LEGACY,
            )
        }
    }

    private fun launchFilePick(callId: String) {
        if (pendingPickCallId != null) {
            bridge.rejectFromShell(callId, "已有选择进行中")
            return
        }
        if (!hasAllFilesAccess()) {
            launchAllFilesAccessRequest()
            bridge.resolveFromShell(callId, """{"needPermission":true}""")
            return
        }
        pendingPickCallId = callId
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addCategory(Intent.CATEGORY_OPENABLE)
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK_FILES)
        } catch (e: Throwable) {
            pendingPickCallId = null
            bridge.rejectFromShell(callId, e.message ?: "无法打开文件选择器")
        }
    }

    private fun launchFolderPick(callId: String) {
        if (pendingPickCallId != null) {
            bridge.rejectFromShell(callId, "已有选择进行中")
            return
        }
        if (!hasAllFilesAccess()) {
            launchAllFilesAccessRequest()
            bridge.resolveFromShell(callId, """{"needPermission":true}""")
            return
        }
        pendingPickCallId = callId
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PICK_FOLDER)
        } catch (e: Throwable) {
            pendingPickCallId = null
            bridge.rejectFromShell(callId, e.message ?: "无法打开文件夹选择器")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQ_PICK_CAMERA && requestCode != REQ_PICK_GALLERY &&
            requestCode != REQ_PICK_FILES && requestCode != REQ_PICK_FOLDER
        ) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val callId = pendingPickCallId ?: return
        pendingPickCallId = null
        val cameraUri = pendingCameraUri
        val cameraFile = pendingCameraFile
        pendingCameraUri = null
        pendingCameraFile = null

        if (requestCode == REQ_PICK_FILES) {
            if (resultCode != RESULT_OK) {
                bridge.resolveFromShell(callId, """{"files":[]}""")
                return
            }
            val uris = mutableListOf<Uri>()
            val clip = data?.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            } else {
                data?.data?.let { uris.add(it) }
            }
            bridge.materializeFilesAsync(callId, uris)
            return
        }
        if (requestCode == REQ_PICK_FOLDER) {
            val tree = data?.data
            if (resultCode != RESULT_OK || tree == null) {
                bridge.resolveFromShell(callId, """{"folder":null}""")
                return
            }
            bridge.walkFolderAsync(callId, tree)
            return
        }

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
        // work ends only through the bridge or the notification's explicit stop.
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("__loomNative")
            webView.stopLoading()
            webView.destroy()
        }
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
        private const val REQ_NOTIF = 100
        private const val REQ_PICK_CAMERA = 101
        private const val REQ_PICK_GALLERY = 102
        private const val REQ_PICK_FILES = 103
        private const val REQ_PICK_FOLDER = 104
        private const val REQ_STORAGE_LEGACY = 105

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
        private val BRIDGE_SHIM = """
(function () {
  if (window.__loomBridge) return;
  var N = window.__loomNative;
  if (!N) return;
  var localRuntimeAuth = null;

  function normalizedHttpOrigin(raw) {
    try {
      var url = new URL(String(raw), window.location.href);
      if (url.protocol === "ws:") url.protocol = "http:";
      if (url.protocol === "wss:") url.protocol = "https:";
      return url.origin;
    } catch (_) {
      return "";
    }
  }

  function isLocalRuntimeUrl(raw) {
    return !!localRuntimeAuth && normalizedHttpOrigin(raw) === localRuntimeAuth.origin;
  }

  function authenticatedStreamUrl(raw) {
    if (!isLocalRuntimeUrl(raw)) return raw;
    try {
      var signed = N.authorizeLocalRuntimeUrl(String(raw));
      return signed ? String(signed) : raw;
    } catch (_) {
      return raw;
    }
  }

  function hasLocalRuntimeQueryAuth(raw) {
    if (!isLocalRuntimeUrl(raw)) return false;
    try {
      var url = new URL(String(raw), window.location.href);
      return url.searchParams.has("${LoopbackAuth.expirationQueryParameter}") &&
        url.searchParams.has("${LoopbackAuth.queryParameter}");
    } catch (_) {
      return false;
    }
  }

  Object.defineProperty(window, "__loomInstallLocalRuntimeAuth", {
    configurable: false,
    enumerable: false,
    value: function (baseUrl, bearerToken) {
      try {
        var base = new URL(String(baseUrl));
        var normalizedBearer = String(bearerToken || "");
        if (base.protocol !== "http:" || base.hostname !== "127.0.0.1" || !base.port) return false;
        if (!/^[0-9a-f]{64}$/.test(normalizedBearer)) return false;
        localRuntimeAuth = {
          origin: base.origin,
          bearerToken: normalizedBearer
        };
        return true;
      } catch (_) {
        return false;
      }
    }
  });

  Object.defineProperty(window, "__loomClearLocalRuntimeAuth", {
    configurable: false,
    enumerable: false,
    value: function () { localRuntimeAuth = null; }
  });

  Object.defineProperty(window, "__loomAuthorizeLocalRuntimeURL", {
    configurable: false,
    enumerable: false,
    writable: false,
    value: function (rawUrl) { return authenticatedStreamUrl(rawUrl); }
  });

  // A same-session reload can restore the persisted local runtime target without
  // calling startLocalRuntime again. Ask native to re-install the still-live
  // process capability into this new document; no token is returned to callers.
  try {
    if (typeof N.restoreLocalRuntimeAuth === "function") N.restoreLocalRuntimeAuth();
  } catch (_) {}

  if (!window.__loomRuntimeAuthPatched) {
    Object.defineProperty(window, "__loomRuntimeAuthPatched", {
      configurable: false,
      enumerable: false,
      value: true
    });

    var nativeFetch = window.fetch.bind(window);
    window.fetch = function (input, init) {
      var request;
      try {
        request = new Request(input, init);
      } catch (error) {
        return Promise.reject(error);
      }
      if (!isLocalRuntimeUrl(request.url)) return nativeFetch(input, init);
      if (hasLocalRuntimeQueryAuth(request.url)) return nativeFetch(request);
      try {
        var headers = new Headers(request.headers);
        headers.set("Authorization", "Bearer " + localRuntimeAuth.bearerToken);
        return nativeFetch(new Request(request, { headers: headers }));
      } catch (error) {
        return Promise.reject(error);
      }
    };

    if (window.XMLHttpRequest) {
      var xhrUrls = new WeakMap();
      var nativeXhrOpen = XMLHttpRequest.prototype.open;
      var nativeXhrSend = XMLHttpRequest.prototype.send;
      XMLHttpRequest.prototype.open = function (method, url) {
        xhrUrls.set(this, String(url));
        return nativeXhrOpen.apply(this, arguments);
      };
      XMLHttpRequest.prototype.send = function () {
        if (isLocalRuntimeUrl(xhrUrls.get(this)) && !hasLocalRuntimeQueryAuth(xhrUrls.get(this))) {
          this.setRequestHeader("Authorization", "Bearer " + localRuntimeAuth.bearerToken);
        }
        return nativeXhrSend.apply(this, arguments);
      };
    }

    if (window.EventSource) {
      var NativeEventSource = window.EventSource;
      var AuthenticatedEventSource = function (url, config) {
        var authenticatedUrl = authenticatedStreamUrl(url);
        return arguments.length > 1
          ? new NativeEventSource(authenticatedUrl, config)
          : new NativeEventSource(authenticatedUrl);
      };
      AuthenticatedEventSource.prototype = NativeEventSource.prototype;
      try { Object.setPrototypeOf(AuthenticatedEventSource, NativeEventSource); } catch (_) {}
      window.EventSource = AuthenticatedEventSource;
    }

    if (window.WebSocket) {
      var NativeWebSocket = window.WebSocket;
      var AuthenticatedWebSocket = function (url, protocols) {
        var authenticatedUrl = authenticatedStreamUrl(url);
        return arguments.length > 1
          ? new NativeWebSocket(authenticatedUrl, protocols)
          : new NativeWebSocket(authenticatedUrl);
      };
      AuthenticatedWebSocket.prototype = NativeWebSocket.prototype;
      try { Object.setPrototypeOf(AuthenticatedWebSocket, NativeWebSocket); } catch (_) {}
      window.WebSocket = AuthenticatedWebSocket;
    }
  }

  // Android has no tab/window surface. Route safe window.open requests through
  // the top frame so WebViewClient can externalize them; never create a second
  // privileged WebView or allow javascript:/data:/intent: URLs.
  window.open = function (rawUrl) {
    try {
      if (rawUrl == null || String(rawUrl).trim() === "") return null;
      var targetUrl = new URL(String(rawUrl), window.location.href);
      if (["http:", "https:", "mailto:", "tel:"].indexOf(targetUrl.protocol) < 0) return null;
      window.top.location.assign(targetUrl.href);
    } catch (_) {}
    return null;
  };

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
    pickFiles: function (callbackId) { N.pickFiles(callbackId); },
    pickFolder: function (callbackId) { N.pickFolder(callbackId); },
    shareFile: function (path, name, callbackId) {
      N.shareFile(callbackId, String(path || ""), String(name || ""));
    },
    startLocalRuntime: function (callbackId) { N.startLocalRuntime(callbackId); },
    stopLocalRuntime: function (callbackId) { N.stopLocalRuntime(callbackId); }
  };
})();
""".trimIndent()
    }
}
