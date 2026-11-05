package com.zsz.studyassistant.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zsz.studyassistant.MainViewModel

/** Await private IO recovery before the camera/crop UI consumes its queue. */
@Composable
internal fun CaptureRecoveryGate(vm: MainViewModel, content: @Composable () -> Unit) {
    var ready by remember(vm) { mutableStateOf(false) }
    LaunchedEffect(vm) { vm.restoreCaptureFlowIfNeeded(); ready = true }
    if (ready) content() else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
