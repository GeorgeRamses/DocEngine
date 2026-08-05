package nsf.georgeramsis.docengine

import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.util.Matrix
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

// ─────────────────────────────────────────────────────────────────────────────
// Footer placement constants
// ─────────────────────────────────────────────────────────────────────────────

private const val MARGIN_X     = 12f   // left / right text margin (points)
private const val TEXT_Y_RATIO = 0.32f // text baseline = footerHeight * ratio

// ─────────────────────────────────────────────────────────────────────────────
// PdfFooterService
//
// Strategy: expand the page DOWNWARD into negative Y space.
//
//  Before:  MediaBox = [0,  0,  W,  H]   content lives at y = 0 … H
//  After:   MediaBox = [0, -F,  W,  H]   content UNCHANGED at y = 0 … H
//                                         footer drawn   at y = -F … 0
//
// The existing content coordinates are never touched, so nothing can shift
// or get clipped. CropBox and all other page boxes are updated to match.
// ─────────────────────────────────────────────────────────────────────────────

class PdfFooterService {

    private val cancelFlag = AtomicBoolean(false)
    fun requestCancel() { cancelFlag.set(true) }

    // ── ENTRY POINT ───────────────────────────────────────────────────────────

    suspend fun addFooterToFiles(
        inputFiles : List<File>,
        config     : AppConfig,
        onProgress : (Float) -> Unit,
        onLog      : suspend (String) -> Unit,
        onComplete : (suspend (success: Int, skipped: Int) -> Unit)? = null
    ) = coroutineScope {

        var success = 0
        var skipped = 0

        onLog("┌────────────────────────────────────────┐")
        onLog("  Add Footer  ·  ${inputFiles.size} file(s)")
        onLog("  Left   : \"${config.footerLeftText}\"")
        onLog("  Center : \"${config.footerCenterText}\"")
        onLog("  Right  : \"${config.footerRightText}\"")
        onLog("  Height : ${config.footerHeight.toInt()} pt  |  Font: ${config.footerFontSize.toInt()} pt")
        onLog("└────────────────────────────────────────┘")

        inputFiles.forEachIndexed { index, file ->
            if (cancelFlag.get() || !isActive) {
                onLog("  ⚠ Cancelled — ${inputFiles.size - index} file(s) skipped")
                skipped += inputFiles.size - index
                return@forEachIndexed
            }

            val result = runCatching { processFile(file, config) }
            if (result.isSuccess && result.getOrDefault(false)) {
                success++
                onLog("  ✅ [${file.name}] Footer added")
            } else {
                skipped++
                onLog("  ❌ [${file.name}] Failed: ${result.exceptionOrNull()?.message ?: "unknown error"}")
            }

            onProgress((index + 1).toFloat() / inputFiles.size.toFloat())
        }

        onLog("\n╔══════════════════════════════════════╗")
        onLog("║       ✅  FOOTER COMPLETE             ║")
        onLog("║  📂 Total   : ${inputFiles.size.toString().padEnd(14)}║")
        onLog("║  ✅ Success : ${success.toString().padEnd(14)}║")
        onLog("║  ⚠  Skipped : ${skipped.toString().padEnd(14)}║")
        onLog("╚══════════════════════════════════════╝")

        onComplete?.invoke(success, skipped)
    }

    // ── PER-FILE PROCESSING ───────────────────────────────────────────────────

