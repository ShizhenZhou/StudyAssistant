package com.zsz.studyassistant.data

import android.content.Context

internal const val UNCATEGORIZED_ORDER_ID = -1L

/** Synthetic Uncategorized key is an appearance preference, never a database category. */
internal fun categoryChipOrder(ids: List<Long>, counts: Map<Long, Int>, custom: Boolean, saved: List<Long>): List<Long> {
    val named = ids.distinct().sortedByDescending { counts[it] ?: 0 }
    val automatic = if ((counts[UNCATEGORIZED_ORDER_ID] ?: 0) > 0) listOf(UNCATEGORIZED_ORDER_ID) + named
        else named + UNCATEGORIZED_ORDER_ID
    if (!custom) return automatic
    val live = automatic.toSet()
    val known = saved.filter { it in live }.distinct()
    return known + automatic.filterNot { it in known }
}

internal fun moveCategoryOrder(ids: List<Long>, moving: Long, target: Long): List<Long> {
    val from = ids.indexOf(moving)
    val to = ids.indexOf(target)
    if (from < 0 || to < 0 || from == to) return ids
    return ids.toMutableList().apply { add(to, removeAt(from)) }
}

internal class CategoryOrderPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun enabled(): Boolean = prefs.getBoolean("category_order_custom", false)
    fun order(): List<Long> = prefs.getString("category_order_ids", "").orEmpty().split(',')
        .mapNotNull { it.toLongOrNull() }.filter { it > 0 || it == UNCATEGORIZED_ORDER_ID }.distinct()
    fun setEnabled(enabled: Boolean) { prefs.edit().putBoolean("category_order_custom", enabled).apply() }
    fun save(order: List<Long>) {
        prefs.edit().putString("category_order_ids", order.filter { it > 0 || it == UNCATEGORIZED_ORDER_ID }
            .distinct().joinToString(",")).apply()
    }
}
