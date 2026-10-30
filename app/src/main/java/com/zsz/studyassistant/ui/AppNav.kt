package com.zsz.studyassistant.ui

import androidx.navigation.NavHostController

/**
 * 全应用**页面树**（唯一导航事实源）。
 *
 * 为什么要有这个文件（2026-10-18，用户要求「把页面/子页面的跳转关系理清，弄成目录树」）：
 *   以前 `nav.navigate("solve")` 散在各屏，返回全靠临场 `if` 猜（例如解题页用 `vm.isPhotoSession`
 *   判断"要不要回拍题界面"）→ 于是出现「从**搜题记录**打开一道**拍过照的**题，返回却跳到拍照界面」。
 *   现在：① 进解题页必须声明**来路**（[Origin]），返回按来路走，不再猜；
 *         ② 设置/数据管理这两个内部页用 [SettingsPages] 的树算"上一级"。
 *
 * 页面树（NavHost 路由）：
 * ```
 * home（底部页签「学习」）
 * ├── camera ──→ crop ──→ solve(camera)     拍照搜题：中间页用完即弃，栈 = home → solve
 * ├── solve(home)                           图文提问
 * ├── grade ──→ solve(grade)                批改
 * ├── history ──→ solve(history)            🕘 搜题记录（**保留来路**）
 * ├── notebook ──→ solve(notebook)          📚 错题本
 * ├── review ──→ solve(review)              📖 复习
 * ├── similar                               练同类题（从解题页进）
 * ├── stats（底部页签）                       📊 统计
 * └── settings（底部页签）                     ⚙️ 设置 ──→ 见 [SettingsPages]
 * ```
 */
object AppNav {

    /** System backup-failure notification → settings → data → backup. */
    const val OPEN_BACKUP_EXTRA = "open_backup_management"

    /** Update download notification → settings → update; Back returns to Settings. */
    const val OPEN_UPDATE_EXTRA = "open_app_update"

    /** Review notification → the current review entry. */
    const val OPEN_REVIEW_EXTRA = "open_review"

    // ── NavHost 路由（节点）──────────────────────────────────────────────
    // Home startup: API Key dialog → optional automatic-backup folder setup (dialogs, not navigation nodes).
    const val HOME = "home"
    const val CAMERA = "camera"
    const val CROP = "crop"
    const val GRADE = "grade"
    const val SOLVE = "solve"                 // 路由模板：[SOLVE_PATTERN]
    const val SOLVE_PATTERN = "solve/{origin}"
    const val HISTORY = "history"
    const val NOTEBOOK = "notebook"
    const val REVIEW = "review"
    const val SIMILAR = "similar"
    const val STATS = "stats"
    const val SETTINGS = "settings"

    val rootTabs = listOf(HOME, STATS, SETTINGS)
    fun adjacentRootTab(route: String?, next: Boolean): String? {
        val index = rootTabs.indexOf(route)
        if (index < 0) return null
        return rootTabs.getOrNull(index + if (next) 1 else -1)
    }
    fun rootTab(route: String?): String = when (route) {
        SETTINGS -> SETTINGS
        STATS -> STATS
        else -> HOME
    }
    fun showsBottomBar(route: String?): Boolean = route in rootTabs || route in setOf(NOTEBOOK, REVIEW, HISTORY)

    /**
     * 解题页的**来路** = 页面树上 `solve` 的父节点。
     * `returnsToCamera`：拍题流程返回时回拍题页（方便再拍一张），其余一律返回上一页。
     */
    enum class Origin(val key: String, val returnsToCamera: Boolean) {
        HOME("home", false),
        CAMERA("camera", true),
        GRADE("grade", false),
        HISTORY("history", false),
        NOTEBOOK("notebook", false),
        REVIEW("review", false),
        SETTINGS("settings", false);

        companion object {
            fun of(key: String?): Origin = entries.firstOrNull { it.key == key } ?: HOME
        }
    }

    fun solve(origin: Origin): String = "$SOLVE/${origin.key}"

    /** 分享图片复用框选流程，主页保持为返回栈根节点。 */
    fun openSharedCrop(nav: NavHostController) {
        nav.navigate(CROP) { popUpTo(HOME); launchSingleTop = true }
    }

    /** 拍照 → 框选：拍题页用完即弃（栈 = home → crop），保证返回键不会回到相机预览 */
    fun openCrop(nav: NavHostController) {
        nav.navigate(CROP) { popUpTo(CAMERA) { inclusive = true } }
    }

    /**
     * **进入解题页的唯一入口**。
     * - 拍照 / 批改 / 图文提问：中间页（camera、crop、grade）用完即弃 → 先弹回 home 再压解题页；
     * - 搜题记录 / 错题本 / 复习：**保留来路页面**再压解题页，这样返回才回得去。
     */
    fun openSolve(nav: NavHostController, origin: Origin) {
        when (origin) {
            Origin.CAMERA, Origin.GRADE, Origin.HOME -> nav.navigate(solve(origin)) { popUpTo(HOME) }
            Origin.HISTORY, Origin.NOTEBOOK, Origin.REVIEW, Origin.SETTINGS -> nav.navigate(solve(origin))
        }
    }

