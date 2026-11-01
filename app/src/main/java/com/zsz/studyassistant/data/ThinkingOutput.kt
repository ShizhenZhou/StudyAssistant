package com.zsz.studyassistant.data

/** One parser for live, completed and interrupted replies; unfinished thinking never becomes an answer. */
internal object ThinkingOutput {
    data class Parts(val body: String, val thinking: String?)

    fun split(raw: String): Parts {
        val text = raw.replace("〈思考〉", "<思考>").replace("〈/思考〉", "</思考>")
            .replace("&lt;思考&gt;", "<思考>").replace("&lt;/思考&gt;", "</思考>")
            .replace("<thinking>", "<思考>").replace("</thinking>", "</思考>")
            .replace("【思考】", "<思考>").replace("【/思考】", "</思考>")
        val body = StringBuilder()
        val thinking = mutableListOf<String>()
        var offset = 0
        while (true) {
            val start = text.indexOf("<思考>", offset)
            if (start < 0) break
            body.append(text.substring(offset, start))
            val end = text.indexOf("</思考>", start + 4)
            thinking += text.substring(start + 4, if (end < 0) text.length else end)
            offset = if (end < 0) text.length else end + 5
        }
        body.append(text.substring(offset))
        if (thinking.isNotEmpty()) return Parts(body.toString(), thinking.joinToString("\n"))
        val start = Regex("(?:^|\\n)\\s*(?:思考|分析|思路)[:：]").find(text)
        val end = Regex("\\n\\s*(?:解答|答案|题目)[:：]").find(text)
        return if (start != null && end != null && end.range.first > start.range.last) {
            Parts(text.substring(0, start.range.first) + text.substring(end.range.first),
                text.substring(start.range.last + 1, end.range.first))
        } else Parts(text, null)
    }
}
