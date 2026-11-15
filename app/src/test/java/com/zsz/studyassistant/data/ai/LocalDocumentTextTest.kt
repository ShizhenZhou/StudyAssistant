package com.zsz.studyassistant.data

import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalDocumentTextTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun textKeepsMarkdownAndCsvVerbatimAndRejectsBrokenEncodingOrOversizedOutput() {
        val original = "# 标题\r\n\"a,b\",日本語\n"
        listOf(
            original.toByteArray(Charsets.UTF_8),
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + original.toByteArray(Charsets.UTF_8),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + original.toByteArray(Charsets.UTF_16LE),
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + original.toByteArray(Charsets.UTF_16BE)
        ).forEach { bytes -> assertEquals(original, LocalDocumentText.read(file(bytes), "notes.MD")) }
        assertEquals(original, LocalDocumentText.read(file(original.toByteArray()), "data.csv"))
        assertProblem("document.textEncoding") { LocalDocumentText.read(file(byteArrayOf(0xC3.toByte(), 0x28)), "notes.txt") }
        assertProblem("document.textTooLarge") {
            LocalDocumentText.read(file("a".repeat(LocalDocumentText.MAX_TEXT_CHARS + 1).toByteArray()), "notes.txt")
        }
        assertTrue(LocalDocumentText.supports("TABLE.XLSX"))
        assertTrue(LocalDocumentText.supports("notes.markdown"))
        assertFalse(LocalDocumentText.supports("TABLE.xlsm"))
    }

    @Test fun xlsxSupportsBothNamespacesCellPositionsStringsAndCachedResultsWithoutReadingOtherSheets() {
        listOf(false, true).forEach { strict ->
            val ns = if (strict) "http://purl.oclc.org/ooxml/spreadsheetml/main"
                else "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
            val sheet = """<worksheet xmlns="$ns"><sheetData><row r="1">
                <c r="A1" t="s"><v>0</v></c><c r="C1" t="inlineStr"><is><t>inline</t></is></c>
                <c r="D1" t="b"><v>1</v></c><c r="E1" s="2"><v>45123</v></c>
                <c r="F1"><f>1+1</f><v>2</v></c><c r="G1"><f>SUM(A1)</f></c>
                </row><row r="4"><c r="B4"><v>3.5</v></c></row></sheetData></worksheet>"""
            val strings = """<sst xmlns="$ns"><si><r><t>shared </t></r><rPh sb="0" eb="1"><t>phonetic</t></rPh><r><t>text</t></r></si></sst>"""
            val book = book(sheet, strict, strings)
            assertEquals(listOf("数学", "Other"), LocalDocumentText.sheets(book))
            val text = LocalDocumentText.read(book, "book.xlsx", listOf("数学"))
            assertTrue(text.contains("A1: shared text") && text.contains("C1: inline"))
            assertTrue(text.contains("D1: true") && text.contains("E1: 45123 [style 2; unformatted number]"))
            assertTrue(text.contains("F1: 2 [cached formula result]") && text.contains("G1: [formula has no cached result; not calculated]"))
            assertTrue(text.contains("B4: 3.5"))
            assertFalse(text.contains("SUM(A1)") || text.contains("phonetic") || text.contains("[Sheet: Other]"))
            assertProblem("document.missingSheets") { LocalDocumentText.read(book, "book.xlsx", listOf("absent")) }
            assertProblem("document.missingSheets") { LocalDocumentText.read(book, "book.xlsx", emptyList()) }
        }
    }

    @Test fun xlsxRejectsEntityDeclarationsExternalSheetRelationshipsAndInvalidXml() {
        val malicious = """<!DOCTYPE worksheet [<!ENTITY xxe SYSTEM "file:///must-never-read">]>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>&xxe;</sheetData></worksheet>"""
        assertProblem("document.invalidXlsx") { LocalDocumentText.read(book(malicious), "book.xlsx", listOf("数学")) }
        assertProblem("document.invalidXlsx") { LocalDocumentText.read(book("<unrelated/>"), "book.xlsx", listOf("数学")) }
        assertProblem("document.invalidXlsx") { LocalDocumentText.sheets(book("", target = "https://example.invalid/sheet.xml", external = true)) }
        assertProblem("document.invalidXlsx") { LocalDocumentText.sheets(book("", target = "../../outside.xml")) }
        val cancelled = assertThrows(CancellationException::class.java) {
            LocalDocumentText.read(book(""), "book.xlsx", listOf("数学")) { throw CancellationException("cancel") }
        }
        assertEquals("cancel", cancelled.message)
    }

    @Test fun expandedXmlAndFinalTextHaveIndependentLimits() {
        val prefix = """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>"""
        val suffix = "</t></is></c></row></sheetData></worksheet>"
        assertProblem("document.textTooLarge") {
            LocalDocumentText.read(book(prefix + "a".repeat(LocalDocumentText.MAX_TEXT_CHARS) + suffix), "book.xlsx", listOf("数学"))
        }
        assertProblem("document.tooLarge") {
            LocalDocumentText.read(book(prefix + "a".repeat(8 * 1024 * 1024) + suffix), "book.xlsx", listOf("数学"))
        }
    }

    private fun file(bytes: ByteArray): File = temporary.newFile().apply { writeBytes(bytes) }

    private fun assertProblem(key: String, block: () -> Unit) {
        assertEquals(key, assertThrows(DocumentProblem::class.java) { block() }.messageKey)
    }

    private fun book(sheet: String, strict: Boolean = false, strings: String? = null,
                     target: String = "worksheets/sheet1.xml", external: Boolean = false): File {
        val ns = if (strict) "http://purl.oclc.org/ooxml/spreadsheetml/main"
            else "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val rel = if (strict) "http://purl.oclc.org/ooxml/officeDocument/relationships"
            else "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val parts = linkedMapOf(
            "xl/workbook.xml" to """<workbook xmlns="$ns" xmlns:r="$rel"><sheets><sheet name="数学" sheetId="1" r:id="r1"/><sheet name="Other" sheetId="2" r:id="r2"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="$rel/worksheet" Target="$target" ${if (external) "TargetMode=\"External\"" else ""}/><Relationship Id="r2" Type="$rel/worksheet" Target="worksheets/sheet2.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to sheet,
            // Deliberately malformed: selecting the first sheet must not open this one.
            "xl/worksheets/sheet2.xml" to "not XML")
        if (strings != null) parts["xl/sharedStrings.xml"] = strings
        return temporary.newFile().apply {
            ZipOutputStream(outputStream()).use { zip ->
                parts.forEach { (path, text) ->
                    zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                }
            }
        }
    }
}
