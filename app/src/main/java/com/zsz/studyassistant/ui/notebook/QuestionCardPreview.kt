package com.zsz.studyassistant.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zsz.studyassistant.data.Question

/** Save only layout metadata, never image bytes, in the navigation entry's UI state. */
@Composable
internal fun QuestionCardPreview(q: Question, description: String) {
    val bitmaps = questionCardBitmaps(q, 400)
    var rememberedRatios by rememberSaveable(q.id) { mutableStateOf(floatArrayOf(1.6f)) }
    val ratios = bitmaps?.map { it.width.toFloat() / it.height.coerceAtLeast(1) }?.toFloatArray()
        ?: rememberedRatios
    LaunchedEffect(bitmaps) {
        if (bitmaps != null) rememberedRatios = ratios
    }
    val alpha by animateFloatAsState(if (bitmaps == null) 0f else 1f, tween(140), label = "thumbnail")
    if (ratios.isEmpty()) {
        Text(TextPretty.oneLine(q.text, 110), maxLines = 2,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    } else {
        ratios.forEachIndexed { index, ratio ->
            Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)
                .aspectRatio(ratio).clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                bitmaps?.getOrNull(index)?.let { bitmap ->
                    Image(bitmap.asImageBitmap(), description,
                        modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha },
                        contentScale = ContentScale.Fit)
                }
            }
        }
    }
}
