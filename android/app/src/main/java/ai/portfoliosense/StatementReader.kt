package ai.portfoliosense

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser

internal object StatementReader {
    private const val maxText = 90000
    private const val maxBytes = 24L * 1024 * 1024

    fun read(context: Context, documents: List<Pair<Uri, String>>): String {
        val result = StringBuilder()
        documents.forEachIndexed { index, (uri, name) ->
            val tmp = File.createTempFile("statement-", ".tmp", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= maxBytes) {
                                "A statement exceeds the 24 MB limit. Split it and try again."
                            }
                            output.write(buffer, 0, n)
                        }
                    }
                } ?: error("Cannot read the selected document.")
                val text =
                    when (name.substringAfterLast('.').lowercase()) {
                        "pdf" -> {
                            PDFBoxResourceLoader.init(context)
                            PDDocument.load(tmp).use { pdf ->
                                require(
                                    !pdf.isEncrypted ||
                                        pdf.currentAccessPermission.canExtractContent()
                                ) {
                                    "Use a PDF that allows text extraction."
                                }
                                val stripper = PDFTextStripper().apply { sortByPosition = true }
                                buildString {
                                    for (page in 1..pdf.numberOfPages) {
                                        stripper.startPage = page
                                        stripper.endPage = page
                                        appendLine(stripper.getText(pdf))
                                        checkLength(length)
                                    }
                                }
                            }
                        }
                        "xlsx" -> ZipFile(tmp).use(::spreadsheet)
                        "docx" ->
                            ZipFile(tmp).use { zip ->
                                buildString {
                                    xml(zip, "word/document.xml") { p ->
                                        if (p.eventType == XmlPullParser.START_TAG && p.name == "t")
                                            append(p.nextText())
                                        if (
                                            p.eventType == XmlPullParser.END_TAG &&
                                                p.name in listOf("p", "tr", "tc")
                                        )
                                            append('\n')
                                        checkLength(length)
                                    }
                                }
                            }
                        else -> error("Choose a PDF, XLSX or DOCX statement.")
                    }
                require(text.isNotBlank()) {
                    "No readable text in statement ${index+1}. For a scanned PDF, use a searchable export."
                }
                result.appendLine("Statement ${index+1}").appendLine(text)
                checkLength(result.length)
            } finally {
                tmp.delete()
            }
        }
        return result
            .toString()
            .replace(Regex("""(?i)\b[A-Z]{5}[0-9]{4}[A-Z]\b"""), "[PAN]")
            .replace(Regex("""(?<![\w.])\d{10,12}(?![\w.])"""), "[identifier]")
            .replace(Regex("""[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}"""), "[email]")
    }

    private fun checkLength(n: Int) =
        require(n <= maxText) {
            "Too much statement text. Select a smaller date range or fewer files."
        }

    private fun xml(zip: ZipFile, path: String, visit: (XmlPullParser) -> Unit) {
        val entry = zip.getEntry(path) ?: error("Invalid Office document.")
        require(entry.size in 0..maxBytes) { "Office document section is too large." }
        zip.getInputStream(entry).use { input ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(input, null)
            while (parser.nextToken() != XmlPullParser.END_DOCUMENT) {
                require(parser.eventType != XmlPullParser.DOCDECL) {
                    "Unsupported Office document XML."
                }
                visit(parser)
            }
        }
    }

    private fun spreadsheet(zip: ZipFile): String {
        val strings = mutableListOf<String>()
        if (zip.getEntry("xl/sharedStrings.xml") != null) {
            var value = StringBuilder()
            xml(zip, "xl/sharedStrings.xml") { p ->
                when {
                    p.eventType == XmlPullParser.START_TAG && p.name == "si" ->
                        value = StringBuilder()
                    p.eventType == XmlPullParser.START_TAG && p.name == "t" ->
                        value.append(p.nextText())
                    p.eventType == XmlPullParser.END_TAG && p.name == "si" ->
                        strings.add(value.toString())
                }
            }
        }
        return buildString {
            val sheets =
                zip.entries()
                    .asSequence()
                    .map { it.name }
                    .filter { it.matches(Regex("""xl/worksheets/[^/]+\.xml""")) }
                    .sorted()
            for (sheet in sheets) {
                appendLine()
                var type = ""
                xml(zip, sheet) { p ->
                    when {
                        p.eventType == XmlPullParser.START_TAG && p.name == "c" -> {
                            type = p.getAttributeValue(null, "t").orEmpty()
                            append(p.getAttributeValue(null, "r").orEmpty()).append('=')
                        }
                        p.eventType == XmlPullParser.START_TAG && p.name in listOf("v", "t") -> {
                            val v = p.nextText()
                            append(
                                if (type == "s") strings.getOrNull(v.toIntOrNull() ?: -1).orEmpty()
                                else v
                            )
                        }
                        p.eventType == XmlPullParser.END_TAG && p.name == "c" -> append('\t')
                        p.eventType == XmlPullParser.END_TAG && p.name == "row" -> appendLine()
                    }
                    checkLength(length)
                }
            }
        }
    }
}
