package com.zsz.studyassistant.ui

import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Cap reading width without narrowing the outer window, insets or navigation bar. */
internal fun Modifier.readablePage(maxWidth: Dp = 840.dp): Modifier =
    fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = maxWidth)

/** Show only the system wallpaper surface, never another application's Activity. */
internal fun applyWallpaperBackground(window: Window, enabled: Boolean, surface: Color) {
    val attributes = window.attributes
    attributes.setWallpaperTouchEventsEnabled(false)
    window.attributes = attributes
    if (enabled) {
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setBackgroundDrawable(ColorDrawable(surface.toArgb()))
        window.setFormat(PixelFormat.OPAQUE)
    }
}
