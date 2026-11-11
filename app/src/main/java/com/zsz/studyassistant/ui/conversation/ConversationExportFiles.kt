package com.zsz.studyassistant.ui

internal fun conversationExportName(input: String, fallback: String): String {
    val cleaned = input.trim().replace(Regex("(?i)\\.(pdf|png)$"), "")
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim(' ', '.')
    val bounded = cleaned.codePoints().limit(72).toArray().let { String(it, 0, it.size) }.trimEnd(' ', '.')
    if (bounded.isBlank()) return fallback
    return if (Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$").matches(bounded)) "SA-$bounded" else bounded
}
