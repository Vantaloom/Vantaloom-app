package online.timefiles.vantaloom.phone.core.fixture

import online.timefiles.vantaloom.phone.core.json.Json
import online.timefiles.vantaloom.phone.core.json.JsonException
import online.timefiles.vantaloom.phone.core.json.jBool
import online.timefiles.vantaloom.phone.core.json.jInt
import online.timefiles.vantaloom.phone.core.json.jList
import online.timefiles.vantaloom.phone.core.json.jLong
import online.timefiles.vantaloom.phone.core.json.jObj
import online.timefiles.vantaloom.phone.core.json.jStr
import online.timefiles.vantaloom.phone.core.json.jStrList
import online.timefiles.vantaloom.phone.core.model.DisplayKind
import online.timefiles.vantaloom.phone.core.model.NodeAction
import online.timefiles.vantaloom.phone.core.model.RawNode
import online.timefiles.vantaloom.phone.core.model.RawSnapshot
import online.timefiles.vantaloom.phone.core.model.RawWindow
import online.timefiles.vantaloom.phone.core.model.Rect
import online.timefiles.vantaloom.phone.core.model.WindowType

/**
 * RawSnapshot ↔ JSON。debug dump（demo 导出屏幕）和 JVM 测试夹具共用同一格式，
 * 所以真机上导出的 dump 可以直接拿来回放复现问题。
 *
 * 格式要点：
 *  - bounds 写成 `[l, t, r, b]` 四元数组（比对象紧凑，dump 动辄上千节点）。
 *  - 布尔字段只在为 true 时写出（visibleToUser/enabled 例外：它们默认 true，只在 false 时写出），
 *    读取时缺省按 Android 的默认值处理。这样手写夹具时只需写关心的字段。
 *  - actions 写枚举名数组；不认识的名字读取时忽略（老 dump 与新枚举兼容）。
 *  - 顶层带 `"v": 1` 版本号。
 */
object SnapshotJson {
    /** 格式版本。 */
    const val VERSION = 1

    /** 序列化为 JSON 对象。 */
    fun toJson(s: RawSnapshot): Map<String, Any?> = Json.obj(
        "v" to VERSION,
        "captureId" to s.captureId,
        "display" to s.display.wire,
        "foregroundPackage" to s.foregroundPackage,
        "foregroundLabel" to s.foregroundLabel,
        "screenWidth" to s.screenWidth,
        "screenHeight" to s.screenHeight,
        "rotation" to s.rotation,
        "imeVisible" to s.imeVisible,
        "secureWindow" to s.secureWindow,
        "complete" to s.complete,
        "capturedAtMs" to s.capturedAtMs,
        "windows" to s.windows.map { windowToJson(it) },
    )

    /** 序列化为 JSON 文本（dump 默认 pretty，便于人读和 diff）。 */
    fun toJsonString(s: RawSnapshot, pretty: Boolean = true): String = Json.stringify(toJson(s), pretty)

    /** 窗口 → JSON。 */
    fun windowToJson(w: RawWindow): Map<String, Any?> {
        val m = Json.obj(
            "windowId" to w.windowId,
            "type" to w.type.name,
            "packageName" to w.packageName,
            "title" to w.title,
            "bounds" to rectToJson(w.bounds),
            "active" to w.active,
            "focused" to w.focused,
            "layer" to w.layer,
        )
        m["root"] = w.root?.let { nodeToJson(it) }
        return m
    }

