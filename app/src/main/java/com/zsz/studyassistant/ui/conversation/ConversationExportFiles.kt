package com.zsz.studyassistant.ui

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets.US_ASCII

internal fun conversationExportName(input: String, fallback: String): String {
    val cleaned = input.trim().replace(Regex("(?i)\\.(pdf|png)$"), "")
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim(' ', '.')
    val bounded = cleaned.codePoints().limit(72).toArray().let { String(it, 0, it.size) }.trimEnd(' ', '.')
    if (bounded.isBlank()) return fallback
    return if (Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$").matches(bounded)) "SA-$bounded" else bounded
}

/** Streaming image-only PDF: one JPEG/page, bounded memory, no whole-document bitmap retention. */
internal class ConversationImagePdf(file: File) : AutoCloseable {
    private val output: OutputStream = BufferedOutputStream(FileOutputStream(file))
    private val offsets = mutableListOf(0L, 0L, 0L)
    private val pageObjects = mutableListOf<Int>()
    private var written = 0L
    private var finished = false
    init { ascii("%PDF-1.4\n%SA image export\n") }
    private fun bytes(value: ByteArray) { output.write(value); written += value.size }
    private fun ascii(value: String) = bytes(value.toByteArray(US_ASCII))
    private fun obj(id: Int, body: () -> Unit) {
        while (offsets.size <= id) offsets.add(0L)
        offsets[id] = written; ascii("$id 0 obj\n"); body(); ascii("\nendobj\n")
    }
    fun addPage(jpeg: ByteArray, width: Int, height: Int) {
        check(!finished && width > 0 && height > 0)
        val image = offsets.size; val content = image + 1; val page = image + 2
        obj(image) {
            ascii("<< /Type /XObject /Subtype /Image /Width $width /Height $height /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n")
            bytes(jpeg); ascii("\nendstream")
        }
        val draw = "q 595 0 0 842 0 0 cm /Image Do Q\n"
        obj(content) { ascii("<< /Length ${draw.length} >>\nstream\n${draw}endstream") }
        obj(page) { ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /XObject << /Image $image 0 R >> >> /Contents $content 0 R >>") }
        pageObjects.add(page)
    }
    fun finish() {
        check(!finished && pageObjects.isNotEmpty())
        obj(1) { ascii("<< /Type /Catalog /Pages 2 0 R >>") }
        obj(2) { ascii("<< /Type /Pages /Count ${pageObjects.size} /Kids [${pageObjects.joinToString(" ") { "$it 0 R" }}] >>") }
        val xref = written
        ascii("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
        offsets.drop(1).forEach { offset -> ascii(offset.toString().padStart(10, '0') + " 00000 n \n") }
        ascii("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        output.flush(); finished = true
    }
    override fun close() = output.close()
}
