package com.zsz.studyassistant.data

import kotlinx.serialization.json.JsonPrimitive

/** Before the answer heading, even a nonempty preview can still be an unfinished question. */
internal data class SimilarExerciseOutput(val questionText: String, val answer: String?) {
    val readyQuestion: String? get() = questionText.takeIf { it.isNotBlank() && answer != null }

    companion object {
        // Require the answer heading on its own line or at the start of the answer line.
        // A question can itself quote a phrase such as “解答：” in its text.
        private fun heading(label: String) =
            Regex("""(?m)^[ \t]*(?:#{1,6}[ \t]+)?(?:\*\*)?$label(?:\*\*)?[：:](?:\*\*)?[ \t]*""")
        private val answerHeading = heading("解答")
        private val questionHeading = heading("题目")

        fun parse(raw: String): SimilarExerciseOutput {
            val heading = answerHeading.find(raw)
            val head = if (heading == null) raw else raw.substring(0, heading.range.first)
            val questionStart = questionHeading.find(head)?.range?.last?.plus(1) ?: 0
            val answer = heading?.let { raw.substring(it.range.last + 1).trimStart() }
            return SimilarExerciseOutput(head.substring(questionStart).trim(), answer)
        }
    }
}

/** A fresh exercise streams both sections. A continuation streams only the missing answer suffix. */
internal class SimilarExerciseGeneration(val question: String? = null, val prefix: String = "") {
    fun output(raw: String): SimilarExerciseOutput = if (question == null) SimilarExerciseOutput.parse(raw)
        else SimilarExerciseOutput(question, prefix + raw)

    fun continuationMessages(system: DeepSeekMessage): List<DeepSeekMessage> {
        val messages = listOf(system, DeepSeekMessage("user",
            JsonPrimitive("请解答下面这道已经确定的练习题，仅给出完整的参考解答，不要复述题干或另出新题。\n\n" + requireNotNull(question))))
        return ContinuationContext(messages, prefix, emptyList(), null).messagesForRequest(webEnabled = false)
    }
}
