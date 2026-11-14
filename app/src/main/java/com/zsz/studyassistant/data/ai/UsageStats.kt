package com.zsz.studyassistant.data

import android.content.Context

/**
 * API 用量统计（累计调用次数与 token 数）。
 * 只做本机累计，不上报任何数据；存在 settings 这个 SharedPreferences 里。
 */
object UsageStats {
    private const val PREFS = "settings"
    private const val K_CALLS = "usage_calls"
    private const val K_PROMPT = "usage_prompt_tokens"
    private const val K_COMPLETION = "usage_completion_tokens"

    data class Snapshot(val calls: Int, val promptTokens: Int, val completionTokens: Int, val cacheTokens: Int, val searches: Int, val fetches: Int, val imageSearches: Int)

    @Synchronized
    fun load(c: Context): Snapshot {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Snapshot(
            calls = p.getInt(K_CALLS, 0),
            promptTokens = p.getInt(K_PROMPT, 0),
            completionTokens = p.getInt(K_COMPLETION, 0),
            cacheTokens = p.getInt("usage_cache", 0), searches = p.getInt("usage_search", 0),
            fetches = p.getInt("usage_fetch", 0), imageSearches = p.getInt("usage_image_search", 0)
        )
    }

    /** 记一次调用（usage 为空时只累加次数） */
    @Synchronized
    fun add(c: Context, usage: DeepSeekUsage?) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit()
            .putInt(K_CALLS, p.getInt(K_CALLS, 0) + 1)
            .putInt(K_PROMPT, p.getInt(K_PROMPT, 0) + (usage?.promptTokens ?: 0))
            .putInt(K_COMPLETION, p.getInt(K_COMPLETION, 0) + (usage?.completionTokens ?: 0))
            .putInt("usage_cache", p.getInt("usage_cache", 0) + (usage?.cacheHitTokens ?: 0).coerceAtLeast(0))
            .putInt("usage_search", p.getInt("usage_search", 0) + (usage?.serverToolUse?.searches ?: 0).coerceAtLeast(0))
            .putInt("usage_fetch", p.getInt("usage_fetch", 0) + (usage?.serverToolUse?.fetches ?: 0).coerceAtLeast(0))
            .putInt("usage_image_search", p.getInt("usage_image_search", 0) + (usage?.serverToolUse?.imageSearches ?: 0).coerceAtLeast(0))
            .apply()
    }

    @Synchronized
    fun reset(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(K_CALLS).remove(K_PROMPT).remove(K_COMPLETION)
            .remove("usage_cache").remove("usage_search").remove("usage_fetch").remove("usage_image_search").apply()
    }
}
