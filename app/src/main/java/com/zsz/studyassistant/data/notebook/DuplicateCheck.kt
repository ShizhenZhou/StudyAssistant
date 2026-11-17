package com.zsz.studyassistant.data

/**
 * 重复题检测（B9）：存题前按**归一化题干**比较，命中就提示"可能已存过"。
 *
 * 设计取舍：
 *  - **只提示、不阻止**：题干相似不等于同一道题（同一题不同问法很常见），最终由用户决定 → 可"仍然保存"。
 *  - 分三档判定（从严到宽），**越宽的条件要求的长度越长**（短题干用任何相似度指标都会误报）：
 *      ① 比较键完全相同 → 命中；
 *      ② 一方包含另一方（都 ≥ [CONTAIN_MIN_LEN]）→ 命中（例：第二次转写多/少了一句说明）；
 *      ③ 字符 bigram 的 Dice 相似度 ≥ [FUZZY_THRESHOLD]（都 ≥ [FUZZY_MIN_LEN]）→ 命中。
 *    ⚠️ 加 ②③ 的原因（用户实测反馈的真实 bug）：拍同一道题两次时，AI 的转写**不会逐字相同**
 *    （LaTeX 记号、标点、语序常有小差异），只做"完全相同"比较等于永远查不出来。
 *  - 比较键去噪：小写 + 去所有空白 + 去常见中英标点 + 去 `$`，**再去 LaTeX 记号 `\ { } ^ _ ~`**
 *    （`x^{2}` 与 `x^2` 要能对上）；但**不做**同义词/公式等价替换 —— 宁可漏报也别乱报。
 *  - 太短的题干（默认 < 6 个有效字符，如"1+1"）直接跳过，否则"1+1"会跟一堆题撞车。
 *  - 纯函数 + 单元测试（见 DuplicateCheckTest）：这里错了就是"存不进去"或"疯狂误报"，必须有回归网。
 */
object DuplicateCheck {

    /** 参与比较的最短有效长度 */
    const val MIN_LEN = 6
    /** "包含关系"判定的最短长度 */
    const val CONTAIN_MIN_LEN = 12
    /** 模糊相似度（Dice）判定的最短长度：短题干一律只走"完全相同" */
    const val FUZZY_MIN_LEN = 16
    /** 模糊相似度阈值：0.92 ≈ 40 字里允许 3 个字左右的差异 */
    const val FUZZY_THRESHOLD = 0.92

    private val BLANKS = Regex("\\s+")
    private val PUNCT = Regex("[，。、；：？！,.;:?!\"'“”‘’（）()【】\\[\\]{}<>《》「」]")
    /** LaTeX 记号：同一道题两次转写经常只在 `\ { } ^ _ ~` 上不同 */
    private val LATEX_MARKS = Regex("[\\\\{}^_~]")

    /** 第一层归一化：小写 + 去所有空白 + 去常见标点 + 去 LaTeX 行内定界符 `$` */
    fun normalize(text: String): String =
        text.lowercase()
            .replace(BLANKS, "")
            .replace(PUNCT, "")
            .replace("\$", "")
            .trim()

    /** 比较键：在 [normalize] 基础上再去掉 LaTeX 记号（`x^{2}` → `x2`） */
    fun compareKey(text: String): String = normalize(text).replace(LATEX_MARKS, "")

    /** 相似度 0..1：完全相同 1.0；包含关系 0.95；否则字符 bigram 的 Dice 系数（太短返回 0） */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (a.length >= CONTAIN_MIN_LEN && b.length >= CONTAIN_MIN_LEN &&
            (a.contains(b) || b.contains(a))
        ) return 0.95
        // 太短的一律不判相似：`x2` 与 `x3`、"1+1" 与 "1+2" 这类会被误判成同一题
        if (a.length < FUZZY_MIN_LEN || b.length < FUZZY_MIN_LEN) return 0.0
        return dice(a, b)
    }

    private fun bigrams(s: String): Map<String, Int> {
        val m = HashMap<String, Int>()
        if (s.length < 2) {
            m[s] = 1
            return m
        }
        for (i in 0 until s.length - 1) {
            val g = s.substring(i, i + 2)
            m[g] = (m[g] ?: 0) + 1
        }
        return m
    }

    private fun dice(a: String, b: String): Double {
        val ba = bigrams(a)
        val bb = bigrams(b)
        var total = 0
        for (v in ba.values) total += v
        for (v in bb.values) total += v
        if (total == 0) return 0.0
        var inter = 0
        for ((g, c) in ba) inter += minOf(c, bb[g] ?: 0)
        return 2.0 * inter / total
    }

    /**
     * 在已有题目里找"同一道题"（未删除、长度够、且相似度达标）；返回**最像的那一条**。
     * @param excludeId 排除这道题自身（重新保存同一条时不要提示自己）
     */
    fun find(
        existing: List<Question>,
        text: String,
        excludeId: QuestionId? = null,
        minLen: Int = MIN_LEN,
        threshold: Double = FUZZY_THRESHOLD
    ): Question? {
        val matcher = Matcher(text, excludeId, minLen, threshold)
        if (!matcher.eligible) return null
        for (q in existing) {
            if (!q.deleted && matcher.consider(q.id, q.text)) break
        }
        return existing.firstOrNull { it.id == matcher.bestId }
    }

    /** Incremental comparison retains only one ID, never all question/answer bodies. */
    class Matcher(text: String, private val excludeId: QuestionId? = null,
        private val minLen: Int = MIN_LEN, private val threshold: Double = FUZZY_THRESHOLD) {
        private val key = compareKey(text)
        val eligible: Boolean get() = key.length >= minLen
        var bestId: QuestionId? = null
            private set
        private var bestScore = 0.0

        /** True only for an exact match, when scanning can stop. */
        fun consider(id: QuestionId, text: String): Boolean {
            if (!eligible || id == excludeId) return false
            val candidate = compareKey(text)
            if (candidate.length < minLen) return false
            val score = similarity(key, candidate)
            if (score >= threshold && score > bestScore) {
                bestScore = score
                bestId = id
            }
            if (candidate == key) { bestId = id; return true }
            return false
        }
    }
}
