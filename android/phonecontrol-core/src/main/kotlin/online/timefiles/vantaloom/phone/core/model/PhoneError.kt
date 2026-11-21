package online.timefiles.vantaloom.phone.core.model

/** 协议 §5 的错误码。wire 是对外字符串，改了就是破坏协议。 */
enum class ErrorCode(val wire: String, val retry: Retry) {
    INVALID_ARGS("INVALID_ARGS", Retry.NEVER),
    SERVICE_DISABLED("SERVICE_DISABLED", Retry.NEVER),
    SCREEN_LOCKED("SCREEN_LOCKED", Retry.NEVER),
    STALE_SCREEN("STALE_SCREEN", Retry.SAFE),
    AMBIGUOUS("AMBIGUOUS", Retry.SAFE),
    NOT_ACTIONABLE("NOT_ACTIONABLE", Retry.SAFE),
    DENIED_POLICY("DENIED_POLICY", Retry.NEVER),
    USER_REJECTED("USER_REJECTED", Retry.NEVER),
    CONFIRM_TIMEOUT("CONFIRM_TIMEOUT", Retry.NEVER),
    PAUSED("PAUSED", Retry.SAFE),
    STOPPED("STOPPED", Retry.NEVER),
    HANDOFF_ABANDONED("HANDOFF_ABANDONED", Retry.NEVER),
    SECURE_WINDOW("SECURE_WINDOW", Retry.NEVER),
    UNSUPPORTED("UNSUPPORTED", Retry.NEVER),
    ACTION_FAILED("ACTION_FAILED", Retry.UNSAFE),
    TIMEOUT("TIMEOUT", Retry.UNSAFE),
    BUSY("BUSY", Retry.SAFE),
    INTERNAL("INTERNAL", Retry.UNSAFE);
}

enum class Retry(val wire: String) { SAFE("safe"), UNSAFE("unsafe"), NEVER("never") }

/**
 * 已知失败。[details] 是任意可 JSON 化的值（String/Number/Boolean/List/Map/null）。
 * [observationText] 非空时会拼在错误文本后面（如 STALE_SCREEN 附新屏幕）。
 */
class PhoneException(
    val code: ErrorCode,
    override val message: String,
    val details: Map<String, Any?> = emptyMap(),
    val observationText: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

fun phoneError(code: ErrorCode, message: String, details: Map<String, Any?> = emptyMap()): Nothing =
    throw PhoneException(code, message, details)
