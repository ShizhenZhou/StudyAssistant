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
    fun solveDouble(c: Context): Boolean = prefs(c).getBoolean(KEY_SOLVE_DOUBLE, false)

    fun setSolveDouble(c: Context, v: Boolean) =
        prefs(c).edit().putBoolean(KEY_SOLVE_DOUBLE, v).apply()

    /** 批改页：上次是否为「两张」模式 */
    fun gradeDouble(c: Context): Boolean = prefs(c).getBoolean(KEY_GRADE_DOUBLE, false)

    fun setGradeDouble(c: Context, v: Boolean) =
        prefs(c).edit().putBoolean(KEY_GRADE_DOUBLE, v).apply()
}
