package nsf.georgeramsis.docengine

import net.sourceforge.tess4j.ITessAPI
import net.sourceforge.tess4j.Tesseract
import net.sourceforge.tess4j.Word
import ocr.TessDataManager
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.util.Matrix
import java.awt.Color
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ─────────────────────────────────────────────────────────────────────────────
// Public API
// ─────────────────────────────────────────────────────────────────────────────

data class ProcessingResult(
    val success: Boolean,
    val message: String,
    val outputFile: File? = null,
    val inputFile: File?  = null
)

enum class DetectionAccuracy {
    STRICT,      // exact text only
    BALANCED,    // tolerates minor OCR noise (recommended default)
    FLEXIBLE,    // tolerates moderate OCR errors
    AGGRESSIVE   // finds text even with heavy OCR corruption
}

// ─────────────────────────────────────────────────────────────────────────────
// Internal domain types
// ─────────────────────────────────────────────────────────────────────────────

private data class OcrWord(
    val text: String,
    val imageBox: Rectangle,
    val lineIndex: Int,
    val wordBoxHeightPx: Int
)

private data class MatchLine(val lineIndex: Int, val imageRect: Rectangle)

private data class TextMatch(val lines: List<MatchLine>, val fontSizePt: Float)

// ─────────────────────────────────────────────────────────────────────────────
// Fuzzy / OCR-aware string similarity
// ─────────────────────────────────────────────────────────────────────────────

private object OcrSimilarity {

    private val CONFUSION_GROUPS = listOf(
        setOf('0', 'o', 'O', 'Q', 'D'),
        setOf('1', 'i', 'I', 'l', 'L', '|', 'j'),
        setOf('t', 'l', 'f'),
        setOf('r', 't', 'n'),
        setOf('5', 's', 'S'),
        setOf('8', 'b', 'B'),
        setOf('3', 'e', 'E'),
        setOf('2', 'z', 'Z'),
        setOf('6', 'g', 'G', 'b'),
        setOf('9', 'q', 'g'),
        setOf('4', 'a', 'A'),
        setOf('u', 'v', 'n'),
        setOf('m', 'n'),
        setOf('w', 'v'),
        setOf('c', 'e', 'o'),
        setOf('n', 'h'),
        setOf('P', 'R')
    )

    private val SUBS: Map<Char, Set<Char>> = buildMap {
        for (group in CONFUSION_GROUPS)
            for (ch in group)
                merge(ch.lowercaseChar(), group.map { it.lowercaseChar() }.toSet()) { a, b -> a + b }
    }

