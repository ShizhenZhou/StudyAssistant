package com.zsz.studyassistant

import android.app.Activity
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotlinx.coroutines.isActive
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zsz.studyassistant.ui.RootTabSwipeHost
import com.zsz.studyassistant.ui.AppNav
import com.zsz.studyassistant.ui.CameraScreen
import com.zsz.studyassistant.ui.CropScreen
import com.zsz.studyassistant.ui.GradeScreen
import com.zsz.studyassistant.ui.HomeScreen
import com.zsz.studyassistant.ui.LocalStrings
import com.zsz.studyassistant.ui.LocalUiLang
import com.zsz.studyassistant.ui.NotebookScreen
import com.zsz.studyassistant.ui.SettingsSubPage
import com.zsz.studyassistant.ui.TagMergePage
import com.zsz.studyassistant.ui.ReviewScreen
import com.zsz.studyassistant.ui.SettingsTab
import com.zsz.studyassistant.ui.UpdateBadgeDot
import com.zsz.studyassistant.ui.UpdateBadgeState
import com.zsz.studyassistant.ui.silentUpdateCheck
import com.zsz.studyassistant.ui.SimilarScreen
import com.zsz.studyassistant.ui.SolveScreen
import com.zsz.studyassistant.ui.SolveHistoryScreen
import com.zsz.studyassistant.ui.StatsScreen
import com.zsz.studyassistant.ui.stringsFor

