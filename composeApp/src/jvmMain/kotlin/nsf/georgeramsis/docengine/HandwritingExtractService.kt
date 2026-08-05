package nsf.georgeramsis.docengine

import net.sourceforge.tess4j.ITessAPI
import net.sourceforge.tess4j.Tesseract
import ocr.TessDataManager
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.File
import java.time.LocalDateTime

// ─────────────────────────────────────────────────────────────────────────────
// Result types
// ─────────────────────────────────────────────────────────────────────────────

data class HandwritingPageResult(
    val pageNumber: Int,
    val hasHandwriting: Boolean,
    val confidence: Double,        // 0.0–1.0 stroke-width CV score
    val extractedText: String,
    val wordCount: Int
)

data class HandwritingDocResult(
    val file: File,
    val success: Boolean,
    val message: String,
    val pages: List<HandwritingPageResult>,
    val reportFile: File? = null,
    val textOnlyFile: File? = null
)

// ─────────────────────────────────────────────────────────────────────────────
// Service
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Scans PDF files for handwritten content and extracts the text.
 *
 * Detection pipeline per page:
 *  1. Render at [DETECT_DPI] (150 DPI) — fast, low memory
 *  2. Score via [HandwritingPreprocessor.handwritingConfidence]
 *     (stroke-width Coefficient of Variation heuristic)
 *  3. If score ≥ [minConfidence]:
 *     a. Re-render at [OCR_DPI] (300 DPI) for accuracy
 *     b. OpenCV preprocessing: denoise → CLAHE → Gaussian adaptive threshold
 *     c. OCR with dict-free LSTM Tesseract settings tuned for handwriting
 *     d. Append result to the report
 *
 * Image preprocessing is powered by OpenCV via JavaCV, providing CLAHE
 * adaptive contrast, non-local means denoising, and Gaussian adaptive
 * thresholding — significantly faster and more accurate on uneven scans
 * than the previous pure Java AWT implementation.
 */
class HandwritingExtractService {

    companion object {
        private const val DETECT_DPI = 150f   // fast detection pass
        private const val OCR_DPI    = 300f   // full-quality OCR pass
    }

    @Volatile private var cancelRequested = false
    fun requestCancel() { cancelRequested = true }
    fun resetCancel()   { cancelRequested = false }

    // ── Main entry point ──────────────────────────────────────────────────────

    /**
     * Scans [inputFile] for handwritten content and returns per-page results.
     *
     * @param minConfidence  pages with a handwriting score below this are
     *                       treated as print-only  (0.0–1.0, default 0.25)
     * @param saveReport     when true a `<name>_handwriting.txt` report is
     *                       written beside the original PDF
     * @param onProgress     receives human-readable status strings
     */
    fun extractFromFile(
        inputFile: File,
        minConfidence: Double = 0.25,
        saveReport: Boolean   = true,
        textOnly: Boolean     = false,
        onProgress: (String) -> Unit
    ): HandwritingDocResult {

        val pageResults = mutableListOf<HandwritingPageResult>()
        val tess = buildHandwritingTesseract()

        try {
            PDDocument.load(inputFile).use { doc ->
                val renderer = PDFRenderer(doc)
                val total    = doc.numberOfPages

                for (pageIdx in 0 until total) {
                    if (cancelRequested) break

                    onProgress("Page ${pageIdx + 1} / $total — detecting…")

                    // ── Step 1: fast detection render ──────────────────────
                    val detectImage = renderer.renderImageWithDPI(pageIdx, DETECT_DPI, ImageType.GRAY)
                    val confidence  = HandwritingPreprocessor.handwritingConfidence(detectImage)
                    detectImage.flush()

                    onProgress("  Page ${pageIdx + 1}: handwriting score = ${"%.2f".format(confidence)}")

                    if (confidence < minConfidence) {
                        pageResults += HandwritingPageResult(
                            pageNumber     = pageIdx + 1,
                            hasHandwriting = false,
                            confidence     = confidence,
                            extractedText  = "",
                            wordCount      = 0
                        )
                        continue
                    }

                    // ── Step 2: high-DPI render + handwriting preprocessing ─
                    onProgress("  Page ${pageIdx + 1}: re-rendering at ${OCR_DPI.toInt()} DPI for OCR…")

                    val ocrImage      = renderer.renderImageWithDPI(pageIdx, OCR_DPI, ImageType.GRAY)
                    val preprocessed  = HandwritingPreprocessor.prepareForHandwriting(ocrImage)
                    ocrImage.flush()

                    // ── Step 3: OCR with handwriting-tuned settings ────────
                    val text      = runCatching { tess.doOCR(preprocessed) }.getOrDefault("").trim()
                    preprocessed.flush()

                    val wordCount = text.split("\\s+".toRegex()).count { it.length > 1 }

                    pageResults += HandwritingPageResult(
                        pageNumber     = pageIdx + 1,
                        hasHandwriting = true,
                        confidence     = confidence,
                        extractedText  = text,
                        wordCount      = wordCount
                    )
                    onProgress("  ✅ Page ${pageIdx + 1}: ~$wordCount word(s) extracted")
                }
            }
        } catch (ex: Exception) {
            return HandwritingDocResult(
                file    = inputFile,
                success = false,
                message = "Failed: ${ex.message}",
                pages   = pageResults
            )
        }

        val handwritingPages = pageResults.count { it.hasHandwriting }

        val reportFile: File? = if (saveReport && handwritingPages > 0)
            writeReport(inputFile, pageResults)
        else null

        val textOnlyFile: File? = if (textOnly && handwritingPages > 0)
            writeTextOnly(inputFile, pageResults)
        else null

        val message = when (handwritingPages) {
            0    -> "No handwriting detected."
            else -> "Handwriting detected on $handwritingPages / ${pageResults.size} page(s)."
        }

        return HandwritingDocResult(
            file         = inputFile,
            success      = true,
            message      = message,
            pages        = pageResults,
            reportFile   = reportFile,
            textOnlyFile = textOnlyFile
        )
    }

