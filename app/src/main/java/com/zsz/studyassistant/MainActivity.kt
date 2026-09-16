package com.zsz.studyassistant

import android.app.Activity
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zsz.studyassistant.ui.CameraScreen
import com.zsz.studyassistant.ui.CropScreen
import com.zsz.studyassistant.ui.AskScreen
import com.zsz.studyassistant.ui.GradeScreen
import com.zsz.studyassistant.ui.HomeScreen
import com.zsz.studyassistant.ui.LocalStrings
import com.zsz.studyassistant.ui.LocalUiLang
import com.zsz.studyassistant.ui.NotebookScreen
import com.zsz.studyassistant.ui.ReviewScreen
import com.zsz.studyassistant.ui.SettingsTab
import com.zsz.studyassistant.ui.SimilarScreen
import com.zsz.studyassistant.ui.SolveScreen
import com.zsz.studyassistant.ui.stringsFor

private const val NAV_TOUCH_GUARD_MS = 250L   // 导航后吞掉内容区触摸的时长（防穿透）

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启动即创建通知渠道（否则系统"通知管理"会显示"未发布任何通知"），并按需申请通知权限
        com.zsz.studyassistant.data.ReminderScheduler.ensureChannel(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 && com.zsz.studyassistant.data.ReminderScheduler.isEnabled(this)) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        val openReview = intent?.getBooleanExtra("open_review", false) ?: false
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
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme() else lightColorScheme()
            ) {
                // 状态栏透明 + 图标颜色跟随主题（浅色=深色图标，深色=浅色图标）
                val view = LocalView.current
                if (!view.isInEditMode) {
                    SideEffect {
                        val window = (view.context as Activity).window
                        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
                    }
                }
                Surface(modifier = Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    val backStack by nav.currentBackStackEntryAsState()
                    val current = backStack?.destination?.route
                    val showBottomBar = current == "home" || current == "settings"

                    // 防触摸穿透的「导航后短时封锁」状态（见下方 NavHost 旁的说明）
                    val navBlocking = remember { androidx.compose.runtime.mutableStateOf(false) }
                    val navBlockUntil = remember { androidx.compose.runtime.mutableStateOf(0L) }
                    androidx.compose.runtime.DisposableEffect(nav) {
                        val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, _, _ ->
                            navBlockUntil.value = android.os.SystemClock.uptimeMillis() + NAV_TOUCH_GUARD_MS
                            navBlocking.value = true
                        }
                        nav.addOnDestinationChangedListener(listener)
                        onDispose { nav.removeOnDestinationChangedListener(listener) }
                    }
                    androidx.compose.runtime.LaunchedEffect(navBlocking.value) {
                        if (navBlocking.value) {
                            val wait = navBlockUntil.value - android.os.SystemClock.uptimeMillis()
                            if (wait > 0) kotlinx.coroutines.delay(wait)
                            navBlocking.value = false
                        }
                    }

                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            NavHost(
                                nav,
                                startDestination = "home",
                                // 默认过渡是 fadeIn/fadeOut(tween(700))，对一个笔记类 App 偏慢；
                                // 缩短到 200ms，更跟手，也让「旧页面仍可被命中」的窗口更小
                                enterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) },
                                exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)) },
                                popEnterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) },
                                popExitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)) }
                            ) {
                                composable("home") { HomeScreen(nav, viewModel) }
                                composable("camera") { CameraScreen(nav, viewModel) }
                                composable("ask") { AskScreen(nav, viewModel) }
                                composable("crop") { CropScreen(nav, viewModel) }
                                composable("grade") { GradeScreen(nav, viewModel) }
                                composable("solve") { SolveScreen(nav, viewModel) }
                                composable("notebook") { NotebookScreen(nav, viewModel) }
                                composable("review") { ReviewScreen(nav, viewModel) }
                                composable("similar") { SimilarScreen(nav, viewModel) }
                                composable("settings") { SettingsTab(viewModel) }
                            }

                            // ★ 过渡期间吞掉内容区触摸（对所有页面切换都生效）：
                            //   页面切换时「正在退出的页面」仍在组合中并参与命中测试，而新页面在很多位置
                            //   没有可点击节点，触摸会穿透到下面那张旧页面 → 曾出现「点了设置又误触拍照搜题」。
                            //   两道判据（取或）：① 当前目的地尚未 RESUMED（动画进行中）
                            //                    ② 刚发生导航的 250ms 内（不依赖过渡实现细节的兜底）
                            val currentEntry = backStack
                            val notResumed = currentEntry?.lifecycle?.currentState
                                ?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) != true
                            val withinNavGuard = navBlocking.value ||
                                android.os.SystemClock.uptimeMillis() < navBlockUntil.value
                            if (notResumed || withinNavGuard) {
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

                            // 从通知点进来 → 直接打开复习页
                            androidx.compose.runtime.LaunchedEffect(openReview) {
                                if (openReview) nav.navigate("review")
                            }
                        }
                        if (showBottomBar) {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = current == "home",
                                    onClick = {
                                        nav.navigate("home") {
                                            popUpTo("home") { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    },
                                    icon = { Text("🏠") },
                                    label = { Text(strings["nav.home"]) }
                                )
                                NavigationBarItem(
                                    selected = current == "settings",
                                    onClick = {
                                        nav.navigate("settings") {
                                            popUpTo("home") { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    },
                                    icon = { Text("⚙️") },
                                    label = { Text(strings["nav.settings"]) }
                                )
                            }
                        }
                    }
                }
            }
            }
        }
    }
}
