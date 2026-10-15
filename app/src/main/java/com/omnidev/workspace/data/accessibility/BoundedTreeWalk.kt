package com.omnidev.workspace.data.accessibility

/** Iterative, lazy traversal: borrows root and releases every acquired child, even on failure.
 * A deadline stops NEW IPC calls; it cannot interrupt an Android Binder call already in flight.
 */
internal object BoundedTreeWalk {
    data class Result(val visited: Int, val truncated: Boolean)
    private data class Frame<T>(val node: T, val depth: Int, val children: Int, var next: Int = 0)

    fun <T> scan(
        root: T,
        childCount: (T) -> Int,
        child: (T, Int) -> T?,
        release: (T) -> Unit,
        visit: (T) -> Unit,
        now: () -> Long,
        maxNodes: Int = 256,
        maxDepth: Int = 48,
        budgetMs: Long = 120
    ): Result {
        require(maxNodes > 0 && maxDepth >= 0 && budgetMs > 0)
        val started = now()
        val stack = java.util.ArrayDeque<Frame<T>>()
        var visited = 1
        var requests = 0
        var truncated = false
        visit(root)
        stack.addLast(Frame(root, 0, childCount(root)))
        try {
            while (stack.isNotEmpty()) {
                val frame = stack.peekLast()
                if (frame.next >= frame.children || frame.depth >= maxDepth) {
                    if (frame.next < frame.children) truncated = true
                    stack.removeLast()
                    if (stack.isNotEmpty()) release(frame.node)
                    continue
                }
                // Count attempted lookups too: a wide tree of null children must be bounded.
                if (visited >= maxNodes || requests >= maxNodes - 1 || now() - started >= budgetMs) {
                    truncated = true
                    break
                }
                requests++
                val next = child(frame.node, frame.next++) ?: continue
                // Install ownership before visit/childCount so their exceptions cannot leak next.
                stack.addLast(Frame(next, frame.depth + 1, 0))
                visited++
                visit(next)
                val count = childCount(next)
                stack.removeLast()
                stack.addLast(Frame(next, frame.depth + 1, count))
            }
            return Result(visited, truncated)
        } finally {
            while (stack.size > 1) release(stack.removeLast().node)
        }
    }
}
