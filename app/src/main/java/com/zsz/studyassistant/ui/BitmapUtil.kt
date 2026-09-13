package com.zsz.studyassistant.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory

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
