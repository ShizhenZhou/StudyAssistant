package com.zsz.studyassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.zsz.studyassistant.ui.CameraScreen
import com.zsz.studyassistant.ui.HomeScreen
import com.zsz.studyassistant.ui.NotebookScreen
import com.zsz.studyassistant.ui.SolveScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = "home") {
                        composable("home") { HomeScreen(nav, viewModel) }
                        composable("camera") { CameraScreen(nav, viewModel) }
                        composable("solve") { SolveScreen(nav, viewModel) }
                        composable("notebook") { NotebookScreen(nav, viewModel) }
                    }
                }
            }
        }
    }
}
