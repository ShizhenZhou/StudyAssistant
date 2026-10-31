package com.zsz.studyassistant.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Both dimensions are bounded: a narrow, very tall image must not decode at full resolution. */
internal fun decodeBoundedBitmap(bytes: ByteArray, maxDimension: Int): Bitmap? = try {
    require(maxDimension > 0)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    var sample = 1
    while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
} catch (_: Exception) { null }

/**
 * 按目标宽度降采样解码，避免列表加载全尺寸大图（降低内存占用、提升流畅度）。
 */
internal fun decodeSampledBitmap(bytes: ByteArray, reqWidth: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    var w = bounds.outWidth
    while (w > 0 && w / 2 >= reqWidth) {
        sample *= 2
        w /= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
} catch (e: Exception) {
    null
}

/** Query and decode images only for composed/visible cards, on IO, rather than in list queries. */
@androidx.compose.runtime.Composable
internal fun questionCardBitmaps(q: com.zsz.studyassistant.data.Question, width: Int): List<android.graphics.Bitmap>? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = androidx.compose.runtime.produceState<List<android.graphics.Bitmap>?>(null, q.id, width) {
        value = try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val dao = com.zsz.studyassistant.data.AppDatabase.get(context).questionDao()
            val stored = dao.imagesForQuestion(q.id).map { it.bytes }
            val bytes = if(stored.isNotEmpty()) stored else {
                val full = dao.heavyOnce(q.id)
                com.zsz.studyassistant.data.mergeImageKeys(full?.imageBytes?.let {
                    android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
                }, com.zsz.studyassistant.data.questionImageBase64(full?.conversationJson)).mapNotNull {
                    runCatching { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }.getOrNull()
                }
            }
            bytes.take(3).mapNotNull { decodeSampledBitmap(it,width) }
        } } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { emptyList() }
    }
    return state.value
}
