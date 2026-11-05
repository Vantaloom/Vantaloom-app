package online.timefiles.vantaloom.phone.core.session

import online.timefiles.vantaloom.phone.core.json.Json
import online.timefiles.vantaloom.phone.core.mcp.JsonValues
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer

/**
 * 一次工具调用的审计记录（协议 §9）。
 * [args] 必须已经是摘要（[online.timefiles.vantaloom.phone.core.tools.ToolArgs.auditSummary]）：
 * `type` 等的文本只记长度，绝不落盘原文。
 */
data class AuditEntry(
    val timeMs: Long,
    val taskId: String?,
    val tool: String,
    val args: String,
    /** 安全门判定：allow / confirm:<rule>:<结果> / deny:<rule>；不经过安全门的为 null。 */
    val decision: String?,
    /** OK 或错误码 wire。 */
    val code: String,
    val durationMs: Long,
    val remoteCaller: String?,
) {
    /** JSONL 一行的字段（短键名，省空间；顺序固定便于人读）。 */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "t" to timeMs,
        "task" to taskId,
        "tool" to tool,
        "args" to args,
        "decision" to decision,
        "code" to code,
        "ms" to durationMs,
        "remote" to remoteCaller,
    )
}

/** 审计出口。实现必须线程安全，且**永不抛异常**（审计失败不能让工具调用失败）。 */
interface AuditLog {
    fun record(entry: AuditEntry)

    companion object {
        /** 不记录（测试、未配置目录时）。 */
        val NONE: AuditLog = object : AuditLog {
            override fun record(entry: AuditEntry) = Unit
        }
    }
}

/**
 * 文件环形审计：两个文件 `audit-0.jsonl`（当前写）和 `audit-1.jsonl`（上一段）。
 * 当前段写满 maxLines/2 行时，丢掉 audit-1、把 audit-0 改名为 audit-1、新开 audit-0。
 * 这样总行数始终在 maxLines/2 .. maxLines 之间，近似「环形保留 2000 行」，又不需要原地改写文件。
 *
 * 进程重启后会数一遍 audit-0 的行数接着写（2000 行级别，开销可以忽略）。
 * IO 错误一律吞掉：审计是尽力而为，不能反过来卡住用户的操作。
 */
class FileAuditLog(
    private val dir: File,
    private val maxLines: Int = 2000,
) : AuditLog {
    private val lock = Any()
    private val segmentLines = (maxLines / 2).coerceAtLeast(1)
    private val current = File(dir, "audit-0.jsonl")
    private val previous = File(dir, "audit-1.jsonl")
    private var writer: Writer? = null
    private var linesInCurrent = -1

    override fun record(entry: AuditEntry) {
        val line = try {
            Json.stringify(JsonValues.normalize(entry.toMap()))
        } catch (_: Throwable) {
            return
        }
        synchronized(lock) {
            try {
                ensureOpen()
                if (linesInCurrent >= segmentLines) rotate()
                val w = writer ?: return
                w.write(line)
                w.write("\n")
                w.flush()
                linesInCurrent++
            } catch (_: Throwable) {
                closeQuietly()
            }
        }
    }

    /** 关闭文件句柄（服务停止时）。之后再 record 会重新打开。 */
    fun close() {
        synchronized(lock) { closeQuietly() }
    }

    private fun ensureOpen() {
        if (writer != null) return
        if (!dir.exists()) dir.mkdirs()
        if (linesInCurrent < 0) linesInCurrent = countLines(current)
        writer = OutputStreamWriter(FileOutputStream(current, true), Charsets.UTF_8)
    }

    private fun rotate() {
        closeQuietly()
        if (previous.exists()) previous.delete()
        if (current.exists() && !current.renameTo(previous)) current.delete()
        linesInCurrent = 0
        writer = OutputStreamWriter(FileOutputStream(current, false), Charsets.UTF_8)
    }

    private fun countLines(f: File): Int {
        if (!f.exists()) return 0
        return try {
            BufferedReader(InputStreamReader(f.inputStream(), Charsets.UTF_8)).use { r ->
                var n = 0
                while (r.readLine() != null) n++
                n
            }
        } catch (_: Throwable) {
            0
        }
    }

    private fun closeQuietly() {
        try {
            writer?.close()
        } catch (_: Throwable) {
        }
        writer = null
    }
}
