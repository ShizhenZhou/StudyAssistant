package com.zsz.studyassistant.data

import android.content.Context

/**
 * 拍照相关的小偏好（与主题/语言共用 "settings" 这个 SharedPreferences）。
 * 目前只存「单张 / 两张」模式：记住用户上次的选择，下次进页面直接沿用。
 */
object CapturePrefs {
    private const val PREFS = "settings"
    private const val KEY_SOLVE_DOUBLE = "camera_double_mode"   // 拍照搜题页
    private const val KEY_GRADE_DOUBLE = "grade_double_mode"    // 批改页

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 拍照搜题页：上次是否为「两张」模式 */
    // ---- 用户自定义 Prompt（附加到每次 AI 请求，用于"只给思路/指定语言/更详细…"等个人要求）----
    private const val KEY_CUSTOM_PROMPT = "custom_prompt"
    fun customPrompt(c: Context): String = prefs(c).getString(KEY_CUSTOM_PROMPT, "") ?: ""
    fun setCustomPrompt(c: Context, v: String) =
        prefs(c).edit().putString(KEY_CUSTOM_PROMPT, v).apply()
    fun solveDouble(c: Context): Boolean = prefs(c).getBoolean(KEY_SOLVE_DOUBLE, false)

    fun setSolveDouble(c: Context, v: Boolean) =
        prefs(c).edit().putBoolean(KEY_SOLVE_DOUBLE, v).apply()

    private const val KEY_GRADE_TOGGLE = "camera_grade_toggle"   // 拍照页右上角「批改」开关

    /** 拍照页右上角开关：true = 批改模式 */
    fun gradeToggle(c: Context): Boolean = prefs(c).getBoolean(KEY_GRADE_TOGGLE, false)

    fun setGradeToggle(c: Context, v: Boolean) =
        prefs(c).edit().putBoolean(KEY_GRADE_TOGGLE, v).apply()

private const val KEY_AI_CROP_MS = "ai_crop_timeout_ms"   // AI 框选时限（毫秒）

    /** 「通用 → AI 框选时限」：AI 自动框选的等待上限，默认 500ms，范围 100~5000ms */
    fun aiCropTimeoutMs(c: Context): Long =
        prefs(c).getLong(KEY_AI_CROP_MS, 500L).coerceIn(100L, 5000L)

    fun setAiCropTimeoutMs(c: Context, v: Long) =
        prefs(c).edit().putLong(KEY_AI_CROP_MS, v.coerceIn(100L, 5000L)).apply()

    /** 批改页：上次是否为「两张」模式 */
    fun gradeDouble(c: Context): Boolean = prefs(c).getBoolean(KEY_GRADE_DOUBLE, false)

    fun setGradeDouble(c: Context, v: Boolean) =
        prefs(c).edit().putBoolean(KEY_GRADE_DOUBLE, v).apply()
}