    fun score(a: String, b: String): Double {
        val s1 = a.lowercase(); val s2 = b.lowercase()
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        val m  = s1.length; val n = s2.length
        val dp = Array(m + 1) { row -> IntArray(n + 1) { col -> if (row == 0) col else row } }
        for (i in 1..m) for (j in 1..n) {
            val cost = if (s1[i-1] == s2[j-1] || SUBS[s1[i-1]]?.contains(s2[j-1]) == true) 0 else 1
            dp[i][j] = minOf(dp[i-1][j] + 1, dp[i][j-1] + 1, dp[i-1][j-1] + cost)
        }
        return 1.0 - dp[m][n].toDouble() / max(m, n)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Token sequence matching
// ─────────────────────────────────────────────────────────────────────────────

private object TokenMatcher {

    private val DELIMITERS = Regex("[^A-Za-z0-9]+")

    fun tokenize(text: String): List<String> =
        text.trim().split(DELIMITERS).filter { it.isNotBlank() }

    fun thresholds(accuracy: DetectionAccuracy): Pair<Double, Double> = when (accuracy) {
        DetectionAccuracy.STRICT     -> 1.00 to 1.00
        DetectionAccuracy.BALANCED   -> 0.82 to 0.90
        DetectionAccuracy.FLEXIBLE   -> 0.68 to 0.78
        DetectionAccuracy.AGGRESSIVE -> 0.55 to 0.65
    }

    fun findMatches(
        haystack: List<OcrWord>,
        needle: List<String>,
        wordThreshold: Double,
        windowThreshold: Double
    ): List<IntRange> {
        if (needle.isEmpty() || haystack.size < needle.size) return emptyList()
        val results = mutableListOf<IntRange>()
        var i = 0
        while (i <= haystack.size - needle.size) {
            var hits = 0
            for (j in needle.indices)
                if (OcrSimilarity.score(needle[j], haystack[i + j].text) >= wordThreshold) hits++
            if (hits.toDouble() / needle.size >= windowThreshold) {
                results += i until (i + needle.size)
                i += needle.size
            } else i++
        }
        return results
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// OCR line grouping
// ─────────────────────────────────────────────────────────────────────────────

private fun groupIntoLines(words: List<Word>, toleranceFraction: Float = 0.55f): List<List<Word>> {
    if (words.isEmpty()) return emptyList()
    val sorted    = words.sortedWith(compareBy({ it.boundingBox.y }, { it.boundingBox.x }))
    val heights   = sorted.map { it.boundingBox.height }.sorted()
    val medianH   = heights[heights.size / 2]
    val tolerance = (medianH * toleranceFraction).toInt().coerceAtLeast(6)
    val lines     = mutableListOf<MutableList<Word>>()
    for (word in sorted) {
        val last = lines.lastOrNull()
        val avgY = last?.let { g -> g.sumOf { it.boundingBox.y } / g.size } ?: Int.MIN_VALUE
        if (last == null || abs(word.boundingBox.y - avgY) > tolerance) lines += mutableListOf(word)
        else last += word
    }
    return lines.map { it.sortedBy { w -> w.boundingBox.x } }
}

// ─────────────────────────────────────────────────────────────────────────────
// Match → TextMatch
// ─────────────────────────────────────────────────────────────────────────────

private fun buildTextMatch(
    matchedWords: List<OcrWord>,
    lineBounds: List<Rectangle>,
    pageMedianHeightPx: Int,
    renderDpi: Float
): TextMatch? {
    val heights           = matchedWords.map { it.wordBoxHeightPx }.sorted()
    val medianMatchH      = heights[heights.size / 2]
    val effectiveHeightPx = min(medianMatchH, pageMedianHeightPx)
    val fontSizePt        = effectiveHeightPx * (72f / renderDpi) * 1.436f

    val byLine = matchedWords.groupBy { it.lineIndex }.entries.sortedBy { it.key }

    val matchLines = byLine.mapIndexedNotNull { slot, (lineIndex, wordsOnLine) ->
        val sorted  = wordsOnLine.sortedBy { it.imageBox.x }
        val used    = if (slot == 0) clipAtColumnGap(sorted) else sorted
        val xStart  = used.first().imageBox.x
        val xEnd    = used.last().imageBox.x + used.last().imageBox.width
        val lineBox = lineBounds.getOrNull(lineIndex) ?: return@mapIndexedNotNull null
        MatchLine(lineIndex, Rectangle(xStart, lineBox.y, xEnd - xStart, lineBox.height))
    }

    return if (matchLines.isEmpty()) null else TextMatch(matchLines, fontSizePt)
}

private fun enforceCoverageLines(match: TextMatch, lineBounds: List<Rectangle>, maxLines: Int): TextMatch {
    val sorted = match.lines.sortedBy { it.lineIndex }
    val target = maxLines.coerceAtLeast(1)
    if (sorted.size >= target) return match.copy(lines = sorted.take(target))

    val xStart = sorted.minOf { it.imageRect.x }
    val xEnd   = sorted.maxOf { it.imageRect.x + it.imageRect.width }
    val width  = (xEnd - xStart).coerceAtLeast(1)
    val result = sorted.toMutableList()
    var next   = sorted.last().lineIndex + 1
    while (result.size < target && next < lineBounds.size) {
        result += MatchLine(next, Rectangle(xStart, lineBounds[next].y, width, lineBounds[next].height))
        next++
    }
    return match.copy(lines = result)
}

private fun clipAtColumnGap(words: List<OcrWord>): List<OcrWord> {
    if (words.size <= 1) return words
    val avgWidth = words.map { it.imageBox.width }.average()
    val gapLimit = (avgWidth * 3.0).coerceAtLeast(120.0)
    val result   = mutableListOf(words.first())
    for (i in 1 until words.size) {
        val gap = words[i].imageBox.x - (words[i-1].imageBox.x + words[i-1].imageBox.width)
        if (gap > gapLimit) break
        result += words[i]
    }
    return result
}

// ─────────────────────────────────────────────────────────────────────────────
// Coordinate transform: rendered-image pixels → PDF user-space
// ─────────────────────────────────────────────────────────────────────────────

private fun imageRectToPdf(
    rect: Rectangle, imageWidth: Int, imageHeight: Int, mediaBox: PDRectangle, rotation: Int
): FloatArray {
    val (ix, iy, iw, ih) = rect
    val mW = mediaBox.width; val mH = mediaBox.height
    return when (rotation) {
        90  -> { val sx = mH / imageWidth.toFloat(); val sy = mW / imageHeight.toFloat()
            floatArrayOf(iy * sy, (imageWidth - ix - iw) * sx, ih * sy, iw * sx) }
        180 -> { val sx = mW / imageWidth.toFloat(); val sy = mH / imageHeight.toFloat()
            floatArrayOf((imageWidth - ix - iw) * sx, (imageHeight - iy - ih) * sy, iw * sx, ih * sy) }
        270 -> { val sx = mH / imageWidth.toFloat(); val sy = mW / imageHeight.toFloat()
            floatArrayOf((imageHeight - iy - ih) * sy, mH - (ix + iw) * sx, ih * sy, iw * sx) }
        else-> { val sx = mW / imageWidth.toFloat(); val sy = mH / imageHeight.toFloat()
            floatArrayOf(ix * sx, (imageHeight - iy - ih) * sy, iw * sx, ih * sy) }
    }
}

private operator fun FloatArray.component1() = this[0]
private operator fun FloatArray.component2() = this[1]
private operator fun FloatArray.component3() = this[2]
private operator fun FloatArray.component4() = this[3]

private operator fun Rectangle.component1() = x
private operator fun Rectangle.component2() = y
private operator fun Rectangle.component3() = width
private operator fun Rectangle.component4() = height

// ─────────────────────────────────────────────────────────────────────────────
// Text rendering
// ─────────────────────────────────────────────────────────────────────────────

@Suppress("DEPRECATION")
private fun renderText(
    cs: PDPageContentStream, font: PDType1Font,
    text: String, pdfX: Float, pdfY: Float, pdfW: Float, pdfH: Float,
    rotation: Int, fontSizePt: Float = 0f
) {
    val (lineH, lineW) = when (rotation) { 90, 270 -> pdfW to pdfH; else -> pdfH to pdfW }

    val baseFontSize = if (fontSizePt > 0f) fontSizePt.coerceAtLeast(4f)
    else (lineH * 0.60f).coerceAtLeast(4f)

    val rawTextW = (font.getStringWidth(text) / 1000f) * baseFontSize
    val fontSize = if (rawTextW > lineW - 4f)
        (baseFontSize * ((lineW - 4f) / rawTextW)).coerceAtLeast(4f)
    else baseFontSize

    val vCentre = (lineH - fontSize) / 2f
    val margin  = 2f

    cs.beginText()
    cs.setFont(font, fontSize)
    cs.setNonStrokingColor(Color.BLACK)
    when (rotation) {
        270  -> cs.setTextMatrix(Matrix(0f, -1f,  1f,  0f, pdfX + vCentre,          pdfY + pdfH - margin))
        90   -> cs.setTextMatrix(Matrix(0f,  1f, -1f,  0f, pdfX + pdfW - vCentre,   pdfY + margin))
        180  -> cs.setTextMatrix(Matrix(-1f, 0f,  0f, -1f, pdfX + pdfW - margin,    pdfY + pdfH - vCentre))
        else -> cs.setTextMatrix(Matrix(1f,  0f,  0f,  1f, pdfX + margin,           pdfY + vCentre))
    }
    cs.showText(text)
    cs.endText()
}

// ─────────────────────────────────────────────────────────────────────────────
// Image pre-processing (OpenCV)
// ─────────────────────────────────────────────────────────────────────────────

private fun toBinary(source: BufferedImage): BufferedImage =
    OpenCvOps.prepareForOcr(source, useDenoise = false, useAdaptive = true)

// ─────────────────────────────────────────────────────────────────────────────
// PDFProcessor
// ─────────────────────────────────────────────────────────────────────────────

class PDFProcessor {

    companion object {
        /** Default render DPI for text-replace OCR. Configurable via [AppConfig.textReplaceRenderDpi]. */
        const val DEFAULT_RENDER_DPI = 200f
    }

    @Volatile private var cancelRequested = false
    fun requestCancel() { cancelRequested = true }
    fun resetCancel()   { cancelRequested = false }

    fun processPDF(
        inputFile: File,
        outputDir: File,
        searchText: String,
        replaceText: String,
        maxLines: Int,
        detectionAccuracy: DetectionAccuracy = DetectionAccuracy.BALANCED,
        renderDpi: Float = DEFAULT_RENDER_DPI,
        autoRotate: Boolean = true,
        onProgress: (String) -> Unit
    ): ProcessingResult {
        if (cancelRequested) return cancelled(inputFile)

        val needle = TokenMatcher.tokenize(searchText)
        if (needle.isEmpty()) return ProcessingResult(
            success = false, message = "Search text contains no usable tokens.", inputFile = inputFile
        )

        return try {
            PDDocument.load(inputFile).use { doc ->
                processDocument(doc, inputFile, outputDir, needle, replaceText, maxLines, detectionAccuracy, renderDpi, autoRotate, onProgress)
            }
        } catch (ex: Exception) {
            ProcessingResult(false, "Failed to process ${inputFile.name}: ${ex.message}", inputFile = inputFile)
        }
    }



    @Suppress("DEPRECATION")
    private fun processDocument(
        doc: PDDocument, inputFile: File, outputDir: File,
        needle: List<String>, replaceText: String, maxLines: Int,
        accuracy: DetectionAccuracy, renderDpi: Float, autoRotate: Boolean,
        onProgress: (String) -> Unit
    ): ProcessingResult {
        val renderer     = PDFRenderer(doc)
        val output       = PDDocument()
        val font         = PDType1Font.HELVETICA_BOLD
        val tess         = buildTesseract(renderDpi)
        val osdTess      = Tesseract().apply { 
            setDatapath(TessDataManager.tessDataPath)
            setLanguage("osd")
            setPageSegMode(0)
            setOcrEngineMode(net.sourceforge.tess4j.ITessAPI.TessOcrEngineMode.OEM_DEFAULT)
        }
        val (wordSim, winSim) = TokenMatcher.thresholds(accuracy)
        var totalMatches = 0

        for (pageIndex in 0 until doc.numberOfPages) {
            if (cancelRequested) { output.close(); return cancelled(inputFile) }

            onProgress("Page ${pageIndex + 1} / ${doc.numberOfPages}")

            val page     = doc.getPage(pageIndex)
            val rotation = page.rotation
            val image    = renderer.renderImageWithDPI(pageIndex, renderDpi)
            val binary   = toBinary(image)

            var matchRanges: List<IntRange> = emptyList()
            var haystack: List<OcrWord> = emptyList()
            var pageMedianHeightPx = 0
            var lineBounds: List<Rectangle> = emptyList()
            var activeImage = image

            fun attemptSearch(binImg: BufferedImage) {
                val words = tess.getWords(binImg, ITessAPI.TessPageIteratorLevel.RIL_WORD)
                    .filter { it.text.isNotBlank() }
                if (words.isEmpty()) return

                pageMedianHeightPx = words.map { it.boundingBox.height }.sorted().let { it[it.size / 2] }
                val lineGroups = groupIntoLines(words)
                lineBounds = lineGroups.map { g -> unionRect(g.map { it.boundingBox }) }

                haystack = lineGroups.flatMapIndexed { lineIdx, lineWords ->
                    lineWords.flatMap { word ->
                        TokenMatcher.tokenize(word.text).map { token ->
                            OcrWord(token, word.boundingBox, lineIdx, word.boundingBox.height)
                        }
                    }
                }
                matchRanges = TokenMatcher.findMatches(haystack, needle, wordSim, winSim)
            }

            attemptSearch(binary)

            if (matchRanges.isEmpty() && autoRotate) {
                // Rotation fallback: use OSD to detect page orientation.
                val osdAngle = runCatching {
                    val osdOut = osdTess.doOCR(image)
                    val rv = Regex("(?im)^\\s*Rotate\\s*:\\s*(\\d+)").find(osdOut)?.groupValues?.get(1)?.toIntOrNull()
                    val ov = if (rv == null)
                        Regex("(?im)^\\s*Orientation in degrees\\s*:\\s*(\\d+)").find(osdOut)?.groupValues?.get(1)?.toIntOrNull()?.let { ((360 - it) % 360) }
                    else null
                    rv ?: ov ?: 0
                }.onFailure { e ->
                    println("  ⚠ [OSD] PDFProcessor failed: ${e.message}")
                }.getOrDefault(0)

                // OSD = 0° → page is already upright; text isn't here — skip.
                // OSD ≠ 0° → try only the OSD-suggested angle.
                if (osdAngle != 0) {
                    val rotatedBinary = OpenCvOps.rotate90(binary, osdAngle)
                    activeImage = OpenCvOps.rotate90(image, osdAngle)
                    attemptSearch(rotatedBinary)
                }
            }

            if (matchRanges.isEmpty()) { output.importPage(page); continue }

            onProgress("  -> ${matchRanges.size} match(es) found on page ${pageIndex + 1}")
            totalMatches += matchRanges.size

            val matches: List<TextMatch> = matchRanges.mapNotNull { range ->
                val words    = haystack.subList(range.first, range.last + 1)
                val rawMatch = buildTextMatch(words, lineBounds, pageMedianHeightPx, renderDpi)
                    ?: return@mapNotNull null
                enforceCoverageLines(rawMatch, lineBounds, maxLines)
            }

            val newPage = output.importPage(page)
            applyReplacements(output, newPage, activeImage, matches, replaceText, font, rotation)
        }

        if (totalMatches == 0) {
            output.close()
            return ProcessingResult(false, "Text not found in ${inputFile.name}.", inputFile = inputFile)
        }

        // Save to a .tmp file so PdfService can safely move/rename without
        // conflicting with the still-open source file on Windows.
        val tmpFile = File(outputDir, "${inputFile.nameWithoutExtension}.replacing.tmp")
        output.save(tmpFile)
        output.close()
        return ProcessingResult(true, "Replaced $totalMatches occurrence(s).", tmpFile, inputFile)
    }

    @Suppress("DEPRECATION")
    private fun applyReplacements(
        doc: PDDocument, page: PDPage, image: BufferedImage,
        matches: List<TextMatch>, replaceText: String, font: PDType1Font, rotation: Int
    ) {
        val mediaBox = page.mediaBox
        val imgW = image.width; val imgH = image.height
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            for (match in matches) {
                val block = paddedImageRect(
                    unionRect(match.lines.map { it.imageRect }), padX = 6, padY = 4, maxWidth = imgW, maxHeight = imgH
                )
                cs.setNonStrokingColor(Color.WHITE)
                val (bx, by, bw, bh) = imageRectToPdf(block, imgW, imgH, mediaBox, rotation)
                cs.addRect(bx, by, bw, bh); cs.fill()

                val firstLine = match.lines.minByOrNull { it.imageRect.y }!!.imageRect
                val textBox   = Rectangle(block.x, firstLine.y, block.width, firstLine.height)
                val (px, py, pw, ph) = imageRectToPdf(textBox, imgW, imgH, mediaBox, rotation)
                renderText(cs, font, replaceText, px, py, pw, ph, rotation, match.fontSizePt)
            }
        }
    }

    private fun buildTesseract(renderDpi: Float = DEFAULT_RENDER_DPI): Tesseract = Tesseract().apply {
        setDatapath(TessDataManager.tessDataPath)
        setLanguage("eng")
        setPageSegMode(ITessAPI.TessPageSegMode.PSM_AUTO)
        setOcrEngineMode(ITessAPI.TessOcrEngineMode.OEM_LSTM_ONLY)
        setVariable("user_defined_dpi", renderDpi.toInt().toString())
        setVariable("preserve_interword_spaces", "1")
    }

    private fun unionRect(rects: List<Rectangle>): Rectangle {
        var x1 = Int.MAX_VALUE; var y1 = Int.MAX_VALUE
        var x2 = Int.MIN_VALUE; var y2 = Int.MIN_VALUE
        for (r in rects) { x1 = min(x1, r.x); y1 = min(y1, r.y); x2 = max(x2, r.x + r.width); y2 = max(y2, r.y + r.height) }
        return Rectangle(x1, y1, x2 - x1, y2 - y1)
    }

    private fun paddedImageRect(rect: Rectangle, padX: Int, padY: Int, maxWidth: Int, maxHeight: Int): Rectangle {
        val x      = (rect.x - padX).coerceAtLeast(0)
        val y      = (rect.y - padY).coerceAtLeast(0)
        val right  = (rect.x + rect.width  + padX).coerceAtMost(maxWidth)
        val bottom = (rect.y + rect.height + padY).coerceAtMost(maxHeight)
        return Rectangle(x, y, (right - x).coerceAtLeast(1), (bottom - y).coerceAtLeast(1))
    }

    private fun cancelled(file: File) = ProcessingResult(false, "Cancelled: ${file.name}", inputFile = file)
}
