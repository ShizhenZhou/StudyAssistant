package com.zsz.studyassistant.data

/**
 * 从会话 JSON 里取出**题目那条消息的附图**（base64 列表）。
 *
 * ## 为什么需要它
 * 图片有两个落点：
 *  - **专用字段** `Question.imageBytes`（单张 BLOB）—— 错题本卡片缩略图、复习页自测原图只读它；
 *  - **会话 JSON** `conversationJson`（`ChatItem.images`，base64 列表）—— 解题页气泡用它。
 *
 * 而 **多图拍题**（`solveWithImages`）与**图文提问**（`solveDirect`）这两条链路**从不给 `imageBytes` 赋值**
 * （它们只有 `multiImages` / `directImages`），于是存进错题本后：
 * 错题本卡片与复习页**看不到原图**（用户 2026-10-14 报的"加错题本后原图仍会丢，多图时更容易"）。
 * 实际上这些图**一直躺在 `conversationJson` 里**，所以读取端可以用本函数兜底还原，
 * 不必改数据库结构、也不必做数据迁移（旧数据在启动时由 `MainViewModel.backfillQuestionImages()` 就地补好）。
 *
 * ## 实现约束
 * **纯字符串解析**，不依赖 kotlinx.serialization，也不依赖 Android 的 `Base64` ——
 * 这样它能在纯 JVM 单测里回归（见 `ConversationImagesTest`）。
 * 解析是**字符串感知**的（区分"键"与"字符串内容里的同形文字"，也跳过 `\"` 转义），因此：
 *  - `content` 里出现 `role=question` 之类的字面文字不会误判；
 *  - **题目那条没有图**（`"images":null`）时不会被后面答案里的图骗走（这是最初版本的 bug，已由单测钉住）。
 * JSON 由本项目自己的序列化器产出：`[{"id":..,"role":"question","content":"..","images":[".."],"thinking":..}, ..]`，
 * 且**题目气泡永远是第 0 条**。
 */
internal fun questionImageBase64(conversationJson: String?): List<String> {
    val s = conversationJson ?: return emptyList()
    // 1) 找到"题目"那条消息的 role 键（字符串感知，避免命中 content 里的同形文字）
    val roleAt = indexOfOutsideString(s, "\"role\":\"question\"")
    if (roleAt < 0) return emptyList()
    // 2) 定位它所在的那个对象：题目是第 0 条，它前面唯一的 `{` 就是自己的起始
    val objStart = s.lastIndexOf('{', roleAt)
    if (objStart < 0) return emptyList()
    val objEnd = endOfObject(s, objStart)
    if (objEnd < 0) return emptyList()
    // 3) 只在这个对象范围内找 `"images"`；若为 null（题目没图）直接返回空，绝不去后面答案里找
    val imgKey = indexOfOutsideString(s, "\"images\":", roleAt)
    if (imgKey < 0 || imgKey >= objEnd) return emptyList()
    var i = imgKey + "\"images\":".length
    while (i < objEnd && s[i].isWhitespace()) i++
    if (i >= objEnd || s[i] != '[') return emptyList()      // null / 缺省
    val arrEnd = endOfArray(s, i)
    if (arrEnd < 0) return emptyList()
    return stringLiteralsIn(s, i + 1, arrEnd)
}

/** 在**字符串之外**查找 [needle]（跳过 `\"` 转义），找不到返回 -1 */
private fun indexOfOutsideString(s: String, needle: String, from: Int = 0): Int {
    var i = from
    var inStr = false
    var esc = false
    while (i < s.length) {
        val c = s[i]
        if (inStr) {
            when {
                esc -> esc = false
                c == '\\' -> esc = true
                c == '"' -> inStr = false
            }
        } else {
            // ⚠️ 必须先判 needle 再判引号：needle 本身就以 `"` 开头，
            //   顺序反了会永远命中"进入字符串"分支，键就再也找不到了（曾经的 bug）
            if (s.startsWith(needle, i)) return i
            if (c == '"') inStr = true
        }
        i++
    }
    return -1
}

/** 返回 [start] 处 `{` 的配对 `}` 下标（字符串感知），失败返回 -1 */
private fun endOfObject(s: String, start: Int): Int = endOfBracket(s, start, '{', '}')

/** 返回 [start] 处 `[` 的配对 `]` 下标（字符串感知），失败返回 -1 */
private fun endOfArray(s: String, start: Int): Int = endOfBracket(s, start, '[', ']')

private fun endOfBracket(s: String, start: Int, open: Char, close: Char): Int {
    if (start >= s.length || s[start] != open) return -1
    var depth = 0
    var i = start
    var inStr = false
    var esc = false
    while (i < s.length) {
        val c = s[i]
        if (inStr) {
            when {
                esc -> esc = false
                c == '\\' -> esc = true
                c == '"' -> inStr = false
            }
        } else when (c) {
            '"' -> inStr = true
            open -> depth++
            close -> {
                depth--
                if (depth == 0) return i
            }
        }
        i++
    }
    return -1
}

/** 取出 `[from, to)` 区间里的字符串字面量（忽略转义），用于解析 base64 数组 */
private fun stringLiteralsIn(s: String, from: Int, to: Int): List<String> {
    val out = mutableListOf<String>()
    var i = from
    while (i < to) {
        if (s[i] == '"') {
            val sb = StringBuilder()
            i++
            var esc = false
            while (i < to) {
                val c = s[i]
                if (esc) { sb.append(c); esc = false }
                else if (c == '\\') esc = true
                else if (c == '"') break
                else sb.append(c)
                i++
            }
            out += sb.toString()
        }
        i++
    }
    return out
}
