package com.zsz.studyassistant.data

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.zsz.studyassistant.ui.AppThemeColor
import com.zsz.studyassistant.ui.appColorScheme

/** Colored notification artwork; the status-bar small icon stays a transparent monochrome mask. */
internal object UpdateDownloadIcon {
    data class Appearance(val accent: Int, val bitmap: Bitmap)
    private var cached: Appearance? = null

    @Synchronized fun appearance(context: Context): Appearance {
        val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val dark = when (settings.getString("theme", "system")) {
            "light" -> false
            "dark" -> true
            else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val scheme = appColorScheme(dark, AppThemeColor.fromId(settings.getString("theme_color", null)),
            settings.getInt("theme_color_custom", 0xFF6750A4.toInt()))
        val accent = ColorUtils.setAlphaComponent(scheme.primary.toArgb(), 255)
        cached?.takeIf { it.accent == accent }?.let { return it }
        // Preserve contrast even for a very pale or dark custom theme color.
        val foreground = if (ColorUtils.calculateContrast(Color.WHITE, accent) >= ColorUtils.calculateContrast(Color.BLACK, accent))
            Color.WHITE else Color.BLACK
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        canvas.drawRoundRect(0f, 0f, 96f, 96f, 24f, 24f, paint)
        canvas.scale(4f, 4f)
        val arrow = Path().apply {
            moveTo(19f, 9f); lineTo(15f, 9f); lineTo(15f, 3f); lineTo(9f, 3f)
            lineTo(9f, 9f); lineTo(5f, 9f); lineTo(12f, 16f); close()
            moveTo(5f, 18f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 18f); close()
        }
        paint.color = foreground
        canvas.drawPath(arrow, paint)
        // Do not recycle an older bitmap: a posted notification can still reference it.
        return Appearance(accent, bitmap).also { cached = it }
    }
}
