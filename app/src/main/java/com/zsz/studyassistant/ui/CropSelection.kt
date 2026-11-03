package com.zsz.studyassistant.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/** Retained per source image. Coordinates are fractions of the image, independent of the window. */
internal class CropSelection {
    var fraction by mutableStateOf<Rect?>(null)
    var touched by mutableStateOf(false)
}

internal class DisplayedCrop(private val source: CropSelection) {
    var bounds by mutableStateOf(Rect.Zero)
    var rect: Rect?
        get() = source.fraction?.takeIf { bounds.width > 0 && bounds.height > 0 }?.let {
            Rect(bounds.left + it.left * bounds.width, bounds.top + it.top * bounds.height,
                bounds.left + it.right * bounds.width, bounds.top + it.bottom * bounds.height)
        }
        set(value) {
            if (value == null || bounds.width <= 0 || bounds.height <= 0) return
            fun x(px: Float) = ((px - bounds.left) / bounds.width).coerceIn(0f, 1f)
            fun y(px: Float) = ((px - bounds.top) / bounds.height).coerceIn(0f, 1f)
            source.fraction = Rect(x(value.left), y(value.top), x(value.right), y(value.bottom))
        }
}
