package com.zsz.studyassistant.ui

import java.io.InputStream

internal fun changelogText(lang: UiLang, openAsset: (String) -> InputStream): String {
    val file = when (lang) {
        UiLang.EN -> "en"
        UiLang.JA -> "ja"
        UiLang.KO -> "ko"
        UiLang.SYSTEM, UiLang.ZH_CN, UiLang.ZH_TW -> "zh"
    }
    val text = openAsset("changelog/$file.txt").bufferedReader(Charsets.UTF_8).use {
        it.readText().removeSuffix("\n")
    }
    return if (lang == UiLang.ZH_TW) s2t(text) else text
}
