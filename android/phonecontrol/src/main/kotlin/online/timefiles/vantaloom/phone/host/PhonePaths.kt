package online.timefiles.vantaloom.phone.host

import android.content.Context
import android.system.Os
import java.io.File

/**
 * files/phone-mcp/ 下的全部落盘位置。集中在一处：Vantaloom runtime 子进程（同 UID）按约定路径读 endpoint.json，
 * 任何一处写错都会让接入断掉。
 */
object PhonePaths {
    /** files/phone-mcp */
    fun root(context: Context): File = File(context.filesDir, "phone-mcp")

    /** 协议 §1 的凭据文件。runtime 通过环境变量 VANTALOOM_PHONE_MCP_FILE 拿到它的绝对路径。 */
    fun endpointFile(context: Context): File = File(root(context), "endpoint.json")

    /** 审计 JSONL（FileAuditLog 的目录）。 */
    fun auditDir(context: Context): File = File(root(context), "audit")

    /** 屏幕 dump（测试夹具）。 */
    fun dumpDir(context: Context): File = File(root(context), "dumps")

    /** 从 assets 物化出来的插件目录（POST /v1/plugins/install 的 path）。 */
    fun pluginDir(context: Context): File = File(root(context), "plugin")

    /** 环境变量名：宿主启动 runtime 子进程时设置为 [endpointFile] 的绝对路径。 */
    const val ENV_ENDPOINT_FILE = "VANTALOOM_PHONE_MCP_FILE"

    /**
     * 原子写入并收紧为 0600。先写临时文件、chmod、再 rename：runtime 可能在任何时刻读，
     * 不能让它读到半个 JSON；先 chmod 再 rename 保证文件从出现的第一刻起就是 0600。
     */
    fun writePrivate(file: File, text: String) {
        val dir = file.parentFile ?: throw IllegalArgumentException("没有父目录：$file")
        if (!dir.exists()) dir.mkdirs()
        restrictDir(dir)
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        restrict(tmp)
        if (!tmp.renameTo(file)) {
            // 极少数文件系统不支持覆盖式 rename：先删后改。
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw java.io.IOException("无法写入 $file")
            }
        }
        restrict(file)
    }

    /** 0600。Os.chmod 失败时退化到 java.io.File 的权限位（效果相同：仅属主读写）。 */
    fun restrict(file: File) {
        try {
            Os.chmod(file.absolutePath, MODE_0600)
        } catch (_: Throwable) {
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setExecutable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
    }

    private fun restrictDir(dir: File) {
        try {
            Os.chmod(dir.absolutePath, MODE_0700)
        } catch (_: Throwable) {
            // filesDir 本身就是 0700，子目录权限收不紧也不致命。
        }
    }

    /** Kotlin 没有八进制字面量：0600 = 384，0700 = 448。 */
    private const val MODE_0600 = 384
    private const val MODE_0700 = 448
}