    // ── Report ────────────────────────────────────────────────────────────────

    private fun writeReport(inputFile: File, pages: List<HandwritingPageResult>): File {
        val report = File(inputFile.parentFile, "${inputFile.nameWithoutExtension}_handwriting.txt")
        report.printWriter().use { w ->
            w.println("═".repeat(60))
            w.println("  DocEngine — Handwriting Extraction Report")
            w.println("═".repeat(60))
            w.println("  File   : ${inputFile.name}")
            w.println("  Date   : ${LocalDateTime.now()}")
            w.println("  Pages  : ${pages.size}  |  Handwriting : ${pages.count { it.hasHandwriting }}")
            w.println("═".repeat(60))

            for (p in pages.filter { it.hasHandwriting }) {
                w.println()
                w.println("── Page ${p.pageNumber}  (confidence: ${"%.0f".format(p.confidence * 100)}%) ──")
                w.println(p.extractedText.ifBlank { "(no text could be extracted from this page)" })
            }

            w.println()
            w.println("═".repeat(60))
            w.println("  Generated by DocEngine v${BuildConfig.VERSION_FULL}")
        }
        return report
    }

    /**
     * Writes a plain-text file containing **only** the extracted handwritten text —
     * no headers, no metadata, no page numbers.  Each page's text is separated by
     * a blank line.  Ideal for downstream processing or pasting into other tools.
     */
    private fun writeTextOnly(inputFile: File, pages: List<HandwritingPageResult>): File {
        val txtFile = File(inputFile.parentFile, "${inputFile.nameWithoutExtension}_handwriting_text.txt")
        txtFile.printWriter().use { w ->
            val hwPages = pages.filter { it.hasHandwriting && it.extractedText.isNotBlank() }
            hwPages.forEachIndexed { idx, p ->
                w.print(p.extractedText.trim())
                if (idx < hwPages.lastIndex) {
                    w.println()
                    w.println()
                }
            }
        }
        return txtFile
    }

    // ── Tesseract configuration ───────────────────────────────────────────────
    //
    // Key differences from the standard OCR configuration:
    //  • PSM_AUTO     — lets Tesseract segment freely (handwriting doesn't align to tight columns)
    //  • All dictionary dawgs disabled — handwriting frequently fails spell-check, causing
    //    confusing substitutions; raw LSTM output is more accurate
    //  • tessedit_min_word_length = 1 — single-char annotations ("✓", "A", "3") are valid

    private fun buildHandwritingTesseract(): Tesseract = Tesseract().apply {
        setDatapath(TessDataManager.tessDataPath)
        setLanguage("eng")
        setPageSegMode(ITessAPI.TessPageSegMode.PSM_AUTO)
        setOcrEngineMode(ITessAPI.TessOcrEngineMode.OEM_LSTM_ONLY)
        setVariable("user_defined_dpi", OCR_DPI.toInt().toString())
        // Disable all dictionary constraints — handwriting OCR is purely LSTM
        setVariable("load_system_dawg",          "0")
        setVariable("load_freq_dawg",            "0")
        setVariable("load_unambig_dawg",         "0")
        setVariable("load_punc_dawg",            "0")
        setVariable("load_number_dawg",          "0")
        setVariable("load_bigram_dawg",          "0")
        setVariable("classify_enable_learning",  "0")
        setVariable("tessedit_min_word_length",  "1")
        setVariable("preserve_interword_spaces", "1")
    }
}

