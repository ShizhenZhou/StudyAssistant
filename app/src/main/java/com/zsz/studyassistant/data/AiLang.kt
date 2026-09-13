package com.zsz.studyassistant.data

import android.content.Context
import java.util.Locale

/**
 * AI 生成语言（解答用什么语言写）。
 * FOLLOW_SYSTEM / FOLLOW_QUESTION 为跟随模式，其余为固定语言。
 */
enum class AiLang(val id: String) {
    FOLLOW_SYSTEM("system"),
    FOLLOW_QUESTION("question"),
    ZH_CN("zh-CN"),
    ZH_TW("zh-TW"),
    EN("en"),
    JA("ja"),
    KO("ko"),
    DE("de"),
    FR("fr"),
    ES("es"),
    RU("ru");

    companion object {
        val DEFAULT = FOLLOW_SYSTEM
        fun fromId(id: String?): AiLang = values().firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** 设置的持久化（与主题等同用 settings 这个 SharedPreferences） */
object AiLangStore {
    private const val PREFS = "settings"
    private const val KEY = "ai_lang"

    fun load(c: Context): AiLang =
        AiLang.fromId(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(c: Context, lang: AiLang) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang.id).apply()
    }
}

/** 手机系统语言的中文名（用于「跟随系统」的显示与提示词） */
fun systemLangName(): String {
    val l = Locale.getDefault()
    return when {
        l.language == "zh" && (l.country == "TW" || l.country == "HK" || l.country == "MO") -> "繁體中文"
        l.language == "zh" -> "简体中文"
        l.language == "en" -> "English"
        l.language == "ja" -> "日本語"
        l.language == "ko" -> "한국어"
        l.language == "de" -> "Deutsch"
        l.language == "fr" -> "Français"
        l.language == "es" -> "Español"
        l.language == "ru" -> "Русский"
        else -> l.displayName.ifBlank { l.language }
    }
}

/** 固定语言的中文名（提示词里用中文说明最稳，附带本地写法便于模型对齐） */
internal fun AiLang.zhNameWithNative(): String = when (this) {
    AiLang.ZH_CN -> "简体中文"
    AiLang.ZH_TW -> "繁體中文"
    AiLang.EN -> "英语（English）"
    AiLang.JA -> "日语（日本語）"
    AiLang.KO -> "韩语（한국어）"
    AiLang.DE -> "德语（Deutsch）"
    AiLang.FR -> "法语（Français）"
    AiLang.ES -> "西班牙语（Español）"
    AiLang.RU -> "俄语（Русский）"
    else -> "简体中文"
}

/** 设置页显示名称：固定语言用其本族写法；跟随模式把当前解析到的语言写在括号里，避免用户不知道「跟随」跟到哪 */
fun AiLang.displayName(): String = when (this) {
    AiLang.FOLLOW_SYSTEM -> "跟随系统（${systemLangName()}）"
    AiLang.FOLLOW_QUESTION -> "跟随题目"
    AiLang.ZH_CN -> "简体中文"
    AiLang.ZH_TW -> "繁體中文"
    AiLang.EN -> "English"
    AiLang.JA -> "日本語"
    AiLang.KO -> "한국어"
    AiLang.DE -> "Deutsch"
    AiLang.FR -> "Français"
    AiLang.ES -> "Español"
    AiLang.RU -> "Русский"
}

/** 发给模型的「回答语言」要求（统一由 Data 层拼，避免各调用点写法不一致） */
fun languageInstruction(lang: AiLang): String = when (lang) {
    AiLang.FOLLOW_QUESTION ->
        "回答语言：请使用与题目相同的语言作答（题目是中文就用中文，是英文就用英文，其余同理）。"
    AiLang.FOLLOW_SYSTEM ->
        "回答语言：请使用${systemLangName()}作答。"
    else ->
        "回答语言：请使用${lang.zhNameWithNative()}作答。"
}
