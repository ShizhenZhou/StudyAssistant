package com.zsz.studyassistant.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect

/** Retained per source image. Coordinates are fractions of the image, independent of the window. */
internal class CropSelection {
    var fraction by mutableStateOf<Rect?>(null)
    var touched by mutableStateOf(false)
        private set
    private var editRevision = 0L

    fun touch(): Long {
        cancelDetection()
        status = null
        touched = true
        return ++editRevision
    }

    fun isLatestEdit(revision: Long) = editRevision == revision

    var autoJob: kotlinx.coroutines.Job? = null
    var status by mutableStateOf<String?>(null)
    private var hasAiResult = false

    fun cancelDetection() {
        if (autoJob?.isActive == true) {
            autoJob?.cancel()
            autoJob = null
            if (status == "crop.aiBusy") status = null
        }
    }

    fun applyManual(box: com.zsz.studyassistant.data.ImageAutoCrop.NormRect, bounds: Rect) {
        setBox(box, bounds)
    }

    /** AI has priority over the local suggestion, and a user's selection has priority over both. */
    fun suggest(box: com.zsz.studyassistant.data.ImageAutoCrop.NormRect?, ai: Boolean, bounds: Rect) {
        if (box == null || touched || hasAiResult || box.looksUnreliable() || box.touchesEdge()) return
        hasAiResult = ai
        setBox(box, bounds)
    }

    private fun setBox(box: com.zsz.studyassistant.data.ImageAutoCrop.NormRect, bounds: Rect) {
        val display = DisplayedCrop(this).apply { this.bounds = bounds }
        val r = Rect(bounds.left + box.x * bounds.width, bounds.top + box.y * bounds.height,
            bounds.left + (box.x + box.w) * bounds.width, bounds.top + (box.y + box.h) * bounds.height)
        display.rect = normalizeSelRect(r, bounds)
    }
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
