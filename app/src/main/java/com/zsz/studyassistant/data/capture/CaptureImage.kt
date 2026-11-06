package com.zsz.studyassistant.data

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.ByteArrayOutputStream
import java.io.File

/** One oriented, bounded software decode for crop preview, detection and whole-image submission. */
object CaptureImage {
    /** Rotate the already-oriented preview without JPEG recompression or changing its source. */
    fun rotateClockwise(bitmap: Bitmap, cacheDir: File): File {
        val matrix = android.graphics.Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
        var output: File? = null
        try {
            val file = File.createTempFile("crop_rotated_", ".png", cacheDir).also { output = it }
            file.outputStream().use { check(rotated.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            require(file.length() in 1L..(32L * 1024 * 1024)) { "Rotated image exceeds safe input size" }
            return file
        } catch (e: Exception) {
            output?.delete()
            throw e
        } finally {
            if (rotated !== bitmap) rotated.recycle()
        }
    }

    fun decode(path: String, maxDim: Int = 4096): Bitmap {
        require(maxDim > 0)
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1.0, maxDim.toDouble() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize(
                (info.size.width * scale).toInt().coerceAtLeast(1),
                (info.size.height * scale).toInt().coerceAtLeast(1)
            )
        }
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
