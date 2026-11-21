package com.zsz.studyassistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zsz.studyassistant.data.PdfSourcePreview
import kotlin.math.roundToInt

@Composable
internal fun PdfSourcePage(preview: PdfSourcePreview, description: String, modifier: Modifier = Modifier) {
    val labels = mediaViewerLabels()
    val s = LocalStrings.current
    var zoom by remember(preview) { mutableFloatStateOf(1f) }
    var offset by remember(preview) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    fun constrain(value: Offset, scale: Float): Offset {
        val fit = minOf(viewport.width.toFloat() / preview.bitmap.width, viewport.height.toFloat() / preview.bitmap.height)
        val x = ((preview.bitmap.width * fit * scale - viewport.width) / 2f).coerceAtLeast(0f)
        val y = ((preview.bitmap.height * fit * scale - viewport.height) / 2f).coerceAtLeast(0f)
        return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
    }
    fun transform(scale: Float, focus: Offset = Offset(viewport.width / 2f, viewport.height / 2f), pan: Offset = Offset.Zero) {
        val next = scale.coerceIn(1f, 6f)
        val fromCenter = focus - Offset(viewport.width / 2f, viewport.height / 2f)
        offset = constrain(fromCenter - (fromCenter - offset) * (next / zoom) + pan, next)
        zoom = next
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(s[if (preview.highlights.isEmpty()) "pdfSource.noHighlight" else "pdfSource.highlightHint"],
            style = MaterialTheme.typography.bodySmall)
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().onSizeChanged {
            viewport = it; offset = constrain(offset, zoom)
        }.pointerInput(preview) {
            detectTransformGestures { centroid, pan, scale, _ -> transform(zoom * scale, centroid, pan) }
        }, contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom,
                translationX = offset.x, translationY = offset.y)) {
                Image(preview.bitmap.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                Canvas(Modifier.fillMaxSize()) {
                    val fit = minOf(size.width / preview.bitmap.width, size.height / preview.bitmap.height)
                    val origin = Offset((size.width - preview.bitmap.width * fit) / 2f,
                        (size.height - preview.bitmap.height * fit) / 2f)
                    preview.highlights.forEach { rect ->
                        drawRect(Color(0x66FFB300), origin + Offset(rect.left * fit, rect.top * fit),
                            Size(rect.width() * fit, rect.height() * fit))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { transform(zoom / 1.5f) }, enabled = zoom > 1f,
                modifier = Modifier.semantics { contentDescription = labels.zoomOut }) { Text("−") }
            TextButton(onClick = { zoom = 1f; offset = Offset.Zero },
                modifier = Modifier.semantics { contentDescription = s["pdfSource.resetZoom"] }) { Text("${(zoom * 100).roundToInt()}%") }
            IconButton(onClick = { transform(zoom * 1.5f) }, enabled = zoom < 6f,
                modifier = Modifier.semantics { contentDescription = labels.zoomIn }) { Text("+") }
        }
    }
}
