package com.zsz.studyassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zsz.studyassistant.ui.CameraScreen
import com.zsz.studyassistant.ui.CropScreen
import com.zsz.studyassistant.ui.HomeScreen
import com.zsz.studyassistant.ui.NotebookScreen
import com.zsz.studyassistant.ui.SettingsTab
import com.zsz.studyassistant.ui.SolveScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = when (viewModel.theme) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme() else lightColorScheme()
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    val backStack by nav.currentBackStackEntryAsState()
                    val current = backStack?.destination?.route
                    val showBottomBar = current == "home" || current == "settings"

                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            NavHost(nav, startDestination = "home") {
                                composable("home") { HomeScreen(nav, viewModel) }
                                composable("camera") { CameraScreen(nav, viewModel) }
                                composable("crop") { CropScreen(nav, viewModel) }
                                composable("solve") { SolveScreen(nav, viewModel) }
                                composable("notebook") { NotebookScreen(nav, viewModel) }
                                composable("settings") { SettingsTab(viewModel) }
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
                                    label = { Text("主页") }
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
                                    label = { Text("设置") }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