    /**
     * 解题页左上角「←」：按**来路**决定去处。
     * 原来是 `if (reviewMode || notebookMode || gradeMode || !isPhotoSession) popBackStack() else 回拍题页` ——
     * 「从搜题记录打开一条拍照记录」时 `isPhotoSession` 为 true，就错误地跳去了拍照界面（用户报的 bug）。
     */
    fun backFromSolve(nav: NavHostController, origin: Origin, startNewQuestion: () -> Unit) {
        if (origin.returnsToCamera) {
            startNewQuestion()
            nav.navigate(CAMERA) { popUpTo(HOME) }
        } else if (!nav.popBackStack()) {
            nav.navigate(HOME) {
                popUpTo(HOME) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    fun openReview(nav: NavHostController) {
        switchTab(nav, HOME)
        nav.navigate(REVIEW) { launchSingleTop = true }
    }

    /** 底部页签始终重建目标主页，丢弃已离开页签的子页状态；配置重建仍由各页保存状态。 */
    fun switchTab(nav: NavHostController, route: String) {
        nav.navigate(route) {
            // HOME itself may already have been removed by a previous tab switch.
            // The graph remains present, so this also removes any old Settings entry and its saved child.
            popUpTo(nav.graph.id) { inclusive = false; saveState = false }
            launchSingleTop = true
            restoreState = false
        }
    }

    /**
     * 「设置」页内部 NavHost 的子页树。
     * ```
     * 设置(main)
     * ├── ai          AI 管理 ──┬── ai.api     API 管理（含用量统计）
     * │                        ├── ai.crop    AI 切图
     * │                        ├── ai.solve   解题设置
     * │                        └── ai.prompt  自定义要求
     * ├── lang        语言设置
     * ├── theme       应用主题
     * ├── notify      通知与后台
     * ├── data        数据管理 ──┬── data.backup   备份管理（含自动备份）
     * │                        ├── data.tags     标签整理
     * │                        ├── data.crash    崩溃日志
     * │                        └── data.upgrade  数据升级 / 修复
     * ├── update      应用更新（下载通知可直达，返回设置主页）
     * └── about       关于
     * ```
     */
    object SettingsPages {
        const val MAIN = "main"
        const val AI = "ai"
        const val AI_API = "ai.api"
        const val AI_CROP = "ai.crop"
        const val AI_SOLVE = "ai.solve"
        const val AI_PROMPT = "ai.prompt"
        const val LANG = "lang"
        const val THEME = "theme"
        const val NOTIFY = "notify"
        const val DATA = "data"
        const val DATA_BACKUP = "data.backup"
        const val DATA_CRASH = "data.crash"
        const val DATA_UPGRADE = "data.upgrade"
        const val DATA_TAG_MERGE = "data.tags"
        const val UPDATE = "update"
        const val ABOUT = "about"

        /** Complete registry, including leaf settings pages without children. */
        val all = setOf(MAIN, AI, AI_API, AI_CROP, AI_SOLVE, AI_PROMPT, LANG, THEME, NOTIFY,
            DATA, DATA_BACKUP, DATA_CRASH, DATA_UPGRADE, DATA_TAG_MERGE, UPDATE, ABOUT)

        /** Direct notification/repair entries must retain the same Back parents as menu entries. */
        fun path(page: String): List<String> = buildList {
            require(page in all)
            var current = page
            add(current)
            while (current != MAIN) {
                current = parent(current)
                add(current)
            }
            reverse()
        }

        fun open(nav: NavHostController, page: String) {
            require(page in all)
            val current = nav.currentDestination?.route
            if (current == page) return
            if (page == MAIN) {
                nav.popBackStack(MAIN, false)
                return
            }
            if (parent(page) != current) {
                nav.popBackStack(MAIN, false)
                path(page).drop(1).dropLast(1).forEach { ancestor ->
                    nav.navigate(ancestor) { launchSingleTop = true }
                }
            }
            nav.navigate(page) { launchSingleTop = true }
        }

        fun back(nav: NavHostController) {
            val current = nav.currentDestination?.route ?: return
            if (current != MAIN && !nav.popBackStack(parent(current), false)) open(nav, parent(current))
        }

        /** 所有设置页的唯一父节点：AI 子页 → AI 管理，数据子页 → 数据管理，其余 → 设置主页。 */
        fun parent(page: String): String = when (page) {
            MAIN -> MAIN
            AI_API, AI_CROP, AI_SOLVE, AI_PROMPT -> AI
            DATA_BACKUP, DATA_CRASH, DATA_UPGRADE, DATA_TAG_MERGE -> DATA
            else -> MAIN
        }
    }
}
