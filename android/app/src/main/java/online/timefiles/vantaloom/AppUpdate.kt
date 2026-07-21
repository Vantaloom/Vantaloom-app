package online.timefiles.vantaloom

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * APK self-update from the PUBLIC GitHub Releases (Vantaloom/Vantaloom-app). The
 * APK build number is the CI run number, embedded both in versionCode
 * (`-PversionCode=<run>`) and the release tag (`apk-build<run>-<sha>`); a newer
 * run number ⇒ an update. The check hits `/releases/latest` (only the APK
 * workflow marks a release `--latest`, so it always resolves to an APK), streams
 * the asset into cache with progress, and hands the file to the system package
 * installer on an explicit user tap. Every CI build is signed with the same key,
 * so the download installs in place with no uninstall.
 */
internal object AppUpdate {
    private const val OWNER = "Vantaloom"
    private const val REPO = "Vantaloom-app"
    private const val LATEST_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val USER_AGENT = "Vantaloom-Android"
    private const val MAX_REDIRECTS = 5

    enum class Phase(val wire: String) {
        IDLE("idle"),
        CHECKING("checking"),
        UP_TO_DATE("upToDate"),
        DOWNLOADING("downloading"),
        READY("ready"),
        ERROR("error"),
    }

    data class State(
        val phase: Phase = Phase.IDLE,
        val currentBuild: Long = 0,
        val latestBuild: Long = 0,
        val latestName: String = "",
        val notes: String = "",
        val progress: Int = 0,
        val apkPath: String = "",
        val error: String = "",
    ) {
        fun toJson(): String = JSONObject()
            .put("phase", phase.wire)
            .put("currentBuild", currentBuild)
            .put("latestBuild", latestBuild)
            .put("latestName", latestName)
            .put("notes", notes)
            .put("progress", progress)
            .put("apkPath", apkPath)
            .put("error", error)
            .toString()
    }

    private val state = AtomicReference(State())

    fun status(context: Context): State {
        val current = state.get()
        if (current.currentBuild == 0L) {
            state.compareAndSet(current, current.copy(currentBuild = installedBuild(context)))
        }
        return state.get()
    }

    fun installedBuild(context: Context): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        PackageInfoCompat.getLongVersionCode(info)
    } catch (_: Throwable) {
        0L
    }

    /** Parse the CI run number out of an `apk-build<run>-<sha>` release tag. */
    fun parseBuildFromTag(tag: String?): Long {
        if (tag.isNullOrBlank()) return 0L
        val match = Regex("""apk-build(\d+)-""").find(tag) ?: return 0L
        return match.groupValues[1].toLongOrNull() ?: 0L
    }

    /** True when `latest` is a strictly newer build than the installed one. A
     *  fresh dev build (versionCode fallback 1) treats any real release as newer. */
    fun isUpdateAvailable(current: Long, latest: Long): Boolean = latest > 0 && latest > current

    /**
     * Blocking: query the latest release, and (autoDownload) fetch its APK asset
     * into cache. Call from a worker thread. Returns the resulting state. Never
     * throws — failures land as Phase.ERROR so the caller can surface a message.
     */
    fun check(context: Context, autoDownload: Boolean): State {
        val prev = state.get()
        if (prev.phase == Phase.CHECKING || prev.phase == Phase.DOWNLOADING) return prev
        val current = installedBuild(context)
        set(State(phase = Phase.CHECKING, currentBuild = current))
        try {
            val (code, body) = httpGetString(LATEST_URL)
            if (code != 200) {
                return set(state.get().copy(phase = Phase.ERROR, error = "GitHub 返回 HTTP $code"))
            }
            val release = JSONObject(body)
            val tag = release.optString("tag_name")
            val latest = parseBuildFromTag(tag)
            val name = release.optString("name").ifBlank { tag }
            val notes = release.optString("body").take(600)
            if (!isUpdateAvailable(current, latest)) {
                return set(
                    State(
                        phase = Phase.UP_TO_DATE,
                        currentBuild = current,
                        latestBuild = latest,
                        latestName = name,
                    )
                )
            }
            val assetUrl = firstApkAssetUrl(release)
                ?: return set(
                    state.get().copy(phase = Phase.ERROR, error = "该版本未附带 APK 安装包")
                )
            set(
                State(
                    phase = if (autoDownload) Phase.DOWNLOADING else Phase.READY,
                    currentBuild = current,
                    latestBuild = latest,
                    latestName = name,
                    notes = notes,
                )
            )
            if (!autoDownload) return state.get()
            val apk = download(context, assetUrl)
            return set(
                state.get().copy(
                    phase = Phase.READY,
                    progress = 100,
                    apkPath = apk.absolutePath,
                )
            )
        } catch (e: Throwable) {
            return set(state.get().copy(phase = Phase.ERROR, error = e.message ?: "检查更新失败"))
        }
    }

    private fun firstApkAssetUrl(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                val url = asset.optString("browser_download_url")
                if (url.isNotBlank()) return url
            }
        }
        return null
    }

    /** Stream the APK asset into `cache/update/`, reporting progress into state. */
    private fun download(context: Context, url: String): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        // Clear any stale downloads so a half-finished file never gets installed.
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val dst = File(dir, "vantaloom-update.apk")
        var connection = openFollowing(url)
        try {
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                dst.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var written = 0L
                    var lastPct = -1
                    while (input.read(buffer).also { read = it } >= 0) {
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            val pct = ((written * 100) / total).toInt().coerceIn(0, 99)
                            if (pct != lastPct) {
                                lastPct = pct
                                set(state.get().copy(progress = pct))
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        return dst
    }

    /** Launch the system package installer for the downloaded APK. Returns a
     *  short result the UI maps to a toast: `launched` / `needsInstallPermission`
     *  / `noFile`. */
    fun install(context: Context): String {
        val path = state.get().apkPath
        if (path.isBlank()) return "noFile"
        val file = File(path)
        if (!file.exists()) return "noFile"
        if (!canRequestInstall(context)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .setData(Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            return "needsInstallPermission"
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        return "launched"
    }

    fun canRequestInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    private fun set(next: State): State {
        state.set(next)
        return next
    }

    private fun httpGetString(urlStr: String): Pair<Int, String> {
        val connection = openFollowing(urlStr)
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to body
        } finally {
            connection.disconnect()
        }
    }

    /** Open the URL following redirects manually (HttpURLConnection does not follow
     *  cross-host https redirects like GitHub's asset → objects.githubusercontent). */
    private fun openFollowing(urlStr: String): HttpURLConnection {
        var target = urlStr
        var redirects = 0
        while (true) {
            val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
            }
            val code = connection.responseCode
            if (code in intArrayOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank() || redirects++ >= MAX_REDIRECTS) {
                    throw IllegalStateException("下载重定向失败")
                }
                target = URL(URL(target), location).toString()
                continue
            }
            return connection
        }
    }
}
