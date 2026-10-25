package com.zsz.studyassistant.data

/** A machine tag is preferred; localized opening verdicts are a conservative fallback.
 * This is a suggestion only. A user confirms the review level before any database update.
 */
object GradeVerdict {
    const val FORGOT = 0
    const val VAGUE = 1
    const val FAMILIAR = 2
    private val TAG = Regex("(?m)^\\s*<review_verdict>(correct|partial|incorrect|unknown)</review_verdict>\\s*$")
    fun suggestion(reply: String): Int? {
        val tags = TAG.findAll(reply).map { it.groupValues[1] }.toList()
        if (tags.isNotEmpty()) {
            if (tags.distinct().size != 1) return null
            return when (tags.first()) { "correct" -> FAMILIAR; "incorrect" -> FORGOT; "partial" -> VAGUE; else -> null }
        }
        val line = reply.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return null
        val head = line.replace(Regex("^(?:[①-⑳]\\s*)?(?:结论|結論|Conclusion|Result|결론|解答|答案)\\s*[:：]\\s*", RegexOption.IGNORE_CASE), "")
        if (Regex("^(?:不正确|不正確|错误|錯誤|不正解|誤り|incorrect\\b|wrong\\b|틀림|오답|❌|✗|×)", RegexOption.IGNORE_CASE).containsMatchIn(head)) return FORGOT
        if (Regex("^(?:正确|正確|答对|答對|正しい|正解|correct\\b|right\\b|정답|맞음|✅|✓)", RegexOption.IGNORE_CASE).containsMatchIn(head)) return FAMILIAR
        return null
    }
    fun level(reply: String, headLen: Int = 600): Int = suggestion(reply.take(headLen)) ?: VAGUE
}
