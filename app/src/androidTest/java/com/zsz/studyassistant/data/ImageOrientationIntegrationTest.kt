package com.zsz.studyassistant.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageOrientationIntegrationTest {
    @Test fun rotatedAndMirroredDecodesMatchTheUnscaledPlatformOrientation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.zsz.studyassistant.integration")
        val file = File.createTempFile("all-orientations", ".jpg", context.cacheDir)
        val image = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888)
        try {
            Canvas(image).apply {
                drawColor(Color.WHITE)
                drawRect(0f, 0f, 60f, 80f, Paint().apply { color = Color.RED })
                drawRect(60f, 80f, 120f, 160f, Paint().apply { color = Color.BLUE })
            }
            file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            for (orientation in 1..8) {
                ExifInterface(file.absolutePath).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes()
                }
                val expected = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(file)) { decoder, _, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
                val actual = CaptureImage.decode(file.absolutePath)
                try {
                    assertEquals(expected.width, actual.width); assertEquals(expected.height, actual.height)
                    for (x in listOf(actual.width / 4, actual.width * 3 / 4)) {
                        for (y in listOf(actual.height / 4, actual.height * 3 / 4)) {
                            assertEquals("EXIF $orientation at $x,$y", expected.getPixel(x, y), actual.getPixel(x, y))
                        }
                    }
                } finally { expected.recycle(); actual.recycle() }
            }
        } finally { image.recycle(); file.delete() }
    }

    @Test fun failedGalleryBatchDoesNotDropAnInputOrLeavePartialCopies() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.zsz.studyassistant.integration")
        val readable = File.createTempFile("gallery-input", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try { readable.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) } }
        finally { bitmap.recycle() }
        val empty = File.createTempFile("gallery-empty", ".bin", context.cacheDir)
        val prefix = "gallery-fixture-${java.util.UUID.randomUUID()}"
        try {
            val uris = listOf(readable, empty).map { android.net.Uri.fromFile(it) }
            assertNotNull(com.zsz.studyassistant.ui.urisToCompressedBytes(context, uris.take(1)))
            assertNull(com.zsz.studyassistant.ui.urisToCompressedBytes(context, uris))
            assertNull(com.zsz.studyassistant.ui.urisToTempFiles(context, uris, prefix))
            assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith(prefix) })
            assertTrue(readable.exists())
        } finally { readable.delete(); empty.delete() }
    }

    @Test fun pureBlackTextRemainsInsideTheOtsuForegroundClass() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.zsz.studyassistant.integration")
        val file = File.createTempFile("binary-fixture", ".png", context.cacheDir)
        val image = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        try {
            Canvas(image).apply {
                drawColor(Color.WHITE)
                drawRect(30f, 60f, 100f, 105f, Paint().apply { color = Color.BLACK })
            }
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertEquals(1, ImageAutoCrop.detectBlocks(file.absolutePath).size)
        } finally { image.recycle(); file.delete() }
    }

    @Test fun localCropCoordinatesFollowExifOrientationLikeThePreview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.zsz.studyassistant.integration")
        val file = File.createTempFile("orientation-fixture", ".jpg", context.cacheDir)
        val image = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(image)
            canvas.drawColor(Color.WHITE)
            canvas.drawRect(30f, 60f, 100f, 105f, Paint().apply { color = Color.rgb(25, 25, 25) })
            file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            val unrotated = ImageAutoCrop.detectBlocks(file.absolutePath).first()
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val rotated = ImageAutoCrop.detectBlocks(file.absolutePath).first()
            val whole = CaptureImage.wholeJpeg(file.absolutePath)
            val submitted = android.graphics.BitmapFactory.decodeByteArray(whole, 0, whole.size)
            try {
                assertEquals(300, submitted.width)
                assertEquals(200, submitted.height)
                assertTrue(Color.red(submitted.getPixel(220, 60)) < 70)
                assertTrue(Color.red(submitted.getPixel(60, 80)) > 220)
            } finally { submitted.recycle() }
            val bounded = CaptureImage.decode(file.absolutePath, 100)
            try { assertTrue(maxOf(bounded.width, bounded.height) in 1..100) }
            finally { bounded.recycle() }
            val compressed = StudyAssistant.compressImage(file, maxDim = 100)
            val small = android.graphics.BitmapFactory.decodeByteArray(compressed, 0, compressed.size)
            try { assertTrue(small.width > small.height); assertTrue(small.width <= 100) }
            finally { small.recycle() }
            assertEquals(1f - unrotated.y - unrotated.h, rotated.x, 0.025f)
            assertEquals(unrotated.x, rotated.y, 0.025f)
            assertEquals(unrotated.h, rotated.w, 0.025f)
            assertEquals(unrotated.w, rotated.h, 0.025f)
        } finally { image.recycle(); file.delete() }
    }
}