private const val NAV_TOUCH_GUARD_MS = 250L   // 导航后吞掉内容区触摸的时长（防穿透）

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var wallpaperWindowMode = false

    override fun onStart() {
        super.onStart()
        com.zsz.studyassistant.data.BackupJobs.setForeground(true)
        com.zsz.studyassistant.data.AnswerForegroundService.setAppForeground(true)
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.zsz.studyassistant.data.BackupFolderAccess.refresh(applicationContext, scheduleIfAvailable = true)
        }
    }

    override fun onStop() {
        viewModel.flushInputDraft()
        com.zsz.studyassistant.data.BackupJobs.setForeground(false)
        com.zsz.studyassistant.data.AnswerForegroundService.setAppForeground(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshClock()
        com.zsz.studyassistant.data.ReminderScheduler.applySchedule(this)
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.sharedImages.receive(intent)
        com.zsz.studyassistant.data.NotificationIntents.consumeUpdateCompletion(this, intent)
        com.zsz.studyassistant.data.NotificationIntents.consumeAnswerCompletion(this, intent)
        if (intent.getBooleanExtra(AppNav.OPEN_BACKUP_EXTRA, false)) {
            intent.removeExtra(AppNav.OPEN_BACKUP_EXTRA)
            viewModel.openBackupManagementPage = true
        }
        if (intent.getBooleanExtra(AppNav.OPEN_UPDATE_EXTRA, false)) {
            intent.removeExtra(AppNav.OPEN_UPDATE_EXTRA)
            viewModel.openUpdatePage = true
        }
        if (intent.getBooleanExtra(AppNav.OPEN_REVIEW_EXTRA, false)) {
            intent.removeExtra(AppNav.OPEN_REVIEW_EXTRA)
            viewModel.requestReviewNavigation()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val draft = viewModel.solveInputDraft
        if (draft.fieldFocused && androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(
                androidx.core.view.WindowInsetsCompat.Type.ime()) == true) {
            outState.putString("solveEditingSession", draft.id)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        wallpaperWindowMode = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("wallpaper_background", false)
        if (wallpaperWindowMode) setTheme(R.style.Theme_StudyAssistant_Wallpaper)
        super.onCreate(savedInstanceState)
        viewModel.solveInputDraft.apply { restoreIme = savedInstanceState?.getString("solveEditingSession") == id }
        viewModel.sharedImages.receive(intent)
        com.zsz.studyassistant.data.NotificationIntents.consumeUpdateCompletion(this, intent)
        com.zsz.studyassistant.data.NotificationIntents.consumeAnswerCompletion(this, intent)
        if (wallpaperWindowMode && android.os.Build.VERSION.SDK_INT >= 30) setTranslucent(true)
        // 启动时清理已删除题目与孤儿记录（定期维护数据库）
        viewModel.purgeDeletedData()
        // 启动即创建通知渠道（否则系统"通知管理"会显示"未发布任何通知"），并按需申请通知权限
        com.zsz.studyassistant.data.ReminderScheduler.ensureChannel(this)
        com.zsz.studyassistant.data.UpdateNotifications.applySchedule(this)
        val backupPreferences = getSharedPreferences("settings", MODE_PRIVATE)
        val askBackupNotifications = com.zsz.studyassistant.data.AutoBackup.isEnabled(this) &&
            com.zsz.studyassistant.data.AutoBackup.treeUri(this) != null &&
            !backupPreferences.getBoolean("backup_failure_permission_asked", false)
        if (com.zsz.studyassistant.data.ReminderScheduler.isEnabled(this) || askBackupNotifications) {
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                if (askBackupNotifications) backupPreferences.edit().putBoolean("backup_failure_permission_asked", true).apply()
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        if (intent?.getBooleanExtra(AppNav.OPEN_REVIEW_EXTRA, false) == true) {
            intent.removeExtra(AppNav.OPEN_REVIEW_EXTRA)
            viewModel.requestReviewNavigation()
        }
        com.zsz.studyassistant.data.BackupNotifications.ensureChannel(this)
        if (intent?.getBooleanExtra(AppNav.OPEN_BACKUP_EXTRA, false) == true) {
            intent.removeExtra(AppNav.OPEN_BACKUP_EXTRA)
            viewModel.openBackupManagementPage = true
        }
        if (intent?.getBooleanExtra(AppNav.OPEN_UPDATE_EXTRA, false) == true) {
            intent.removeExtra(AppNav.OPEN_UPDATE_EXTRA)
            viewModel.openUpdatePage = true
        }
        // Only the visible UI needs midnight/time-zone refreshes. Background AI/backup
        // ownership remains independent of the Activity lifecycle.
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while (isActive) {
                    viewModel.refreshClock()
                    kotlinx.coroutines.delay(30_000)
                }
            }
        }
        enableEdgeToEdge()
        setContent {
            val dark = when (viewModel.theme) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            // 界面语言：在整棵 Compose 树之上提供文案表，切换后立即全局生效
            val strings = remember(viewModel.uiLang) { stringsFor(viewModel.uiLang) }
            val uiLangResolved = remember(viewModel.uiLang) {
                if (viewModel.uiLang == com.zsz.studyassistant.ui.UiLang.SYSTEM) com.zsz.studyassistant.ui.systemUiLang() else viewModel.uiLang
            }
            CompositionLocalProvider(LocalStrings provides strings, LocalUiLang provides uiLangResolved) {
            // C14 界面字号：只放大/缩小**文字**（dp 布局不变），所以用 Density(density, fontScale×档位)
            // 覆写 LocalDensity —— fontScale 只影响 sp→px 的换算，不影响 dp。
            val baseDensity = androidx.compose.ui.platform.LocalDensity.current
            val scaleFactor = viewModel.fontScale.factor
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit.Density(baseDensity.density, baseDensity.fontScale * scaleFactor)
            ) {
            MaterialTheme(
                // 配色：深色/浅色 + 用户选择的预设色板或自定义色（设置 → 应用主题）
                colorScheme = com.zsz.studyassistant.ui.appColorScheme(
                    dark, viewModel.themeColor, viewModel.customColor,
                    if (viewModel.wallpaperBackground) viewModel.wallpaperShade else com.zsz.studyassistant.ui.WallpaperShade.STANDARD
                ),
                // 字体：按钮/标签统一比 Material3 默认小 2sp（用户要求"所有按钮字号缩小 1~2 个字号"）
                typography = com.zsz.studyassistant.ui.appTypography()
            ) {
                // 状态栏透明 + 图标颜色跟随主题（浅色=深色图标，深色=浅色图标）
                val view = LocalView.current
                val surfaceColor = MaterialTheme.colorScheme.surface
                LaunchedEffect(viewModel.wallpaperBackground) {
                    // The native render surface must start with an alpha-capable window theme.
                    // Recreate retains the ViewModel and saved navigation state.
                    if (viewModel.wallpaperBackground != wallpaperWindowMode) recreate()
                }
                androidx.compose.runtime.DisposableEffect(view, viewModel.wallpaperBackground, surfaceColor) {
                    if (!view.isInEditMode) {
                        com.zsz.studyassistant.ui.applyWallpaperBackground(
                            (view.context as Activity).window, viewModel.wallpaperBackground, surfaceColor
                        )
                    }
                    onDispose { }
                }
                if (!view.isInEditMode) {
                    SideEffect {
                        val window = (view.context as Activity).window
                        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
                        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
                    }
                }
                com.zsz.studyassistant.ui.AppFeedbackLayout(viewModel) {
                    val feedback = com.zsz.studyassistant.ui.LocalAppFeedback.current
                    val nav = rememberNavController()
                    com.zsz.studyassistant.ui.SharedImageDialog(viewModel, nav)
                    val captureNotice = viewModel.captureRecoveryNoticeKey
                    LaunchedEffect(captureNotice) {
                        if (captureNotice != null) {
                            feedback.show(strings[captureNotice], isError = true, source = "capture")
                            viewModel.consumeCaptureRecoveryNotice()
                        }
                    }
                    val backStack by nav.currentBackStackEntryAsState()
                    val current = backStack?.destination?.route
                    LaunchedEffect(current) {
                        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                            if (current == AppNav.SOLVE_PATTERN || current == AppNav.SIMILAR) {
                                com.zsz.studyassistant.data.AnswerForegroundService.acknowledgeCompletion(this@MainActivity)
                            }
                        }
                    }
                    val updatePrompt by com.zsz.studyassistant.data.UpdatePromptStore.release.collectAsStateWithLifecycle()
                    val updateKey = updatePrompt?.let(com.zsz.studyassistant.data.UpdatePromptStore::identity)
                    var deferredThisVisit by remember(updateKey) { mutableStateOf(false) }
                    val requiredUpdate = updatePrompt?.mandatory == true && !deferredThisVisit
                    var requiredBackToast by remember { mutableStateOf<android.widget.Toast?>(null) }
                    fun showRequiredBackHint() {
                        requiredBackToast?.cancel()
                        // A system text bubble remains visible above the update dialog's window.
                        requiredBackToast = android.widget.Toast.makeText(applicationContext,
                            strings["update.prompt.cannotSkip"], android.widget.Toast.LENGTH_SHORT).also { it.show() }
                    }
                    androidx.compose.runtime.DisposableEffect(requiredUpdate) {
                        onDispose { requiredBackToast?.cancel(); requiredBackToast = null }
                    }
                    var updatePolicyReady by remember { mutableStateOf(false) }
                    LaunchedEffect(viewModel.openBackupManagementPage) {
                        if (viewModel.openBackupManagementPage && nav.currentDestination?.route != AppNav.SETTINGS) {
                            AppNav.switchTab(nav, AppNav.SETTINGS)
                        }
                    }
                    LaunchedEffect(viewModel.openUpdatePage) {
                        if (viewModel.openUpdatePage && nav.currentDestination?.route != AppNav.SETTINGS) {
                            AppNav.switchTab(nav, AppNav.SETTINGS)
                        }
                    }
                    LaunchedEffect(viewModel.openCrashReportPage) {
                        if (viewModel.openCrashReportPage && nav.currentDestination?.route != AppNav.SETTINGS) {
                            AppNav.switchTab(nav, AppNav.SETTINGS)
                        }
                    }
                    val homeScroll = androidx.compose.foundation.rememberScrollState()
                    val statsScroll = androidx.compose.foundation.rememberScrollState()
                    val settingsScroll = androidx.compose.foundation.rememberScrollState()
                    var swipeTransition by remember { mutableStateOf<Pair<String?, String>?>(null) }
                    LaunchedEffect(current) {
                        // Keep the completed gesture transition stable while its destination is active.
                        if (swipeTransition?.second != current) swipeTransition = null
                    }
                    val showBottomBar = AppNav.showsBottomBar(current) && !requiredUpdate
                    var settingsAtRoot by remember { mutableStateOf(true) }
                    var settingsRootRequest by remember { mutableStateOf(0) }
                    val tabScope = rememberCoroutineScope()
                    var tabSwitchJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                    val selectTab: (String) -> Unit = { target ->
                        tabSwitchJob?.cancel()
                        tabSwitchJob = tabScope.launch {
                            val route = nav.currentDestination?.route
                            val root = AppNav.rootTab(route)
                            if (route == AppNav.SETTINGS && !settingsAtRoot) {
                                settingsRootRequest += 1
                                snapshotFlow { settingsAtRoot }.first { it }
                            } else if (route != root) {
                                AppNav.switchTab(nav, root)
                                nav.currentBackStackEntry?.lifecycle?.currentStateFlow?.first {
                                    it == androidx.lifecycle.Lifecycle.State.RESUMED
                                }
                            }
                            if (root != target) AppNav.switchTab(nav, target)
                        }
                    }

                    LaunchedEffect(Unit) {
                        try { com.zsz.studyassistant.data.UpdatePromptStore.restore(this@MainActivity) }
                        finally { updatePolicyReady = true }
                        silentUpdateCheck(this@MainActivity)
                    }

                    LaunchedEffect(Unit) {
                        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                            while (isActive) {
                                com.zsz.studyassistant.data.AppNoticeStore.refresh(this@MainActivity)
                                // Local policy check once a minute; network only when due (15 min / failure retry).
                                kotlinx.coroutines.delay(com.zsz.studyassistant.data.AppNoticeRefreshPolicy.FOREGROUND_TICK)
                            }
                        }
                    }

                    // No replay: a background completion must not become a stale notice on return.
                    LaunchedEffect(feedback, strings) {
                        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                            com.zsz.studyassistant.data.BackupJobs.notices.collect { notice ->
                                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                                    if (notice.failed) feedback.showBackupFailure(strings, notice.automatic) {
                                        viewModel.openBackupManagementPage = true
                                    } else {
                                        feedback.dismiss("backup")
                                        feedback.show(strings[if (notice.automatic) "data.autoBackup.success" else "data.backupSuccess"])
                                    }
                                }
                            }
                        }
                    }

                    // ★ 2026-10-16（用户规格）：**更新后第一次进入应用**时，若数据还没升级 → 弹一次提示；
                    //   弹完即按 `last_seen_version` 记住，不再重复打扰。[去升级] 会跳到设置 →「数据升级 / 修复」。
                    LaunchedEffect(Unit) {
                        // 版本号用 PackageManager 取（项目未开启 BuildConfig 字段）
                        val ver = runCatching {
                            packageManager.getPackageInfo(packageName, 0).versionName
                        }.getOrNull() ?: "0"
                        viewModel.checkUpgradePromptOnce(ver)
                    }
                    if (viewModel.showUpgradePrompt) {
                        val s2 = com.zsz.studyassistant.ui.stringsFor(viewModel.uiLang)
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { viewModel.dismissUpgradePrompt() },
                            title = { androidx.compose.material3.Text(s2["data.upgradePromptTitle"]) },
                            text = { androidx.compose.material3.Text(s2["data.upgradePromptBody"]) },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    viewModel.dismissUpgradePrompt(goUpgrade = true)
                                    nav.navigate(AppNav.SETTINGS)
                                }) { androidx.compose.material3.Text(s2["data.upgradeNow"]) }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = { viewModel.dismissUpgradePrompt() }) {
                                    androidx.compose.material3.Text(s2["data.legacySkip"])
                                }
                            }
                        )
                    }

                    // 防触摸穿透：每次页面切换后用**状态标志**封锁内容区触摸 NAV_TOUCH_GUARD_MS。
                    // ⚠️ 这里必须由协程定时解锁，**不能**把 `SystemClock.uptimeMillis() < t` 之类的时间比较写进
                    //    组合条件——那只是组合期快照，页面若不再重组就永远不会解除，会变成
                    //    「界面正常显示但所有点击无反应」＝卡死（0.5.4 首版踩过这个坑）。
                    val navBlocking = remember { androidx.compose.runtime.mutableStateOf(false) }
                    val blockScope = androidx.compose.runtime.rememberCoroutineScope()
                    val blockJob = remember { androidx.compose.runtime.mutableStateOf<kotlinx.coroutines.Job?>(null) }
                    androidx.compose.runtime.DisposableEffect(nav) {
                        val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, destination, _ ->
                            // ★ 回到主页 → 停掉在跑的生成 + 清空所有拍摄/框选缓存（不保留任何"已框或未框"的图）
                            // ⚠️ 这里必须是 if 条件语句；曾误写成裸 lambda `{ … }`（没有 if）→ 块永不执行，
                            //    两个功能静默失效（发布前复查 git diff 时发现）
                            if (destination.route == AppNav.HOME) {
                                viewModel.stopActiveGeneration()
                                viewModel.clearCaptureCaches()
                            }
                            blockJob.value?.cancel()
                            blockJob.value = blockScope.launch {
                                navBlocking.value = true
                                kotlinx.coroutines.delay(NAV_TOUCH_GUARD_MS)
                                navBlocking.value = false
                            }
                        }
                        nav.addOnDestinationChangedListener(listener)
                        onDispose {
                            nav.removeOnDestinationChangedListener(listener)
                            blockJob.value?.cancel()
                        }
                    }

                    com.zsz.studyassistant.ui.AdaptiveMainLayout(showBottomBar, current, { selectTab(it) }) { bottomBar ->
                        // The sibling NavigationBar already owns the bottom system inset.
                        // Pass that ownership to child Scaffolds, keeping top/side insets intact.
                        RootTabSwipeHost(
                            route = current,
                            enabled = updatePolicyReady && !requiredUpdate && !navBlocking.value && current in AppNav.rootTabs &&
                                (current != AppNav.SETTINGS || settingsAtRoot),
                            modifier = Modifier.fillMaxSize().then(if (bottomBar)
                                Modifier.consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                                else Modifier),
                            onSelect = { target ->
                                // A swipe starts only on a ready root; commit once, with no asynchronous root-return step.
                                tabSwitchJob?.cancel()
                                swipeTransition = current to target
                                AppNav.switchTab(nav, target)
                            },
                            preview = { target ->
                                when (target) {
                                    AppNav.HOME -> HomeScreen(nav, viewModel, homeScroll, preview = true)
                                    AppNav.STATS -> StatsScreen(viewModel, statsScroll)
                                    AppNav.SETTINGS -> com.zsz.studyassistant.ui.SettingsMain(settingsScroll) { }
                                }
                            }
                        ) { swipeModifier ->
                            Box(swipeModifier.fillMaxSize()) {
                                NavHost(
                                    nav,
                                    startDestination = AppNav.HOME,
                                    enterTransition = { if (swipeTransition == (initialState.destination.route to targetState.destination.route)) androidx.compose.animation.EnterTransition.None else com.zsz.studyassistant.ui.pageEnterTransition(initialState.destination.route, targetState.destination.route) },
                                    exitTransition = { if (swipeTransition == (initialState.destination.route to targetState.destination.route)) androidx.compose.animation.ExitTransition.None else com.zsz.studyassistant.ui.pageExitTransition(initialState.destination.route, targetState.destination.route) },
                                    popEnterTransition = { com.zsz.studyassistant.ui.pageEnterTransition(initialState.destination.route, targetState.destination.route, pop = true) },
                                    popExitTransition = { com.zsz.studyassistant.ui.pageExitTransition(initialState.destination.route, targetState.destination.route, pop = true) },
                                    // Predictive Back has separate defaults since Navigation 2.10.
                                    predictivePopEnterTransition = { _ -> com.zsz.studyassistant.ui.pageEnterTransition(initialState.destination.route, targetState.destination.route, pop = true) },
                                    predictivePopExitTransition = { _ -> com.zsz.studyassistant.ui.pagePredictiveExitTransition(initialState.destination.route, targetState.destination.route) }
                                ) {
                                    // ⚠️ 路由常量与"谁能进谁、返回去哪"全部集中在 ui/AppNav.kt（页面树），这里只做绑定
                                    composable(AppNav.HOME) { HomeScreen(nav, viewModel, homeScroll, requiredUpdate = requiredUpdate) }
                                    composable(AppNav.CAMERA) { com.zsz.studyassistant.ui.CaptureRecoveryGate(viewModel) { CameraScreen(nav, viewModel) } }
                                    composable(AppNav.CROP) { com.zsz.studyassistant.ui.CaptureRecoveryGate(viewModel) { CropScreen(nav, viewModel) } }
                                    composable(AppNav.ATTACHMENT_CROP) { CropScreen(nav, viewModel, attachment = true) }
                                    composable(AppNav.GRADE) { GradeScreen(nav, viewModel) }
                                    // 解题页带上来路（home / camera / grade / history / notebook / review）
                                    composable(AppNav.SOLVE_PATTERN) { entry ->
                                        SolveScreen(
                                            nav,
                                            viewModel,
                                            AppNav.Origin.of(entry.arguments?.getString("origin"))
                                        )
                                    }
                                    composable(AppNav.HISTORY) { SolveHistoryScreen(nav, viewModel) }
                                    composable(AppNav.NOTEBOOK) { NotebookScreen(nav, viewModel) }
                                    composable(AppNav.NOTEBOOK_TAGS) {
                                        SettingsSubPage(strings["tagMerge.title"], { nav.popBackStack() }) {
                                            TagMergePage(viewModel, onTagsChanged = { ids ->
                                                nav.previousBackStackEntry?.takeIf { it.destination.route == AppNav.NOTEBOOK }
                                                    ?.savedStateHandle?.set(AppNav.NOTEBOOK_TAG_RESULT, ids.toLongArray())
                                            })
                                        }
                                    }
                                    composable(AppNav.NOTEBOOK_PRACTICE) { com.zsz.studyassistant.ui.NotebookPracticeScreen(nav, viewModel) }
                                    composable(AppNav.REVIEW) { ReviewScreen(nav, viewModel) }
                                    composable(AppNav.SIMILAR) { SimilarScreen(nav, viewModel) }
                                    composable(AppNav.STATS) { StatsScreen(viewModel, statsScroll) }
                                    composable(AppNav.SETTINGS) {
                                        SettingsTab(viewModel, settingsRootRequest, settingsScroll) { settingsAtRoot = it }
                                    }
                                    composable(AppNav.REQUIRED_UPDATE) {
                                        Box(Modifier.padding(WindowInsets.navigationBars.asPaddingValues())) {
                                            com.zsz.studyassistant.ui.SettingsSubPage(strings["settings.update"],
                                                { AppNav.switchTab(nav, AppNav.SETTINGS) }) {
                                                com.zsz.studyassistant.ui.UpdatePage()
                                            }
                                        }
                                    }
                                }

                                // ★ 切换页面后短时吞掉内容区触摸（对所有目的地生效）：
                                //   过渡动画期间「正在退出的页面」仍在组合中并参与命中测试，而新页面在很多位置
                                //   没有可点击节点，触摸会穿透到下面那张旧页面 → 曾出现「点了设置又误触拍照搜题」。
                                //   用 navBlocking（状态 + 协程定时解锁）兜住这段窗口；底部导航栏在 NavHost 之外，不受影响。
                                if (navBlocking.value) {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .pointerInput(Unit) {
                                                awaitPointerEventScope {
                                                    while (true) {
                                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                                        event.changes.forEach { it.consume() }
                                                    }
                                                }
                                            }
                                    )
                                }

                                androidx.compose.runtime.LaunchedEffect(viewModel.draftSolveRequested) {
                                    if (viewModel.draftSolveRequested) {
                                        AppNav.openSolve(nav, AppNav.Origin.of(viewModel.draftReturnOrigin))
                                        viewModel.consumeDraftNavigation()
                                    }
                                }
                                // 从通知点进来 → 直接打开复习页
                                androidx.compose.runtime.LaunchedEffect(viewModel.reviewOpenRequested) {
                                    if (viewModel.reviewOpenRequested) {
                                        viewModel.saveSessionOnExit()
                                        viewModel.stopActiveGeneration()
                                        AppNav.openReview(nav)
                                        viewModel.consumeReviewNavigation()
                                    }
                                }
                            }
                        } // RootTabSwipeHost
                    }
                    com.zsz.studyassistant.ui.RootBackExitHandler(
                        enabled = !requiredUpdate && current in AppNav.rootTabs && (current != AppNav.SETTINGS || settingsAtRoot),
                        pageKey = current,
                        onExit = { moveTaskToBack(true) }
                    )
                    androidx.activity.compose.BackHandler(enabled = requiredUpdate && current == AppNav.REQUIRED_UPDATE) {
                        showRequiredBackHint()
                    }
                    if (!updatePolicyReady) {
                        // Restore local required policy before the first business interaction.
                        androidx.compose.ui.window.Dialog(onDismissRequest = {}, properties =
                            androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
                            androidx.compose.material3.CircularProgressIndicator()
                        }
                    } else com.zsz.studyassistant.ui.StableUpdatePrompt(updatePrompt, current == AppNav.REQUIRED_UPDATE,
                        deferredThisVisit = deferredThisVisit, setDeferredThisVisit = { deferredThisVisit = it },
                        whenHidden = {
                            com.zsz.studyassistant.ui.CrashReminder(
                                enabled = !requiredUpdate && !viewModel.showUpgradePrompt,
                                whenHidden = {
                                    com.zsz.studyassistant.ui.AppNoticePrompt(
                                        enabled = !viewModel.busy && current in AppNav.rootTabs &&
                                            (current != AppNav.SETTINGS || settingsAtRoot) &&
                                            !viewModel.openUpdatePage && !viewModel.openCrashReportPage,
                                        canOpenUpdate = updatePrompt != null,
                                        onOpenUpdate = { viewModel.openUpdatePage = true })
                                },
                                onOpenReport = { viewModel.openCrashReportPage = true })
                        },
                        onRequiredBack = { showRequiredBackHint() }) { required ->
                        if (required) AppNav.openRequiredUpdate(nav)
                        else viewModel.openUpdatePage = true
                    }
                }
            }
            }   // CompositionLocalProvider(LocalDensity)
            }
        }
    }
}