    /** 节点 → JSON（递归；只写非默认值字段）。 */
    fun nodeToJson(n: RawNode): Map<String, Any?> {
        val m = Json.obj("id" to n.id, "windowId" to n.windowId)
        fun str(k: String, v: String?) { if (v != null) m[k] = v }
        fun flag(k: String, v: Boolean) { if (v) m[k] = true }
        str("className", n.className)
        str("packageName", n.packageName)
        str("text", n.text)
        str("contentDescription", n.contentDescription)
        str("hintText", n.hintText)
        str("viewId", n.viewId)
        str("paneTitle", n.paneTitle)
        str("stateDescription", n.stateDescription)
        str("tooltip", n.tooltip)
        m["bounds"] = rectToJson(n.bounds)
        flag("clickable", n.clickable)
        flag("longClickable", n.longClickable)
        flag("focusable", n.focusable)
        flag("focused", n.focused)
        flag("editable", n.editable)
        flag("scrollable", n.scrollable)
        flag("checkable", n.checkable)
        flag("checked", n.checked)
        flag("selected", n.selected)
        if (!n.enabled) m["enabled"] = false
        flag("password", n.password)
        if (!n.visibleToUser) m["visibleToUser"] = false
        flag("heading", n.heading)
        if (n.actions.isNotEmpty()) m["actions"] = n.actions.map { it.name }.sorted()
        if (n.collectionRows != -1) m["collectionRows"] = n.collectionRows
        if (n.collectionColumns != -1) m["collectionColumns"] = n.collectionColumns
        if (n.maxTextLength != -1) m["maxTextLength"] = n.maxTextLength
        if (n.children.isNotEmpty()) m["children"] = n.children.map { nodeToJson(it) }
        return m
    }

    /** Rect → `[l, t, r, b]`。 */
    fun rectToJson(r: Rect): List<Int> = listOf(r.left, r.top, r.right, r.bottom)

    /** 从 JSON 文本恢复。格式错误抛 [JsonException]。 */
    fun fromJsonString(text: String): RawSnapshot = fromJson(Json.parseObject(text, maxDepth = FIXTURE_MAX_DEPTH))

    /**
     * 从 JSON 对象恢复。缺失字段按默认值：captureId 1、display main、屏幕 1080×2400、complete true。
     * 夹具常常手写，宽松读取比严格报错更实用；但窗口/节点结构不对（不是对象）仍然报错。
     */
    fun fromJson(m: Map<String, Any?>): RawSnapshot {
        val windows = m.jList("windows")?.mapIndexed { i, w ->
            windowFromJson(Json.asObject(w) ?: throw JsonException("windows[$i] 不是对象"))
        } ?: emptyList()
        return RawSnapshot(
            captureId = m.jLong("captureId", 1),
            display = DisplayKind.fromWire(m.jStr("display")) ?: DisplayKind.MAIN,
            foregroundPackage = m.jStr("foregroundPackage"),
            foregroundLabel = m.jStr("foregroundLabel"),
            screenWidth = m.jInt("screenWidth", 1080),
            screenHeight = m.jInt("screenHeight", 2400),
            rotation = m.jInt("rotation", 0),
            imeVisible = m.jBool("imeVisible", false),
            secureWindow = m.jBool("secureWindow", false),
            windows = windows,
            complete = m.jBool("complete", true),
            capturedAtMs = m.jLong("capturedAtMs", 0),
        )
    }

    /** 窗口 ← JSON。 */
    fun windowFromJson(m: Map<String, Any?>): RawWindow {
        val windowId = m.jInt("windowId", 0)
        val root = m.jObj("root")?.let { nodeFromJson(it, windowId, IdAllocator()) }
        return RawWindow(
            windowId = windowId,
            type = m.jStr("type")?.let { t -> WindowType.entries.firstOrNull { it.name.equals(t, ignoreCase = true) } } ?: WindowType.APPLICATION,
            packageName = m.jStr("packageName"),
            title = m.jStr("title"),
            bounds = rectFromJson(m["bounds"]) ?: root?.bounds ?: Rect.EMPTY,
            active = m.jBool("active", false),
            focused = m.jBool("focused", false),
            layer = m.jInt("layer", 0),
            root = root,
        )
    }

    /** 手写夹具可以省略 id：没写时按前序遍历自动分配（与驱动的约定一致）。 */
    private class IdAllocator { var next = 0 }

