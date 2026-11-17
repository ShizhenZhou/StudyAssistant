package com.zsz.studyassistant.ui

import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class GalleryImage(val bytes: ByteArray? = null, val base64: String? = null)
internal data class ImageGallery(val images: List<GalleryImage>, val initialIndex: Int)

internal data class MediaViewerLabels(val close: String, val zoomIn: String, val zoomOut: String, val preview: String)

@Composable
internal fun mediaViewerLabels(): MediaViewerLabels {
    val language = LocalUiLang.current.let { if (it == UiLang.SYSTEM) systemUiLang() else it }
    return when (language) {
        UiLang.EN -> MediaViewerLabels("Close", "Zoom in", "Zoom out", "Enlarge image")
        UiLang.JA -> MediaViewerLabels("閉じる", "拡大", "縮小", "画像を拡大")
        UiLang.KO -> MediaViewerLabels("닫기", "확대", "축소", "이미지 확대")
        else -> listOf("关闭", "放大", "缩小", "查看大图")
            .map { if (language == UiLang.ZH_TW) s2t(it) else it }
            .let { MediaViewerLabels(it[0], it[1], it[2], it[3]) }
    }
}

/** Only a single batch is retained. Decode the visible page and its immediate pager neighbour on IO. */
@Composable
internal fun ImageGalleryDialog(gallery: ImageGallery, onDismiss: () -> Unit) {
    if (gallery.images.isEmpty()) return
    val labels = mediaViewerLabels()
    val strings = LocalStrings.current
    val pager = rememberPagerState(initialPage = gallery.initialIndex.coerceIn(gallery.images.indices)) { gallery.images.size }
    val scope = rememberCoroutineScope()
    var zoomedPage by remember { mutableStateOf<Int?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xF2000000))) {
            HorizontalPager(pager, modifier = Modifier.fillMaxSize(), userScrollEnabled = zoomedPage != pager.currentPage) { page ->
                val source = gallery.images[page]
                var loading by remember(source) { mutableStateOf(true) }
                val bitmap by produceState<android.graphics.Bitmap?>(null, source) {
                    try {
                        value = withContext(Dispatchers.IO) {
                            runCatching {
                                val bytes = source.bytes ?: Base64.decode(requireNotNull(source.base64), Base64.NO_WRAP)
                                decodeBoundedBitmap(bytes, 4096)
                            }.getOrNull()
                        }
                    } finally { loading = false }
                }
                var scale by remember(source) { mutableFloatStateOf(1f) }
                var offset by remember(source) { mutableStateOf(Offset.Zero) }
                LaunchedEffect(pager.currentPage) {
                    scale = 1f
                    offset = Offset.Zero
                    if (zoomedPage == page) zoomedPage = null
                }
                Box(Modifier.fillMaxSize().pointerInput(source) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var transformed = false
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } > 1) transformed = true
                            // At fit size, a single finger belongs to the pager. Once pinching,
                            // keep this gesture until all fingers lift so it cannot turn into a page swipe.
                            if (transformed || scale > 1f) {
                                scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                                offset = if (scale > 1f) offset + event.calculatePan() else Offset.Zero
                                val limitX = size.width * (scale - 1f) / 2f
                                val limitY = size.height * (scale - 1f) / 2f
                                offset = Offset(offset.x.coerceIn(-limitX, limitX), offset.y.coerceIn(-limitY, limitY))
                                zoomedPage = if (scale > 1f) page else null
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }, contentAlignment = Alignment.Center) {
                    bitmap?.let { preview ->
                        Image(preview.asImageBitmap(), contentDescription = labels.preview,
                            modifier = Modifier.fillMaxSize().padding(12.dp).graphicsLayer(
                                scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                            contentScale = ContentScale.Fit)
                    } ?: Text(if (loading) "…" else "⚠️", color = Color.White)
                    Text("${"%.1f".format(scale)}×", color = Color.White,
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 70.dp))
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()
                .semantics { contentDescription = labels.close }) { Text("✕", color = Color.White) }
            Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                IconButton(enabled = pager.currentPage > 0,
                    onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                    modifier = Modifier.semantics { contentDescription = strings["documentSelection.previous"] }) {
                    Text("‹", color = if (pager.currentPage > 0) Color.White else Color.Gray)
                }
                Text("${pager.currentPage + 1} / ${gallery.images.size}", color = Color.White)
                IconButton(enabled = pager.currentPage < gallery.images.lastIndex,
                    onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                    modifier = Modifier.semantics { contentDescription = strings["documentSelection.next"] }) {
                    Text("›", color = if (pager.currentPage < gallery.images.lastIndex) Color.White else Color.Gray)
                }
            }
        }
    }
}
