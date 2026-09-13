package com.zsz.studyassistant.ui

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

/**
 * 界面语言（App 文案语言），与「AI 生成语言」是两件事、分开设置。
 * id 与 data.AiLang 保持一致，方便共用语言名。
 */
enum class UiLang(val id: String) {
    SYSTEM("system"),
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
        val DEFAULT = SYSTEM
        fun fromId(id: String?): UiLang = values().firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** 语言自称：各语言用自己的写法，不翻译 */
internal fun nativeName(id: String): String = when (id) {
    "zh-CN" -> "简体中文"
    "zh-TW" -> "繁體中文"
    "en" -> "English"
    "ja" -> "日本語"
    "ko" -> "한국어"
    "de" -> "Deutsch"
    "fr" -> "Français"
    "es" -> "Español"
    "ru" -> "Русский"
    else -> id
}

/**
 * 文案查询器。
 * 查表顺序：当前语言 → 简体中文 → key 本身（漏翻时界面会直接显示 key，便于发现）
 */
class Strings internal constructor(
    private val map: Map<String, String>,
    private val traditional: Boolean = false
) {
    operator fun get(key: String): String {
        val v = map[key] ?: ZH[key] ?: return key
        return if (traditional) s2t(v) else v
    }

    /** 带占位符的文案，如 format("notebook.count", "n" to "3") 对应 "共 {n} 道" */
    fun format(key: String, vararg args: Pair<String, String>): String {
        var out = get(key)
        for ((k, v) in args) out = out.replace("{$k}", v)
        return out
    }
}

/** 当前语言的文案表（在 MainActivity 顶层 provide） */
val LocalStrings = staticCompositionLocalOf { Strings(emptyMap()) }

/** 系统语言 → 支持的语言（未覆盖的系统语言用英文，而不是回落中文） */
fun systemUiLang(): UiLang {
    val l = Locale.getDefault()
    return when {
        l.language == "zh" && (l.country == "TW" || l.country == "HK" || l.country == "MO") -> UiLang.ZH_TW
        l.language == "zh" -> UiLang.ZH_CN
        l.language == "en" -> UiLang.EN
        l.language == "ja" -> UiLang.JA
        l.language == "ko" -> UiLang.KO
        l.language == "de" -> UiLang.DE
        l.language == "fr" -> UiLang.FR
        l.language == "es" -> UiLang.ES
        l.language == "ru" -> UiLang.RU
        else -> UiLang.EN
    }
}

/** 取某语言的文案表 */
fun stringsFor(lang: UiLang): Strings = when (lang) {
    UiLang.SYSTEM -> stringsFor(systemUiLang())
    UiLang.ZH_CN -> Strings(ZH)
    UiLang.ZH_TW -> Strings(emptyMap(), traditional = true)   // 由简体自动转繁体（见 s2t）
    UiLang.EN -> Strings(EN)
    UiLang.JA -> Strings(JA)
    UiLang.KO -> Strings(KO)
    UiLang.DE -> Strings(DE)
    UiLang.FR -> Strings(FR)
    UiLang.ES -> Strings(ES)
    UiLang.RU -> Strings(RU)
}

/** 设置页显示的名称：「跟随系统」本地化，其余用语言自称 */
fun UiLang.displayLabel(s: Strings): String =
    if (this == UiLang.SYSTEM) "${s["lang.followSystem"]}（${nativeName(systemUiLang().id)}）" else nativeName(id)

/** 应用语言设置的持久化（与主题、AI 语言同用 settings 这个 SharedPreferences） */
object UiLangStore {
    private const val PREFS = "settings"
    private const val KEY = "ui_lang"

    fun load(c: Context): UiLang = UiLang.fromId(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(c: Context, lang: UiLang) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang.id).apply()
    }
}

// ---------------------------------------------------------------------------
// 简体 → 繁体转换（供「繁體中文」界面用）
//
// 规则：只收录本 App 文案真实出现、且繁体写法唯一无歧义的字；
// 每个元素必须恰好两个字符（前者简体、后者繁体），所以不存在拼接错位风险。
// 「台」「里」「只」「系」「余」等多义字**不做逐字映射**，改用下面的词级替换处理。
// 新增文案若出现未收录的字，补一对即可。
// ---------------------------------------------------------------------------
private val S2T_PAIRS: List<String> = listOf(
    // 高频通用
    "个個", "为為", "习習", "书書", "买買", "卖賣", "产產", "仅僅", "从從", "会會",
    "体體", "价價", "来來", "两兩", "于於", "与與", "这這", "无無", "变變", "时時",
    "点點", "说說", "语語", "请請", "读讀", "认認", "记記", "讲講", "许許", "话話",
    "该該", "详詳", "误誤", "论論", "证證", "试試", "设設", "备備", "应應",
    // 学科/解题
    "题題", "数數", "学學", "图圖", "电電", "压壓", "计計", "则則", "类類", "练練",
    "组組", "结結", "线線", "络絡", "网網", "统統", "继繼", "续續", "编編", "级級",
    "称稱", "积積", "标標", "签簽", "简簡", "辑輯", "录錄", "选選", "删刪",
    // 界面/操作
    "页頁", "显顯", "关關", "闭閉", "开開", "问問", "间間", "响響",
    "么麼", "启啟", "后後", "随隨", "机機", "输輸", "复復", "权權", "条條", "检檢",
    "现現", "确確", "择擇", "摄攝", "员員", "构構", "导導", "览覽", "视視", "软軟",
    "键鍵", "盘盤", "盖蓋", "频頻", "额額", "银銀", "钱錢", "钟鐘", "错錯", "长長",
    "针針", "项項", "顿頓", "骤驟", "难難", "韩韓", "辅輔", "败敗", "贴貼", "过過",
    "还還", "连連", "运運", "处處", "归歸", "纳納", "别別", "对對", "并並", "划劃",
    "荣榮", "华華", "单單", "务務", "动動", "击擊", "决決", "写寫", "吗嗎", "号號",
    "国國", "师師", "带帶", "当當", "将將", "属屬", "头頭",
    // 备用（当前文案可能未用到，补上以防漏字）
    "缓緩", "忆憶", "准準", "尽盡", "历歷", "丽麗", "词詞", "义義", "议議", "训訓"
)

/** 词级替换：处理多义字（先于逐字映射执行，长的优先） */
private val S2T_WORDS: List<String> = listOf(
    "复习複習",   // 复习 → 複習（不是「復習」）
    "复制複製",
    "恢复恢復",
    "关系關係",   // 关系 → 關係（而「系统」保持「系統」）
    "联系聯繫",
    "这里這裡", "哪里哪裡", "那里那裡", "里面裡面",
    "剩余剩餘",
    "头发頭髮",
    "标准標準"
)

private val S2T: Map<Char, Char> = buildMap {
    for (p in S2T_PAIRS) {
        if (p.length == 2 && p[0] != p[1]) put(p[0], p[1])
    }
}

internal fun s2t(s: String): String {
    var out = s
    for (w in S2T_WORDS) {
        if (w.length >= 4) {
            val from = w.substring(0, w.length / 2)
            val to = w.substring(w.length / 2)
            if (out.contains(from)) out = out.replace(from, to)
        }
    }
    var need = false
    for (c in out) if (S2T.containsKey(c)) { need = true; break }
    if (!need) return out
    val sb = StringBuilder(out.length)
    for (c in out) sb.append(S2T[c] ?: c)
    return sb.toString()
}
