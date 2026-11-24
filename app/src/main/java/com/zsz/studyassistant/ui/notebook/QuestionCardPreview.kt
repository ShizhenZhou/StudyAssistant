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
import com.zsz.studyassistant.data.QuestionPreview
import com.zsz.studyassistant.data.reviewCameraQuestionText

/** Save only layout metadata, never image bytes, in the navigation entry's UI state. */
@Composable
internal fun QuestionCardPreview(q: QuestionPreview, description: String, review: Boolean = false) {
    val preview = questionCardBitmaps(q, 400, review)
    val bitmaps = preview?.images
    val text = preview?.questionText ?: if (review) reviewCameraQuestionText(q.text, null, null) else q.text
    var rememberedRatios by rememberSaveable(q.id) { mutableStateOf(floatArrayOf(1.6f)) }
    val ratios = bitmaps?.map { it.width.toFloat() / it.height.coerceAtLeast(1) }?.toFloatArray()
        ?: rememberedRatios
    LaunchedEffect(bitmaps) {
        if (bitmaps != null) rememberedRatios = ratios
    }
    val alpha by animateFloatAsState(if (bitmaps == null) 0f else 1f, tween(140), label = "thumbnail")
    if (text.isNotBlank() && (ratios.isEmpty() || (review && text.trim() != "（图片题）"))) {
        Text(TextPretty.oneLine(text, 110), maxLines = 2,
            modifier = Modifier.padding(bottom = if (ratios.isEmpty()) 0.dp else 4.dp),
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
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
