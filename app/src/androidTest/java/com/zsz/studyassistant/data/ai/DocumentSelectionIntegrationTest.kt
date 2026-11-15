package com.zsz.studyassistant.data

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zsz.studyassistant.ChatItem
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Optional synthetic verification for the explicitly approved document paths; never an online gate. */
@RunWith(AndroidJUnit4::class)
class DocumentSelectionIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>().also {
        check(it.packageName == "com.zsz.studyassistant.integration")
    }

    @Test fun selectedPdfKeepsTextAndOriginalAndBackupSelection() {
        val work = File(context.cacheDir, "selected-pdf-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val original = File(work, "original.pdf")
            val pdf = PdfDocument()
            try {
                repeat(3) { index ->
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                    page.canvas.drawText("SELECTED_PAGE_${index + 1}", 30f, 70f, Paint().apply { textSize = 18f })
                    pdf.finishPage(page)
                }
                original.outputStream().use(pdf::writeTo)
            } finally { pdf.close() }
            val originalHash = DocumentStore.hash(original)
            assertEquals(3, PdfDocumentSelection.pageCount(original))
            val thumbnail = PdfDocumentSelection.thumbnail(original, 1)
            assertTrue(thumbnail.width > 0); thumbnail.recycle()
            val selected = File(work, "selected.pdf")
            PdfDocumentSelection.writeSelected(context, original, listOf(0, 2), selected)
            PdfRenderer(ParcelFileDescriptor.open(selected, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                assertEquals(2, pdf.pageCount)
                val text = (0 until pdf.pageCount).joinToString("\n") { index ->
                    pdf.openPage(index).use { page -> page.textContents.joinToString("") { it.text } }
                }.replace(Regex("\\s+"), "")
                assertTrue(text.contains("SELECTED_PAGE_1") && text.contains("SELECTED_PAGE_3"))
                assertFalse(text.contains("SELECTED_PAGE_2"))
            }
            assertEquals(originalHash, DocumentStore.hash(original))
            val metadata = ConversationDocument(originalHash, "original.pdf", original.length(), pages = listOf(0, 2))
            assertNotEquals(metadata.requestKey, metadata.copy(pages = listOf(1)).requestKey)
            val store = DocumentStore(context)
            store.install(original, metadata)
            val tree = File(work, "tree").apply { mkdirs(); File(this, "resources").mkdir() }
            val before = Json.encodeToString(listOf(ChatItem(1, "question", "fixture", documents = listOf(metadata))))
            val packed = BackupTree.imagesJson(before, tree, false, true, {}, sourceDocuments = store.directory)
            var restoredHash: String? = null
            val restored = BackupTree.imagesJson(packed, tree, true, true, {}, restoredDocument = { resource, _ ->
                restoredHash = DocumentStore.hash(resource)
            })
            val after = Json.decodeFromString<List<ChatItem>>(restored)
            assertEquals(metadata, after.single().documents.single())
            assertEquals(originalHash, restoredHash)
        } finally { work.deleteRecursively() }
    }

    @Test fun localSheetsAndPortableRulesRoundTripWithoutBroadeningScope() {
        val work = File(context.cacheDir, "selected-sheet-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val file = File(work, "fixture.xlsx")
            val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
            val rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
            val parts = mapOf(
                "xl/workbook.xml" to """<workbook xmlns="$ns" xmlns:r="$rel"><sheets><sheet name="Math" sheetId="1" r:id="r1"/><sheet name="Other" sheetId="2" r:id="r2"/></sheets></workbook>""",
                "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="$rel/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="r2" Type="$rel/worksheet" Target="worksheets/sheet2.xml"/></Relationships>""",
                "xl/worksheets/sheet1.xml" to """<worksheet xmlns="$ns"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>KEEP</t></is></c><c r="B1"><f>1+1</f><v>2</v></c></row></sheetData></worksheet>""",
                "xl/worksheets/sheet2.xml" to """<worksheet xmlns="$ns"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>EXCLUDED</t></is></c></row></sheetData></worksheet>""")
            ZipOutputStream(file.outputStream()).use { zip -> parts.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            } }
            assertEquals(listOf("Math", "Other"), LocalDocumentText.sheets(file))
            val text = LocalDocumentText.read(file, "fixture.xlsx", listOf("Math"))
            assertTrue(text.contains("A1: KEEP") && text.contains("B1: 2")); assertFalse(text.contains("EXCLUDED"))
            val metadata = ConversationDocument(DocumentStore.hash(file), "fixture.xlsx", file.length(), sheets = listOf("Math"))
            val store = DocumentStore(context); store.install(file, metadata)
            val tree = File(work, "tree").apply { mkdirs(); File(this, "resources").mkdir() }
            val packed = BackupTree.imagesJson(Json.encodeToString(listOf(ChatItem(2, "question", "fixture", documents = listOf(metadata)))), tree, false, true, {}, store.directory)
            val restored = BackupTree.imagesJson(packed, tree, true, true, {}, restoredDocument = { resource, _ ->
                assertEquals(metadata.sha256, DocumentStore.hash(resource))
            })
            assertEquals(metadata, Json.decodeFromString<List<ChatItem>>(restored).single().documents.single())
            val rules = listOf(CustomPromptRule("fixture-rule", "  Keep units\n", false, false, listOf(1)))
            val backup = PromptRuleBackupCodec.decode(PromptRuleBackupCodec.export(rules, listOf(Category(1, "Math"))))
            val categories = listOf(Category(99, "Math"))
            val result = PromptRuleBackupCodec.resolve(backup, PromptRuleBackupCodec.suggestMapping(backup, categories), categories).single()
            assertEquals(listOf(99L), result.categoryIds); assertEquals(rules.single().text, result.text)
            assertFalse(result.enabled); assertFalse(result.allSubjects)
            assertThrows(PromptBackupException::class.java) { PromptRuleBackupCodec.resolve(backup, emptyMap(), categories) }
            assertThrows(PromptBackupException::class.java) {
                PromptRuleBackupCodec.export(listOf(rules.single().copy(categoryIds = listOf(1, 2))),
                    listOf(Category(1, "Math"), Category(2, "Math")))
            }
        } finally { work.deleteRecursively() }
    }
}
