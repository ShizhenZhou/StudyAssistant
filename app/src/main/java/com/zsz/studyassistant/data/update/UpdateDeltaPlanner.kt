package com.zsz.studyassistant.data

/** One verified-manifest edge. Intermediate APKs are private data, never installed. */
data class UpdateDeltaStep(val delta: UpdateDeltaInfo, val targetSha256: String, val targetSize: Long, val tagName: String) {
    internal fun valid(): Boolean = UpdateDelta.supports(delta.format) &&
        delta.baseSize in 1..UpdateDelta.MAX_APK_SIZE && targetSize in 1..UpdateDelta.MAX_APK_SIZE &&
        delta.patchSize in 1 until targetSize &&
        listOf(delta.baseSha256, delta.patchSha256, targetSha256).all { Regex("[0-9a-f]{64}").matches(it) } &&
        Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}").matches(delta.patchName) &&
        UpdateChecker.isReleaseAssetUrl(delta.patchUrl, tagName) &&
        runCatching { java.net.URI(delta.patchUrl).path.substringAfterLast('/') == delta.patchName }.getOrDefault(false)
}

data class UpdateDeltaPlan(val steps: List<UpdateDeltaStep>) {
    val downloadSize: Long get() = steps.sumOf { it.delta.patchSize }
}

internal object UpdateDeltaPlanner {
    private data class Identity(val sha: String, val size: Long)
    private data class Route(val node: Identity, val cost: Long)

    /** Prefer a direct patch; otherwise Dijkstra finds the smallest total download, never equal to full. */
    fun find(baseSha: String, baseSize: Long, targetSha: String, targetSize: Long,
             candidates: List<UpdateDeltaStep>, check: () -> Unit = {}): UpdateDeltaPlan? {
        val start = Identity(baseSha.lowercase(), baseSize)
        val target = Identity(targetSha.lowercase(), targetSize)
        if (start == target || targetSize !in 1..UpdateDelta.MAX_APK_SIZE) return null
        val edges = candidates.filter { it.valid() }
        edges.filter { Identity(it.delta.baseSha256, it.delta.baseSize) == start &&
            Identity(it.targetSha256, it.targetSize) == target && it.delta.patchSize < targetSize }
            .minByOrNull { it.delta.patchSize }?.let { return UpdateDeltaPlan(listOf(it)) }
        val outgoing = edges.groupBy { Identity(it.delta.baseSha256, it.delta.baseSize) }
        val queue = java.util.PriorityQueue<Route>(compareBy { it.cost })
        val best = mutableMapOf(start to 0L)
        val previous = mutableMapOf<Identity, Pair<Identity, UpdateDeltaStep>>()
        queue.add(Route(start, 0))
        while (queue.isNotEmpty()) {
            check()
            val route = queue.remove()
            if (best[route.node] != route.cost) continue
            if (route.node == target) {
                val steps = mutableListOf<UpdateDeltaStep>()
                var node = target
                while (node != start) {
                    val (parent, edge) = checkNotNull(previous[node])
                    steps += edge
                    node = parent
                }
                return UpdateDeltaPlan(steps.asReversed())
            }
            for (edge in outgoing[route.node].orEmpty()) {
                val cost = route.cost + edge.delta.patchSize
                val next = Identity(edge.targetSha256, edge.targetSize)
                if (cost >= targetSize || cost >= (best[next] ?: Long.MAX_VALUE)) continue
                best[next] = cost
                previous[next] = route.node to edge
                queue.add(Route(next, cost))
            }
        }
        return null
    }
}
