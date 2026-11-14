package com.zsz.studyassistant.data

/** Bounded budgets, independent of UI language. Small classification/crop calls keep their own limits. */
object AiRequestPolicy {
    data class Budget(val tokens: Int, val effort: String?)
    fun budget(task: String, thinking: Boolean, web: Boolean = false): Budget {
        val followUp = task == "followup" || task == "regenerate_followup"
        val grade = task == "grade" || task == "restore_grade"
        val base = if (thinking) when { grade -> 49152; followUp -> 16384; else -> 32768 }
            else when { grade -> 16384; followUp -> 4096; else -> 8192 }
        return Budget(if (web) maxOf(base, if (thinking) 32768 else 16384) else base,
            if (!thinking) null else if (followUp && !web) "low" else "high")
    }
}
