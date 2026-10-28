package com.zsz.studyassistant.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File

/** One oriented, bounded software decode for crop preview, detection and whole-image submission. */
object CaptureImage {
    fun decode(path: String, maxDim: Int = 4096): Bitmap {
        require(maxDim > 0)
        // API 28's rotated JPEG downsampling can abort in SkSwizzler (SIGFPE), beyond try/catch.
        if (Build.VERSION.SDK_INT == 28) return decodePie(path, maxDim)
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1.0, maxDim.toDouble() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(
                (info.size.width * scale).toInt().coerceAtLeast(1),
                (info.size.height * scale).toInt().coerceAtLeast(1)
            )
        }
    }

    private fun decodePie(path: String, maxDim: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight).toLong() > maxDim.toLong() * sample) sample *= 2
        val decoded = checkNotNull(BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }))
        try {
            val orientation = runCatching { ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            val oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (oriented !== decoded) decoded.recycle()
            return oriented
        } catch (error: Throwable) { decoded.recycle(); throw error }
    }

    fun wholeJpeg(path: String): ByteArray {
        val bitmap = decode(path)
        return try {
            ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out))
                out.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
}
