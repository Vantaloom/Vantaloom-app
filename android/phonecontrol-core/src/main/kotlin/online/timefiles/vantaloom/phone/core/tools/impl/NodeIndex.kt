package online.timefiles.vantaloom.phone.core.tools.impl

import online.timefiles.vantaloom.phone.core.model.RawNode
import online.timefiles.vantaloom.phone.core.model.RawSnapshot

/**
 * 按 [RawNode.id] 建的索引：id → 节点、id → 父节点。
 *
 * 为什么要自己建：[RawNode] 只有 children，没有 parent 指针（快照是不可变树，双向引用会让 data class 的
 * equals/hashCode 递归爆栈）。tap 要沿祖先找可点击节点、type 要回读原始文本，都需要这个索引。
 * 一次 capture 的节点数通常在几百到几千，按需建一次的开销可以忽略。
 *
 * 遍历用显式栈而不是递归：有些 App 的布局深度超过 100，工具线程栈又不大。
 */
class NodeIndex(val snapshot: RawSnapshot) {
    private val byId = HashMap<Int, RawNode>()
    private val parentById = HashMap<Int, Int>()

    init {
        val stack = ArrayDeque<RawNode>()
        for (w in snapshot.windows) w.root?.let { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            byId[n.id] = n
            for (c in n.children) {
                parentById[c.id] = n.id
                stack.addLast(c)
            }
        }
    }

    /** 按 id 取节点。 */
    fun node(id: Int): RawNode? = byId[id]

    /** 父节点；根节点或未知 id 为 null。 */
    fun parent(id: Int): RawNode? = parentById[id]?.let { byId[it] }

    /** 从父亲开始向上最多 [maxLevels] 层的祖先（近的在前）。 */
    fun ancestors(id: Int, maxLevels: Int): List<RawNode> {
        val out = ArrayList<RawNode>(maxLevels)
        var cur = parent(id)
        while (cur != null && out.size < maxLevels) {
            out += cur
            cur = parent(cur.id)
        }
        return out
    }

    /** 在祖先链里找第一个满足条件的节点。 */
    fun findAncestor(id: Int, maxLevels: Int, predicate: (RawNode) -> Boolean): RawNode? =
        ancestors(id, maxLevels).firstOrNull(predicate)

    /** 节点实际文本：如果 text 只是提示语（部分 ROM 会把 hint 填进 text）就当作空。 */
    fun realText(node: RawNode): String {
        val t = node.text ?: return ""
        if (node.hintText != null && t == node.hintText) return ""
        return t
    }
}
