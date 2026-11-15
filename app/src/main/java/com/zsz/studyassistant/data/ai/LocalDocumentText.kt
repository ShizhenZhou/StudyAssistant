package com.zsz.studyassistant.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/** Local, bounded text extraction. CSV/Markdown remain verbatim; spreadsheet formulas are never evaluated. */
object LocalDocumentText {
    const val MAX_TEXT_CHARS = 200_000
    private const val MAX_XML_BYTES = 8 * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 32 * 1024 * 1024
    private val spreadsheetNamespaces = setOf(
        "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
        "http://purl.oclc.org/ooxml/spreadsheetml/main")
    private val officeNamespaces = setOf(
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
        "http://purl.oclc.org/ooxml/officeDocument/relationships")
    private const val PACKAGE_NS = "http://schemas.openxmlformats.org/package/2006/relationships"

    fun supports(name: String): Boolean = extension(name) in setOf("txt", "md", "markdown", "csv", "xlsx")

    fun sheets(file: File, check: () -> Unit = {}): List<String> = workbook(file, check) { zip ->
        zip.sheets().map { it.name }
    }

    fun read(file: File, name: String, selectedSheets: List<String>? = null, check: () -> Unit = {}): String {
        if (!supports(name)) throw DocumentProblem("document.invalid")
        if (extension(name) != "xlsx") {
            val bytes = file.inputStream().use { bounded(it, MAX_TEXT_CHARS * 4 + 3, check, "document.textTooLarge") }
            val text = decode(bytes)
            if ('\u0000' in text) throw DocumentProblem("document.textEncoding")
            if (text.length > MAX_TEXT_CHARS) throw DocumentProblem("document.textTooLarge")
            return text
        }
        return workbook(file, check) { zip ->
            val all = zip.sheets()
            val selection = selectedSheets?.toSet() ?: all.map { it.name }.toSet()
            if (selection.isEmpty() || selection.any { name -> all.none { it.name == name } })
                throw DocumentProblem("document.missingSheets")
            val output = StringBuilder()
            fun append(text: String) {
                if (output.length + text.length > MAX_TEXT_CHARS) throw DocumentProblem("document.textTooLarge")
                output.append(text)
            }
            val strings by lazy { zip.sharedStrings() }
            all.filter { it.name in selection }.forEach { sheet ->
                check()
                append("[Sheet: ${sheet.name}]\n")
                zip.xml(sheet.path, "worksheet", object : DefaultHandler() {
                    var row = 0
                    var column = 0
                    var reference: String? = null
                    var type = "n"
                    var style: String? = null
                    var formula = false
                    var readingValue = false
                    var readingInline = false
                    var phonetic = false
                    var hasValue = false
                    val value = StringBuilder()
                    val inline = StringBuilder()
                    override fun startElement(uri: String, local: String, qName: String, a: Attributes) {
                        if (uri !in spreadsheetNamespaces) return
                        when (local) {
                            "row" -> { row = a.getValue("r")?.toIntOrNull() ?: (row + 1); column = 0; valid(row in 1..1_048_576) }
                            "c" -> {
                                valid(reference == null && row > 0)
                                val ref = a.getValue("r") ?: "${columnName(column + 1)}$row"
                                val match = Regex("([A-Z]{1,3})([1-9][0-9]{0,6})").matchEntire(ref) ?: invalid()
                                column = match.groupValues[1].fold(0) { n, c -> n * 26 + (c - 'A' + 1) }
                                valid(column in 1..16384 && match.groupValues[2].toInt() == row)
                                reference = ref; type = a.getValue("t") ?: "n"; style = a.getValue("s")
                                valid(style == null || style!!.toIntOrNull()?.let { it >= 0 } == true)
                                value.setLength(0); inline.setLength(0); formula = false; hasValue = false
                            }
                            "f" -> if (reference != null) formula = true
                            "v" -> if (reference != null) { readingValue = true; hasValue = true }
                            "rPh" -> phonetic = true
                            "t" -> if (reference != null && type == "inlineStr" && !phonetic) readingInline = true
                        }
                    }
                    override fun characters(ch: CharArray, start: Int, length: Int) {
                        if (readingValue) value.append(ch, start, length)
                        if (readingInline) inline.append(ch, start, length)
                        if (value.length + inline.length > MAX_TEXT_CHARS) throw DocumentProblem("document.textTooLarge")
                    }
                    override fun endElement(uri: String, local: String, qName: String) {
                        if (uri !in spreadsheetNamespaces) return
                        when (local) {
                            "v" -> readingValue = false
                            "t" -> readingInline = false
                            "rPh" -> phonetic = false
                            "c" -> {
                                val ref = reference ?: invalid()
                                val raw = value.toString()
                                val text = when {
                                    formula && (!hasValue || raw.isEmpty()) -> "[formula has no cached result; not calculated]"
                                    type == "inlineStr" -> inline.toString()
                                    !hasValue -> ""
                                    type == "s" -> strings.getOrNull(raw.toIntOrNull() ?: invalid()) ?: invalid()
                                    type == "b" -> when (raw) { "1" -> "true"; "0" -> "false"; else -> invalid() }
                                    type == "e" -> "[cell error: $raw]"
                                    type in setOf("n", "str", "d") -> raw
                                    else -> invalid()
                                }
                                if (text.isNotEmpty()) {
                                    append("$ref: $text")
                                    if (formula && hasValue && raw.isNotEmpty()) append(" [cached formula result]")
                                    if (type == "n" && style != null && raw.isNotEmpty()) append(" [style $style; unformatted number]")
                                    append("\n")
                                }
                                reference = null
                            }
                        }
                    }
                })
                append("\n")
            }
            output.toString()
        }
    }