    private fun nodeFromJson(m: Map<String, Any?>, windowId: Int, ids: IdAllocator): RawNode {
        val explicit = (m["id"] as? Number)?.toInt()
        val id = explicit ?: ids.next
        ids.next = maxOf(ids.next, id) + 1
        val children = m.jList("children")?.mapIndexed { i, c ->
            nodeFromJson(Json.asObject(c) ?: throw JsonException("节点 $id 的 children[$i] 不是对象"), windowId, ids)
        } ?: emptyList()
        val actions = m.jStrList("actions")?.mapNotNullTo(LinkedHashSet()) { a ->
            NodeAction.entries.firstOrNull { it.name.equals(a, ignoreCase = true) }
        } ?: emptySet<NodeAction>()
        return RawNode(
            id = id,
            windowId = m.jInt("windowId", windowId),
            className = m.jStr("className"),
            packageName = m.jStr("packageName"),
            text = m.jStr("text"),
            contentDescription = m.jStr("contentDescription"),
            hintText = m.jStr("hintText"),
            viewId = m.jStr("viewId"),
            paneTitle = m.jStr("paneTitle"),
            stateDescription = m.jStr("stateDescription"),
            tooltip = m.jStr("tooltip"),
            bounds = rectFromJson(m["bounds"]) ?: Rect.EMPTY,
            clickable = m.jBool("clickable", false),
            longClickable = m.jBool("longClickable", false),
            focusable = m.jBool("focusable", false),
            focused = m.jBool("focused", false),
            editable = m.jBool("editable", false),
            scrollable = m.jBool("scrollable", false),
            checkable = m.jBool("checkable", false),
            checked = m.jBool("checked", false),
            selected = m.jBool("selected", false),
            enabled = m.jBool("enabled", true),
            password = m.jBool("password", false),
            visibleToUser = m.jBool("visibleToUser", true),
            heading = m.jBool("heading", false),
            actions = actions,
            collectionRows = m.jInt("collectionRows", -1),
            collectionColumns = m.jInt("collectionColumns", -1),
            maxTextLength = m.jInt("maxTextLength", -1),
            children = children,
        )
    }

    /** `[l, t, r, b]` 或 `{left, top, right, bottom}` → Rect；格式不对返回 null。 */
    fun rectFromJson(v: Any?): Rect? {
        Json.asList(v)?.let { l ->
            val n = l.mapNotNull { (it as? Number)?.toInt() }
            return if (n.size == 4) Rect(n[0], n[1], n[2], n[3]) else null
        }
        Json.asObject(v)?.let { o ->
            return Rect(o.jInt("left", 0), o.jInt("top", 0), o.jInt("right", 0), o.jInt("bottom", 0))
        }
        return null
    }

    /** 多份快照的夹具文件：`{"v":1,"snapshots":[…]}` 或直接是数组。 */
    fun listFromJsonString(text: String): List<RawSnapshot> {
        val root = Json.parse(text, maxDepth = FIXTURE_MAX_DEPTH)
        val items = Json.asList(root) ?: Json.asObject(root)?.jList("snapshots")
            ?: Json.asObject(root)?.let { return listOf(fromJson(it)) }
            ?: throw JsonException("夹具应为快照对象、快照数组或 {snapshots:[…]}")
        return items.mapIndexed { i, s -> fromJson(Json.asObject(s) ?: throw JsonException("snapshots[$i] 不是对象")) }
    }

    /** 多份快照 → 夹具 JSON 文本。 */
    fun listToJsonString(list: List<RawSnapshot>, pretty: Boolean = true): String =
        Json.stringify(Json.obj("v" to VERSION, "snapshots" to list.map { toJson(it) }), pretty)

    /** dump 里节点深度 × 2（对象 + children 数组）可能超过 Json 默认的 64 层，夹具解析放宽到这个值。 */
    const val FIXTURE_MAX_DEPTH = 512
}
