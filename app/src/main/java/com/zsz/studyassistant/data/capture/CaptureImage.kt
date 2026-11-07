package com.zsz.studyassistant.data

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import java.io.File

/** One oriented, bounded software decode for crop preview, detection and whole-image submission. */
object CaptureImage {
    /** Rotation has no encoding or disk IO; the caller retains ownership of the source. */
    fun rotate(bitmap: Bitmap, quarterTurns: Int): Bitmap {
        val turns = Math.floorMod(quarterTurns, 4)
        if (turns == 0) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(turns * 90f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
    }

    fun decode(path: String, maxDim: Int = 4096, quarterTurns: Int = 0): Bitmap {
        require(maxDim > 0)
        val source = ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1.0, maxDim.toDouble() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(
                (info.size.width * scale).toInt().coerceAtLeast(1),
                (info.size.height * scale).toInt().coerceAtLeast(1)
            )
        }
        return try { rotate(source, quarterTurns).also { if (it !== source) source.recycle() } }
        catch (error: Throwable) { source.recycle(); throw error }
    }

    fun wholeJpeg(path: String, quarterTurns: Int = 0): ByteArray {
        val bitmap = decode(path, quarterTurns = quarterTurns)
        return try {
            ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out))
                out.toByteArray()
            }
        } finally { bitmap.recycle() }
    }
}