    private data class Sheet(val name: String, val path: String)

    private class Book(val zip: ZipFile, val check: () -> Unit) {
        var expanded = 0

        fun xml(path: String, root: String, handler: DefaultHandler) {
            check()
            val entry = zip.getEntry(path) ?: invalid()
            valid(!entry.isDirectory)
            if (entry.size > MAX_XML_BYTES) throw DocumentProblem("document.tooLarge")
            val bytes = zip.getInputStream(entry).use {
                bounded(it, minOf(MAX_XML_BYTES, MAX_EXPANDED_BYTES - expanded), check, "document.tooLarge")
            }
            expanded += bytes.size
            val text = decode(bytes)
            // Reject declarations before any parser sees them, including UTF-16 BOM input.
            valid(!text.contains("<!DOCTYPE") && !text.contains("<!ENTITY"))
            val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true; isValidating = false }
                .newSAXParser().xmlReader
            // Some Android SAX implementations do not support these switches; the declaration
            // rejection and throwing resolver remain mandatory and independent of those switches.
            listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities")
                .forEach { feature -> runCatching { reader.setFeature(feature, false) } }
            reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> throw SAXException("External entities are forbidden") }
            reader.contentHandler = object : DefaultHandler() {
                var depth = 0
                override fun startElement(uri: String, local: String, qName: String, a: Attributes) {
                    check(); depth++; valid(depth <= 64)
                    if (depth == 1) valid(local == root &&
                        (if (root == "Relationships") uri == PACKAGE_NS else uri in spreadsheetNamespaces))
                    handler.startElement(uri, local, qName, a)
                }
                override fun endElement(uri: String, local: String, qName: String) {
                    handler.endElement(uri, local, qName); depth--
                }
                override fun characters(ch: CharArray, start: Int, length: Int) {
                    check(); handler.characters(ch, start, length)
                }
            }
            reader.errorHandler = object : DefaultHandler() {
                override fun error(e: org.xml.sax.SAXParseException) { throw e }
                override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
            }
            reader.parse(InputSource(StringReader(text)))
        }

        fun sheets(): List<Sheet> {
            val paths = mutableMapOf<String, String>()
            xml("xl/_rels/workbook.xml.rels", "Relationships", object : DefaultHandler() {
                override fun startElement(uri: String, local: String, qName: String, a: Attributes) {
                    if (uri != PACKAGE_NS || local != "Relationship") return
                    if (a.getValue("Type") !in officeNamespaces.map { "$it/worksheet" }) return
                    valid(a.getValue("TargetMode") != "External")
                    val id = a.getValue("Id") ?: invalid()
                    valid(paths.put(id, resolvePath(a.getValue("Target") ?: invalid())) == null)
                }
            })
            val result = mutableListOf<Sheet>()
            xml("xl/workbook.xml", "workbook", object : DefaultHandler() {
                override fun startElement(uri: String, local: String, qName: String, a: Attributes) {
                    if (uri !in spreadsheetNamespaces || local != "sheet") return
                    val name = a.getValue("name") ?: invalid()
                    val id = officeNamespaces.firstNotNullOfOrNull { a.getValue(it, "id") } ?: invalid()
                    valid(name.isNotEmpty() && name.length <= 31 && result.size < 1024 && result.none { it.name == name })
                    result += Sheet(name, paths[id] ?: invalid())
                }
            })
            valid(result.isNotEmpty())
            return result
        }

        fun sharedStrings(): List<String> {
            val result = mutableListOf<String>()
            val current = StringBuilder()
            var reading = false
            var phonetic = false
            xml("xl/sharedStrings.xml", "sst", object : DefaultHandler() {
                override fun startElement(uri: String, local: String, qName: String, a: Attributes) {
                    if (uri !in spreadsheetNamespaces) return
                    when (local) { "si" -> current.setLength(0); "rPh" -> phonetic = true; "t" -> reading = !phonetic }
                }
                override fun characters(ch: CharArray, start: Int, length: Int) { if (reading) current.append(ch, start, length) }
                override fun endElement(uri: String, local: String, qName: String) {
                    if (uri !in spreadsheetNamespaces) return
                    when (local) {
                        "t" -> reading = false
                        "rPh" -> phonetic = false
                        "si" -> { valid(result.size < 200_000); result += current.toString() }
                    }
                }
            })
            return result
        }
    }

    private fun <T> workbook(file: File, check: () -> Unit, action: (Book) -> T): T = try {
        check()
        ZipFile(file).use { zip ->
            valid(zip.size() <= 4096)
            val names = HashSet<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) { check(); valid(names.add(entries.nextElement().name)) }
            action(Book(zip, check))
        }
    } catch (error: CancellationException) { throw error }
    catch (error: DocumentProblem) { throw error }
    catch (_: Exception) { throw DocumentProblem("document.invalidXlsx") }

    private fun bounded(input: InputStream, max: Int, check: () -> Unit, errorKey: String): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            check()
            val n = input.read(buffer)
            if (n < 0) break
            if (result.size() + n > max) throw DocumentProblem(errorKey)
            result.write(buffer, 0, n)
        }
        return result.toByteArray()
    }

    private fun decode(bytes: ByteArray): String {
        val prefix = bytes.take(4).map { it.toInt() and 255 }
        if (prefix == listOf(255, 254, 0, 0) || prefix == listOf(0, 0, 254, 255)) throw DocumentProblem("document.textEncoding")
        val (charset, skip) = when {
            prefix.take(3) == listOf(239, 187, 191) -> Charsets.UTF_8 to 3
            prefix.take(2) == listOf(255, 254) -> Charsets.UTF_16LE to 2
            prefix.take(2) == listOf(254, 255) -> Charsets.UTF_16BE to 2
            else -> Charsets.UTF_8 to 0
        }
        return try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, skip, bytes.size - skip)).toString()
        } catch (_: CharacterCodingException) { throw DocumentProblem("document.textEncoding") }
    }

    private fun resolvePath(target: String): String {
        valid(target.isNotEmpty() && target.none { it == '\\' || it == ':' || it == '#' || it == '?' || it == '%' })
        val parts = if (target.startsWith('/')) mutableListOf() else mutableListOf("xl")
        target.split('/').forEach { part -> when (part) {
            "", "." -> Unit
            ".." -> { valid(parts.isNotEmpty()); parts.removeAt(parts.lastIndex) }
            else -> parts += part
        } }
        valid(parts.size >= 2 && parts.first() == "xl")
        return parts.joinToString("/")
    }

    private fun columnName(index: Int): String {
        valid(index in 1..16384)
        var n = index
        var text = ""
        while (n > 0) { n--; text = ('A' + n % 26) + text; n /= 26 }
        return text
    }
    private fun extension(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    private fun valid(condition: Boolean) { if (!condition) invalid() }
    private fun invalid(): Nothing = throw DocumentProblem("document.invalidXlsx")
}
