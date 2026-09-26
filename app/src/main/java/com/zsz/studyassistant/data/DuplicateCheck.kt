package com.zsz.studyassistant.data

/**
 * 重复题检测（B9）：存题前按**归一化题干**比较，命中就提示"可能已存过"。
 *
 * 设计取舍：
 *  - **只提示、不阻止**：题干相似不等于同一道题（同一题不同问法很常见），最终由用户决定 → 可"仍然保存"。
 *  - 归一化很保守：只去空白和常见标点（含 LaTeX 的 `$`、全角括号），**不做**同义词/公式等价替换 ——
 *    宁可漏报也不要误报（误报会让用户怀疑查重是坏的）。
 *  - 太短的题干（默认 < 6 个有效字符，如"1+1"）直接跳过，否则"1+1"会跟一堆题撞车。
 *  - 纯函数 + 单元测试（见 DuplicateCheckTest）：这里错了就是"存不进去"或"疯狂误报"，必须有回归网。
 */
object DuplicateCheck {

    /** 参与比较的最短有效长度 */
    const val MIN_LEN = 6

    private val BLANKS = Regex("\\s+")
    private val PUNCT = Regex("[，。、；：？！,.;:?!\"'“”‘’（）()【】\\[\\]{}<>《》「」]")

    /** 归一化：小写 + 去所有空白 + 去常见标点 + 去 LaTeX 行内定界符 `$` */
    fun normalize(text: String): String =
        text.lowercase()
            .replace(BLANKS, "")
            .replace(PUNCT, "")
            .replace("\$", "")
            .trim()

    /**
     * 在已有题目里找"同题干"的那一条（未删除、且有效长度都够）。
     * @param excludeId 排除这道题自身（重新保存同一条时不要提示自己）
     */
    fun find(
        existing: List<Question>,
        text: String,
        excludeId: Long? = null,
        minLen: Int = MIN_LEN
    ): Question? {
        val key = normalize(text)
        if (key.length < minLen) return null
        return existing.firstOrNull { q ->
            !q.deleted && q.id != excludeId && normalize(q.text).let { k -> k.length >= minLen && k == key }
        }
    }
}
