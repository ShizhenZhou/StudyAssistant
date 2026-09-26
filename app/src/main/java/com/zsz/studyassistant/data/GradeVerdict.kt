package com.zsz.studyassistant.data

/**
 * 批改结论 → 复习掌握度档位（B6 重做模式自动更新掌握度用）。
 *
 * 批改提示词要求模型先写一行「结论：<正确/错误，错在哪一步>」，但模型偶尔不守格式，
 * 所以这里**只看开头一段**做关键词判定，判不出来就退到"模糊"（保持档位，不惩罚也不奖励）。
 * 纯函数 + 单元测试：错了会导致"做对了却被重置"或"做错了却升档"，直接影响复习计划。
 */
object GradeVerdict {

    const val FORGOT = 0
    const val VAGUE = 1
    const val FAMILIAR = 2

    private val WRONG = Regex("(不正确|错误|答错|做错|有误|❌|✗|×)")
    private val RIGHT = Regex("(正确|答对|做对|无误|✅|✓)")

    /** 只在前 [headLen] 个字符里判定（结论在最前面；避免正文里出现"正确"等词造成误判） */
    fun level(reply: String, headLen: Int = 600): Int {
        val head = reply.take(headLen)
        // ★ 必须先判"错"：中文里"不正确"包含"正确"，顺序反了会把答错当成答对
        if (WRONG.containsMatchIn(head)) return FORGOT
        if (RIGHT.containsMatchIn(head)) return FAMILIAR
        return VAGUE
    }
}
