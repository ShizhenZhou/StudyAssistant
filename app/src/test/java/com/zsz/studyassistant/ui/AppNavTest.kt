package com.zsz.studyassistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面树（[AppNav]）的回归测试（2026-10-18）。
 *
 * 这一层结构就是为「跳转别再一个个试」而加的，所以把规则钉成用例：
 * · 解题页必须带**来路**；来路解析失败一律回 `home`（绝不能默认成"回拍照界面"）；
 * · 只有**拍照流程**（origin = camera）返回时才回拍题页 —— 「从搜题记录打开一条拍照记录」不算；
 * · 设置子页的"上一级"：数据管理四个子页 → 数据管理，其余 → 设置主页，且**父级不能是它自己**。
 */
class AppNavTest {
    @Test fun completeSettingsRegistryAlwaysReturnsToRoot() {
        val pages=AppNav.SettingsPages
        for (page in pages.all) {
            var cursor=page
            val seen=mutableSetOf<String>()
            while(cursor!=pages.MAIN) {
                assertTrue(seen.add(cursor))
                cursor=pages.parent(cursor)
                assertTrue(cursor in pages.all)
            }
        }
        assertEquals(AppNav.Origin.SETTINGS,AppNav.Origin.of("settings"))
        assertEquals("solve/settings",AppNav.solve(AppNav.Origin.SETTINGS))
    }


    @Test
    fun `解题页路由带上来路`() {
        assertEquals("solve/history", AppNav.solve(AppNav.Origin.HISTORY))
        assertEquals("solve/camera", AppNav.solve(AppNav.Origin.CAMERA))
        assertEquals("solve/home", AppNav.solve(AppNav.Origin.HOME))
        assertEquals("solve/notebook", AppNav.solve(AppNav.Origin.NOTEBOOK))
        assertEquals("solve/review", AppNav.solve(AppNav.Origin.REVIEW))
        assertEquals("solve/grade", AppNav.solve(AppNav.Origin.GRADE))
    }

    @Test
    fun `来路解析：空或未知一律回 home`() {
        assertEquals(AppNav.Origin.HOME, AppNav.Origin.of(null))
        assertEquals(AppNav.Origin.HOME, AppNav.Origin.of(""))
        assertEquals(AppNav.Origin.HOME, AppNav.Origin.of("nope"))
        assertEquals(AppNav.Origin.HOME, AppNav.Origin.of("SOLVE"))
        for (o in AppNav.Origin.entries) assertEquals(o, AppNav.Origin.of(o.key))
    }

    @Test
    fun `只有拍照流程返回时才回拍题页`() {
        val toCamera = AppNav.Origin.entries.filter { it.returnsToCamera }
        assertEquals(listOf(AppNav.Origin.CAMERA), toCamera)
        // 反例：搜题记录里的题**即使是拍照来的**，也不该回拍照界面（这就是用户报的 bug）
        assertTrue(!AppNav.Origin.HISTORY.returnsToCamera)
    }

    @Test
    fun `设置子页的上一级`() {
        val sp = AppNav.SettingsPages
        for (p in listOf(sp.AI_API, sp.AI_CROP, sp.AI_SOLVE)) assertEquals(sp.AI, sp.parent(p))
        assertEquals(sp.DATA, sp.parent(sp.DATA_BACKUP))
        assertEquals(sp.DATA, sp.parent(sp.DATA_CRASH))
        assertEquals(sp.DATA, sp.parent(sp.DATA_UPGRADE))
        assertEquals(sp.DATA, sp.parent(sp.DATA_TAG_MERGE))
        assertEquals(sp.MAIN, sp.parent(sp.DATA))
        for (p in listOf(sp.AI, sp.LANG, sp.THEME, sp.NOTIFY, sp.UPDATE, sp.ABOUT)) {
            assertEquals("$p 的上一级应为设置主页", sp.MAIN, sp.parent(p))
        }
        assertEquals(sp.MAIN, sp.parent(sp.MAIN))
    }

    @Test
    fun `二级页的上一级不能是它自己（否则返回键原地卡住）`() {
        val sp = AppNav.SettingsPages
        val secondLevel = listOf(
            sp.AI, sp.AI_API, sp.AI_CROP, sp.AI_SOLVE, sp.LANG, sp.THEME, sp.NOTIFY, sp.DATA,
            sp.DATA_BACKUP, sp.DATA_CRASH, sp.DATA_UPGRADE, sp.DATA_TAG_MERGE,
            sp.UPDATE, sp.ABOUT
        )
        for (p in secondLevel) assertNotEquals("$p 的上一级是它自己", p, sp.parent(p))
    }

    @Test
    fun `AI 和数据子页沿父节点返回设置主页且不存在环`() {
        val sp = AppNav.SettingsPages
        val children = listOf(sp.AI_API, sp.AI_CROP, sp.AI_SOLVE,
            sp.DATA_BACKUP, sp.DATA_CRASH, sp.DATA_UPGRADE, sp.DATA_TAG_MERGE)
        assertEquals(children.size, children.toSet().size)
        for (child in children) assertEquals(sp.MAIN, sp.parent(sp.parent(child)))
    }

    @Test
    fun `路由常量互不相同且非空`() {
        val routes = listOf(
            AppNav.HOME, AppNav.CAMERA, AppNav.CROP, AppNav.GRADE, AppNav.SOLVE,
            AppNav.HISTORY, AppNav.NOTEBOOK, AppNav.REVIEW, AppNav.SIMILAR,
            AppNav.STATS, AppNav.SETTINGS
        )
        assertEquals(routes.size, routes.toSet().size)
        assertTrue(routes.none { it.isBlank() })
        // 解题页的路由模板必须和 [AppNav.SOLVE] 对得上，否则 NavHost 匹配不到
        assertTrue(AppNav.SOLVE_PATTERN.startsWith(AppNav.SOLVE + "/{"))
    }
}