    private fun processFile(file: File, config: AppConfig): Boolean {
        PDDocument.load(file).use { document ->
            val today = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())

            val totalPages = document.numberOfPages

            val specificPagesText = config.footerPages.trim()
            val pagesToProcess = mutableSetOf<Int>()

            if (specificPagesText.isEmpty()) {
                for (i in 1..totalPages) pagesToProcess.add(i)
            } else {
                specificPagesText.split(",").forEach { part ->
                    val p = part.trim()
                    if (p.contains("-")) {
                        val rangeParts = p.split("-")
                        if (rangeParts.size == 2) {
                            val start = rangeParts[0].trim().toIntOrNull()
                            val end = rangeParts[1].trim().toIntOrNull()
                            if (start != null && end != null) {
                                val s = start.coerceIn(1, totalPages)
                                val e = end.coerceIn(1, totalPages)
                                for (i in minOf(s, e)..maxOf(s, e)) pagesToProcess.add(i)
                            }
                        }
                    } else {
                        val page = p.toIntOrNull()
                        if (page != null && page in 1..totalPages) {
                            pagesToProcess.add(page)
                        }
                    }
                }
            }

            for (pageIndex in 0 until totalPages) {
                val pageNum = pageIndex + 1
                
                if (!pagesToProcess.contains(pageNum)) continue

                addFooterToPage(
                    document   = document,
                    page       = document.getPage(pageIndex),
                    pageIndex  = pageIndex,
                    totalPages = totalPages,
                    fileName   = file.nameWithoutExtension,
                    today      = today,
                    config     = config
                )
            }

            // Save into a dedicated output folder, keep original intact.
            val outDir = File(file.parentFile, "processed")
            if (!outDir.exists()) {
                outDir.mkdirs()
            }
            val outFile = File(outDir, file.name)
            if (outFile.exists()) {
                outFile.delete()
            }
            document.save(outFile)
        }
        return true
    }

    // ── PAGE-LEVEL FOOTER INJECTION ───────────────────────────────────────────

    private fun addFooterToPage(
        document   : PDDocument,
        page       : PDPage,
        pageIndex  : Int,
        totalPages : Int,
        fileName   : String,
        today      : String,
        config     : AppConfig
    ) {
        val fh      = config.footerHeight
        val origBox = page.mediaBox
        val rot     = ((page.rotation % 360) + 360) % 360
        val llx     = origBox.lowerLeftX
        val lly     = origBox.lowerLeftY
        val w       = origBox.width
        val h       = origBox.height
        val visualW = if (rot == 90 || rot == 270) h else w

        // Expand page boxes in the visual bottom direction.
        val newMediaBox = when (rot) {
            90  -> PDRectangle(llx, lly, w + fh, h)
            180 -> PDRectangle(llx, lly, w, h + fh)
            270 -> PDRectangle(llx - fh, lly, w + fh, h)
            else -> PDRectangle(llx, lly - fh, w, h + fh)
        }
        page.mediaBox = newMediaBox

        val cb = page.cosObject.getDictionaryObject(COSName.CROP_BOX)
        if (cb != null) {
            val crop = page.cropBox
            val newCrop = when (rot) {
                90  -> PDRectangle(crop.lowerLeftX, crop.lowerLeftY, crop.width + fh, crop.height)
                180 -> PDRectangle(crop.lowerLeftX, crop.lowerLeftY, crop.width, crop.height + fh)
                270 -> PDRectangle(crop.lowerLeftX - fh, crop.lowerLeftY, crop.width + fh, crop.height)
                else -> PDRectangle(crop.lowerLeftX, crop.lowerLeftY - fh, crop.width, crop.height + fh)
            }
            page.cosObject.setItem(COSName.CROP_BOX, newCrop.cosObject)
        } else {
            page.cosObject.setItem(COSName.CROP_BOX, newMediaBox.cosObject)
        }

        // TrimBox / BleedBox / ArtBox would conflict -> remove them.
        listOf("TrimBox", "BleedBox", "ArtBox").forEach { name ->
            page.cosObject.removeItem(COSName.getPDFName(name))
        }

        // Draw footer in visual coordinates (origin at original visual bottom-left).
        val textY = -fh + fh * TEXT_Y_RATIO

        PDPageContentStream(
            document, page,
            PDPageContentStream.AppendMode.APPEND,
            true,
            true
        ).use { cs ->

            val visualToUser = when (rot) {
                90  -> Matrix(0f, 1f, -1f, 0f, llx + w, lly)
                180 -> Matrix(-1f, 0f, 0f, -1f, llx + w, lly + h)
                270 -> Matrix(0f, -1f, 1f, 0f, llx, lly + h)
                else -> Matrix(1f, 0f, 0f, 1f, llx, lly)
            }
            cs.transform(visualToUser)

            val font     = PDType1Font.HELVETICA
            val fontSize = config.footerFontSize

            cs.setStrokingColor(0.35f, 0.35f, 0.35f)
            cs.setLineWidth(0.5f)
            cs.moveTo(MARGIN_X, 0f)
            cs.lineTo(visualW - MARGIN_X, 0f)
            cs.stroke()

            val left   = resolve(config.footerLeftText,   fileName, pageIndex, totalPages, today)
            val center = resolve(config.footerCenterText, fileName, pageIndex, totalPages, today)
            val right  = resolve(config.footerRightText,  fileName, pageIndex, totalPages, today)

            cs.setNonStrokingColor(0f, 0f, 0f)

            if (left.isNotBlank()) {
                cs.beginText()
                cs.setFont(font, fontSize)
                cs.newLineAtOffset(MARGIN_X, textY)
                cs.showText(sanitize(left))
                cs.endText()
            }

            if (center.isNotBlank()) {
                val tw = font.getStringWidth(sanitize(center)) / 1000f * fontSize
                cs.beginText()
                cs.setFont(font, fontSize)
                cs.newLineAtOffset((visualW - tw) / 2f, textY)
                cs.showText(sanitize(center))
                cs.endText()
            }

            if (right.isNotBlank()) {
                val tw = font.getStringWidth(sanitize(right)) / 1000f * fontSize
                cs.beginText()
                cs.setFont(font, fontSize)
                cs.newLineAtOffset(visualW - tw - MARGIN_X, textY)
                cs.showText(sanitize(right))
                cs.endText()
            }
        }
    }
    // ── HELPERS ───────────────────────────────────────────────────────────────

    /**
     * Replace supported tokens:
     *   {filename}  → file name without extension
     *   {page}      → current page number (1-based)
     *   {total}     → total pages in the document
     *   {date}      → today's date  (dd MMM yyyy)
     */
    private fun resolve(
        template   : String,
        fileName   : String,
        pageIndex  : Int,
        totalPages : Int,
        today      : String
    ): String = template
        .replace("{filename}", fileName)
        .replace("{page}",     (pageIndex + 1).toString())
        .replace("{total}",    totalPages.toString())
        .replace("{date}",     today)

    /** Strip characters outside PDType1Font WinAnsi range to avoid encoding errors. */
    private fun sanitize(text: String): String = text.filter { it.code in 32..255 }

}


