package nsf.georgeramsis.docengine

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import net.sourceforge.tess4j.Tesseract
import org.apache.pdfbox.multipdf.PDFMergerUtility
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.system.measureTimeMillis

// ─────────────────────────────────────────────────────────────────────────────
// CONFIGURATION DATA CLASSES & ENUMS
// ─────────────────────────────────────────────────────────────────────────────

enum class ScanMode {
    RENAME_ONLY,
    SPLIT_AND_ORGANIZE,
    KEYWORD_GROUP,
    PAGE_MERGE,
    DELETE_BY_KEYWORD,
    AUTO_ROTATE,
    TEXT_REPLACE,
    ADD_FOOTER,
    HANDWRITING_EXTRACT
}

enum class UnassignedAction { DROP, APPEND_TO_PREVIOUS }

enum class MergeOperation {
    REPLACE_PAGE,
    REPLACE_LAST_PAGE,
    INSERT_BEFORE,
    INSERT_AFTER,
    APPEND_END
}

data class AppConfig(
    val mode: ScanMode = ScanMode.RENAME_ONLY,
    val delimiter: String = "Prefix",
    val pattern: String = "000000",
    val unassignedAction: UnassignedAction = UnassignedAction.APPEND_TO_PREVIOUS,
    val autoRotate: Boolean = true,
    val debugMode: Boolean = false,
    val exportUndetected: Boolean = false,
    val extractAndDelete: Boolean = false,
    val mergeOperation: MergeOperation = MergeOperation.REPLACE_PAGE,
    val targetPageNumber: Int = 1,
    val sourceFolderB: File? = null,
    // ── Auto-Rotate ──────────────────────────────────────────────────────────
    val enableCheckpoint: Boolean = false,
    val diagMode: Boolean = false,
    // ── Text Replace ─────────────────────────────────────────────────────────
    val searchText: String = "",
    val replaceText: String = "",
    val detectionAccuracy: DetectionAccuracy = DetectionAccuracy.BALANCED,
    val maxCoverageLines: Int = 2,
    /** Render DPI for OCR in TEXT_REPLACE mode (200 = fast + accurate for ≥10 pt text). */
    val textReplaceRenderDpi: Float = 200f,
    // ── Add Footer ───────────────────────────────────────────────────────────
    val footerLeftText: String = "",
    val footerCenterText: String = "",
    val footerRightText: String = "",
    val footerHeight: Float = 20f,
    val footerFontSize: Float = 9f,
    val footerPages: String = "",
    // ── Handwriting Extract ───────────────────────────────────────────────────
    val handwritingMinConfidence: Double = 0.25,
    val handwritingSaveReport: Boolean = true,
    val handwritingTextOnly: Boolean = false,
    // ── Keyword Extract ──────────────────────────────────────────────────────
    val keywordExtractSeparately: Boolean = false
)

// ─────────────────────────────────────────────────────────────────────────────
// SERVICE
// ─────────────────────────────────────────────────────────────────────────────

class PdfService {

    private val processingLimiter = Semaphore(Runtime.getRuntime().availableProcessors())

    // Active service references — used for cooperative cancellation
    @Volatile
    private var activeRotationService: PdfRotationService? = null
    @Volatile
    private var activePdfProcessor: PDFProcessor? = null
    @Volatile
    private var activeFooterService: PdfFooterService? = null
    @Volatile
    private var activeHandwritingService: HandwritingExtractService? = null


    /** Cancel any in-progress auto-rotate or text-replace operation. */
    fun cancelAll() {
        activeRotationService?.requestCancel()
        activePdfProcessor?.requestCancel()
        activeFooterService?.requestCancel()
        activeHandwritingService?.requestCancel()
    }

    private val undetectedPageBytes = mutableListOf<ByteArray>()
    private val undetectedSummary = mutableListOf<String>()
    private var undetectedCount = 0

    private val dataFolderPath: String by lazy {
        ocr.TessDataManager.tessDataPath
    }

    // ── MAIN ENTRY POINT ──────────────────────────────────────────────────────

    suspend fun processInput(
        inputFiles: List<File>,
        config: AppConfig,
        onProgress: (Float) -> Unit,
        onLog: suspend (String) -> Unit,
        onComplete: (suspend (success: Int, skipped: Int) -> Unit)? = null
    ) = supervisorScope {

        if (inputFiles.isEmpty()) {
            onLog("❌ No PDF files found."); return@supervisorScope
        }

        when (config.mode) {
            ScanMode.PAGE_MERGE -> {
                processPageMerge(inputFiles, config, onProgress, onLog, onComplete); return@supervisorScope
            }

            ScanMode.DELETE_BY_KEYWORD -> {
                processDeleteByKeyword(inputFiles, config, onProgress, onLog, onComplete); return@supervisorScope
            }

            ScanMode.AUTO_ROTATE -> {
                processAutoRotate(inputFiles, config, onProgress, onLog, onComplete); return@supervisorScope
            }

            ScanMode.TEXT_REPLACE -> {
                processTextReplace(inputFiles, config, onProgress, onLog, onComplete); return@supervisorScope
            }

            ScanMode.ADD_FOOTER -> {
                val svc = PdfFooterService()
                activeFooterService = svc
                svc.addFooterToFiles(inputFiles, config, onProgress, onLog, onComplete)
                activeFooterService = null
                return@supervisorScope
            }

            ScanMode.HANDWRITING_EXTRACT -> {
                processHandwritingExtract(inputFiles, config, onProgress, onLog, onComplete)
                return@supervisorScope
            }

            else -> { /* OCR modes handled below */
            }
        }

        if (config.debugMode)
            inputFiles.firstOrNull()?.parentFile?.let { File(it, "debug_images").mkdirs() }

        synchronized(undetectedPageBytes) {
            undetectedPageBytes.clear(); undetectedSummary.clear(); undetectedCount = 0
        }

        val filesFinished = AtomicInteger(0)
        val totalFiles = inputFiles.size.toFloat()

        val timeTaken = measureTimeMillis {
            val jobs = inputFiles.map { file ->
                async(Dispatchers.IO) {
                    processingLimiter.withPermit {
                        if (!isActive) return@withPermit false
                        try {
                            val result = processSinglePdf(file, config, onLog) { pct ->
                                val overall = (filesFinished.get() + pct) / totalFiles
                                onProgress(overall)
                            }
                            filesFinished.incrementAndGet()
                            onProgress(filesFinished.get() / totalFiles)
                            result
                        } catch (e: Throwable) {
                            onLog("❌ FATAL on ${file.name}: ${e.message}")
                            e.printStackTrace()
                            filesFinished.incrementAndGet()
                            false
                        }
                    }
                }
            }
            jobs.joinAll()

            val successCount = jobs.count { runCatching { it.getCompleted() }.getOrDefault(false) }
            val failCount = inputFiles.size - successCount

            onLog("\n╔══════════════════════════════════════╗")
            onLog("║       ✅  PROCESS COMPLETE            ║")
            onLog("║  📂 Total   : ${inputFiles.size.toString().padEnd(14)}║")
            onLog("║  ✅ Success : ${successCount.toString().padEnd(14)}║")
            onLog("║  ⚠  Skipped : ${failCount.toString().padEnd(14)}║")
            onLog("╚══════════════════════════════════════╝")

            onComplete?.invoke(successCount, failCount)
        }
        onLog(">>> Total time: ${timeTaken / 1000}s")

        if (config.exportUndetected && undetectedCount > 0)
            exportUndetectedPages(inputFiles.firstOrNull()?.parentFile, onLog)
    }

    // ── HANDWRITING EXTRACT ───────────────────────────────────────────────────

    private suspend fun processHandwritingExtract(
        inputFiles: List<File>,
        config: AppConfig,
        onProgress: (Float) -> Unit,
        onLog: suspend (String) -> Unit,
        onComplete: (suspend (Int, Int) -> Unit)?
    ) = coroutineScope {

        val confPct = (config.handwritingMinConfidence * 100).toInt()
        onLog("✍  Handwriting Extract · ${inputFiles.size} file(s)")
        onLog("   Min confidence : $confPct%")
        onLog("   Save report    : ${config.handwritingSaveReport}")
        onLog("   Text only      : ${config.handwritingTextOnly}")
        onLog("")

        val service = HandwritingExtractService()
        activeHandwritingService = service

        var withHandwriting = 0
        var printOnly       = 0

        val timeTaken = measureTimeMillis {
            withContext(Dispatchers.IO) {
                for ((index, file) in inputFiles.withIndex()) {
                    if (!isActive) break

                    onProgress(index.toFloat() / inputFiles.size)
                    onLog("  📄 [${index + 1}/${inputFiles.size}] ${file.name}")

                    val result = service.extractFromFile(
                        inputFile     = file,
                        minConfidence = config.handwritingMinConfidence,
                        saveReport    = config.handwritingSaveReport,
                        textOnly      = config.handwritingTextOnly,
                        onProgress    = { /* page-level detail suppressed */ }
                    )

                    if (result.success) {
                        val hwPages = result.pages.count { it.hasHandwriting }
                        if (hwPages > 0) {
                            onLog("  ✅ [${file.name}] ${result.message}")
                            result.pages
                                .filter { it.hasHandwriting }
                                .forEach { p ->
                                    val preview = p.extractedText
                                        .replace("\n", " ")
                                        .take(90)
                                        .trim()
                                    val pctStr = "${"%.0f".format(p.confidence * 100)}%"
                                    onLog("     Page ${p.pageNumber} (conf $pctStr, ~${p.wordCount} words): $preview…")
                                }
                            result.reportFile?.let { onLog("     📝 Report → ${it.name}") }
                            result.textOnlyFile?.let { onLog("     📄 Text   → ${it.name}") }
                            withHandwriting++
                        } else {
                            onLog("  🔸 [${file.name}] No handwriting detected.")
                            printOnly++
                        }
                    } else {
                        onLog("  ❌ [${file.name}] ${result.message}")
                        printOnly++
                    }

                    onProgress((index + 1).toFloat() / inputFiles.size)
                }
            }
        }

        onLog("\n╔══════════════════════════════════════╗")
        onLog("║  ✅  HANDWRITING EXTRACT COMPLETE     ║")
        onLog("║  📂 Total      : ${inputFiles.size.toString().padEnd(14)}║")
        onLog("║  ✍  Has HW    : ${withHandwriting.toString().padEnd(14)}║")
        onLog("║  🔸 Print-only : ${printOnly.toString().padEnd(14)}║")
        onLog("╚══════════════════════════════════════╝")
        onLog(">>> Total time: ${timeTaken / 1000}s")

        activeHandwritingService = null
        onComplete?.invoke(withHandwriting, printOnly)
    }

    // ── AUTO ROTATE ───────────────────────────────────────────────────────────

    private suspend fun processAutoRotate(
        inputFiles: List<File>,
        config: AppConfig,
        onProgress: (Float) -> Unit,
        onLog: suspend (String) -> Unit,
        onComplete: (suspend (Int, Int) -> Unit)?
    ) = coroutineScope {

        onLog("🔄 Auto-Rotate · ${inputFiles.size} file(s)")
        onLog("   Checkpoint : ${config.enableCheckpoint}")
        onLog("   Diag logs  : ${config.diagMode}")
        onLog("")

        // Channel bridges non-suspending callbacks → suspending onLog
        val logChannel = Channel<String>(Channel.UNLIMITED)
        val logJob = launch { for (msg in logChannel) onLog(msg) }

        val checkpoint = if (config.enableCheckpoint)
            inputFiles.firstOrNull()?.parentFile?.let { BatchCheckpoint(BatchCheckpoint.defaultPath(it)) }
        else null

        val service = PdfRotationService().apply { diagEnabled = config.diagMode }
        activeRotationService = service

        val timeTaken = measureTimeMillis {
            val summary = withContext(Dispatchers.IO) {
                service.rotateFilesInPlace(
                    files = inputFiles,
                    checkpoint = checkpoint,
                    onProgress = { current, total, _ ->
                        onProgress(current.toFloat() / total.coerceAtLeast(1))
                    },
                    onFileComplete = { result ->
                        val icon = when {
                            result.skipped -> "⏭"
                            !result.success -> "❌"
                            result.pagesRotated > 0 -> "✅"
                            else -> "ℹ️"
                        }
                        logChannel.trySend("  $icon [${result.inputFile.name}] ${result.message}")
                    }
                )
            }

            logChannel.close()
            logJob.join()

            onLog("\n╔══════════════════════════════════════╗")
            onLog("║     ✅  AUTO-ROTATE COMPLETE          ║")
            onLog("║  📂 Total    : ${summary.totalFiles.toString().padEnd(14)}║")
            onLog("║  ✅ Rotated  : ${summary.succeeded.toString().padEnd(14)}║")
            onLog("║  ⏭  Skipped  : ${summary.skipped.toString().padEnd(14)}║")
            onLog("║  ❌ Failed   : ${summary.failed.toString().padEnd(14)}║")
            onLog("╚══════════════════════════════════════╝")

            onComplete?.invoke(summary.succeeded, summary.failed + summary.skipped)
        }
        onLog(">>> Total time: ${timeTaken / 1000}s")
        activeRotationService = null
    }

    // ── TEXT REPLACE ──────────────────────────────────────────────────────────

    private suspend fun processTextReplace(
        inputFiles: List<File>,
        config: AppConfig,
        onProgress: (Float) -> Unit,
        onLog: suspend (String) -> Unit,
        onComplete: (suspend (Int, Int) -> Unit)?
    ) = supervisorScope {

        // ── Resolve root directory ────────────────────────────────────────────
        val rootDir = inputFiles.firstOrNull()?.parentFile
            ?: run { onLog("❌ Cannot resolve root directory"); return@supervisorScope }

        // ── Create managed subdirectories ─────────────────────────────────────
        val originalsDir = File(rootDir, "_originals").also { it.mkdirs() }
        val unmatchedDir = File(rootDir, "_unmatched").also { it.mkdirs() }

        // ── Build full file list ──────────────────────────────────────────────
        data class SourcedFile(val file: File, val fromUnmatched: Boolean)

        val rootFiles = inputFiles
            .filter { it.isFile && it.extension.equals("pdf", ignoreCase = true) }
            .filter { it.parentFile?.canonicalPath != originalsDir.canonicalPath }
            .filter { it.parentFile?.canonicalPath != unmatchedDir.canonicalPath }
            .map { SourcedFile(it, false) }

        val unmatchedFiles = unmatchedDir
            .listFiles { f -> f.isFile && f.extension.equals("pdf", ignoreCase = true) }
            .orEmpty()
            .map { SourcedFile(it, true) }

        val allFiles = rootFiles + unmatchedFiles
        val totalSize = allFiles.size

        if (allFiles.isEmpty()) {
            onLog("❌ No PDF files found to process."); return@supervisorScope
        }

        onLog("🔍 Find & Replace · $totalSize file(s)  (${rootFiles.size} root + ${unmatchedFiles.size} from _unmatched/)")
        onLog("   Search   : \"${config.searchText}\"")
        onLog("   Replace  : \"${config.replaceText}\"")
        onLog("   Accuracy : ${config.detectionAccuracy.name}")
        onLog("   Max lines: ${config.maxCoverageLines}")
        onLog("   Render DPI: ${config.textReplaceRenderDpi.toInt()}")
        onLog("   Root     : ${rootDir.absolutePath}")
        onLog("")

        val processor = PDFProcessor()
        activePdfProcessor = processor

        var successCount = 0
        var failCount = 0

        val timeTaken = measureTimeMillis {

            // ── Phase 1: parallel OCR scanning ───────────────────────────────────
            // Each file's OCR runs concurrently on Dispatchers.IO. PDFProcessor
            // creates a fresh Tesseract instance per document — no thread contention.
            // Tmp files are keyed on the source filename, so there are no write conflicts.
            data class ScanEntry(val sourced: SourcedFile, val result: ProcessingResult, val origIndex: Int)

            val scansCompleted = AtomicInteger(0)

            val scanEntries: List<ScanEntry> = withContext(Dispatchers.IO) {
                allFiles.mapIndexed { index, sourced ->
                    async {
                        processingLimiter.withPermit {
                            val result = runCatching {
                                onLog("  🔄 Scanning ${sourced.file.name}…")
                                processor.processPDF(
                                    inputFile         = sourced.file,
                                    outputDir         = rootDir,
                                    searchText        = config.searchText,
                                    replaceText       = config.replaceText,
                                    maxLines          = config.maxCoverageLines,
                                    detectionAccuracy = config.detectionAccuracy,
                                    renderDpi         = config.textReplaceRenderDpi,
                                    autoRotate        = config.autoRotate,
                                    onProgress        = { msg -> launch { onLog("    [${sourced.file.name}] $msg") } }
                                )
                            }.getOrElse { e ->
                                ProcessingResult(false, "Error: ${e.message}", inputFile = sourced.file)
                            }
                            // Phase 1 progress: 0% → 80%
                            val done = scansCompleted.incrementAndGet()
                            onProgress(done.toFloat() / totalSize * 0.8f)
                            ScanEntry(sourced, result, index)
                        }
                    }
                }.awaitAll()
            }.sortedBy { it.origIndex }

            // ── Phase 2: sequential file operations ──────────────────────────────
            // File-system ops (backup, rename, move) run in the original file order
            // to avoid TOCTOU races in resolveCollision.
            for ((sourced, result, index) in scanEntries) {
                onLog("  📄 [${index + 1}/$totalSize] ${sourced.file.name}${if (sourced.fromUnmatched) " (re-scan)" else ""}…")

                if (result.success && result.outputFile != null) {
                    val tmpFile = result.outputFile

                    // 1. Back up the original into _originals/ (collision-safe)
                    val backup = resolveCollision(originalsDir, sourced.file.name)
                    try {
                        sourced.file.copyTo(backup)
                    } catch (e: Exception) {
                        onLog("  ⚠ [${sourced.file.name}] Could not back up original: ${e.message}")
                        tmpFile.delete()
                        failCount++
                        onProgress(0.8f + (index + 1).toFloat() / totalSize * 0.2f)
                        continue
                    }

                    // 2. Remove the original from its current location
                    //    (root or _unmatched — either way it's been backed up)
                    sourced.file.delete()

                    // 3. Promote the tmp output to root with the original filename
                    val finalOut = resolveCollision(rootDir, sourced.file.name)
                    tmpFile.renameTo(finalOut)

                    onLog("  ✅ [${sourced.file.name}] ${result.message}")
                    if (sourced.fromUnmatched)
                        onLog("     ↳ Promoted from _unmatched/ → root")
                    successCount++

                } else {
                    // No match (or cancelled/error)
                    result.outputFile?.delete()   // clean up any stray tmp

                    when {
                        result.message.startsWith("Cancelled") -> {
                            onLog("  ⏹ [${sourced.file.name}] Cancelled")
                            failCount++
                        }

                        sourced.fromUnmatched -> {
                            // Already in _unmatched/ — leave it untouched
                            onLog("  🔸 [${sourced.file.name}] Still no match — remains in _unmatched/")
                            failCount++
                        }

                        else -> {
                            // Move from root → _unmatched/
                            val dest = resolveCollision(unmatchedDir, sourced.file.name)
                            val moved = sourced.file.renameTo(dest)
                            if (moved) {
                                onLog("  🔸 [${sourced.file.name}] No match — moved to _unmatched/")
                            } else {
                                onLog("  ⚠ [${sourced.file.name}] No match — could not move to _unmatched/ (file in use?)")
                            }
                            failCount++
                        }
                    }
                }

                // Phase 2 progress: 80% → 100%
                onProgress(0.8f + (index + 1).toFloat() / totalSize * 0.2f)
            }

            onLog("\n╔══════════════════════════════════════╗")
            onLog("║   ✅  FIND & REPLACE COMPLETE         ║")
            onLog("║  📂 Total    : ${totalSize.toString().padEnd(14)}║")
            onLog("║  ✅ Replaced : ${successCount.toString().padEnd(14)}║")
            onLog("║  🔸 Unmatched: ${failCount.toString().padEnd(14)}║")
            onLog("╚══════════════════════════════════════╝")
            onLog("   _originals/ : ${originalsDir.listFiles()?.size ?: 0} file(s)")
            onLog("   _unmatched/ : ${unmatchedDir.listFiles()?.size ?: 0} file(s)")

            onComplete?.invoke(successCount, failCount)
        }
        onLog(">>> Total time: ${timeTaken / 1000}s")
        activePdfProcessor = null
    }


    // ── DELETE BY KEYWORD ─────────────────────────────────────────────────────

    private suspend fun processDeleteByKeyword(
        inputFiles: List<File>,
        config: AppConfig,
        onProgress: (Float) -> Unit,
        onLog: suspend (String) -> Unit,
        onComplete: (suspend (Int, Int) -> Unit)?
    ) = supervisorScope {

        onLog("🔑 Keyword to delete: ${config.delimiter}")
        onLog("📂 Processing ${inputFiles.size} file(s)")
        onLog("")

        val filesFinished = AtomicInteger(0)
        val totalFiles = inputFiles.size.toFloat()

        val timeTaken = measureTimeMillis {
            val jobs = inputFiles.map { file ->
                async(Dispatchers.IO) {
                    processingLimiter.withPermit {
                        if (!isActive) return@withPermit false
                        try {
                            val result = deletePagesWithKeyword(file, config, onLog) { pct ->
                                val overall = (filesFinished.get() + pct) / totalFiles
                                onProgress(overall)
                            }
                            filesFinished.incrementAndGet()
                            onProgress(filesFinished.get() / totalFiles)
                            result
                        } catch (e: Throwable) {
                            onLog("❌ FATAL on ${file.name}: ${e.message}")
                            filesFinished.incrementAndGet()
                            false
                        }
                    }
                }
            }
            jobs.joinAll()

            val successCount = jobs.count { runCatching { it.getCompleted() }.getOrDefault(false) }
            val skipCount = inputFiles.size - successCount

            onLog("╔══════════════════════════════════════╗")
            onLog("║   ✅  DELETE BY KEYWORD COMPLETE      ║")
            onLog("║  📂 Total    : ${inputFiles.size.toString().padEnd(14)}║")
            onLog("║  ✅ Modified : ${successCount.toString().padEnd(14)}║")
            onLog("║  !! Skipped  : ${skipCount.toString().padEnd(14)}║")
            onLog("╚══════════════════════════════════════╝")
            onComplete?.invoke(successCount, skipCount)
        }
        onLog(">>> Total time: ${timeTaken / 1000}s")
    }

    private suspend fun deletePagesWithKeyword(
        file: File,
        config: AppConfig,
        onLog: suspend (String) -> Unit,
        onFileProgress: (Float) -> Unit
    ): Boolean {

        val document = PDDocument.load(file)
        val totalPages = document.numberOfPages
        val renderer = PDFRenderer(document)

        val mainTesseract = Tesseract().apply {
            setDatapath(dataFolderPath); setLanguage("eng"); setPageSegMode(6)
            setVariable("load_system_dawg", "F"); setVariable("load_freq_dawg", "F")
        }
        val osdTesseract = Tesseract().apply {
            setDatapath(dataFolderPath)
            setLanguage("osd")
            setPageSegMode(0)
            setOcrEngineMode(net.sourceforge.tess4j.ITessAPI.TessOcrEngineMode.OEM_DEFAULT)
        }

        val matchedPageIndices = mutableListOf<Int>()
        val keepPageIndices = mutableListOf<Int>()

        try {
            for (pageIdx in 0 until totalPages) {
                currentCoroutineContext().ensureActive()
                onFileProgress(pageIdx.toFloat() / totalPages)

                // ── Fast-path: try embedded text first ──
                val directText = tryExtractTextDirect(document, pageIdx,
                    config.copy(mode = ScanMode.KEYWORD_GROUP))
                var matched = directText != null

                // ── OCR fallback ──
                if (!matched) {
                    val raw200 = renderer.renderImageWithDPI(pageIdx, 200f)
                    val prep200 = fixImageForOcr(raw200, 200f)
                    val text = runCatching { mainTesseract.doOCR(prep200) }.getOrNull() ?: ""

                    if (text.contains(config.delimiter, ignoreCase = true)) {
                        matched = true
                    } else if (config.autoRotate) {
                        // Straight-pass missed; try rotation fallback.
                        // Stage 1: OSD hint (may return 0° on many scanned forms).
                        val osdAngle = runCatching {
                            val osdOut = osdTesseract.doOCR(raw200)
                            val rv = Regex("(?im)^\\s*Rotate\\s*:\\s*(\\d+)").find(osdOut)?.groupValues?.get(1)?.toIntOrNull()
                            val ov = if (rv == null)
                                Regex("(?im)^\\s*Orientation in degrees\\s*:\\s*(\\d+)").find(osdOut)?.groupValues?.get(1)?.toIntOrNull()?.let { ((360 - it) % 360) }
                            else null
                            rv ?: ov ?: 0
                        }.onFailure { e ->
                            println("  ⚠ [OSD] Failed for ${file.name} P$pageIdx: ${e.message}")
                        }.getOrDefault(0)

                        // Stage 2: Brute-force — OSD angle first, then remaining rotations.
                        val anglesToTry = buildList {
                            if (osdAngle != 0) add(osdAngle)
                            for (a in listOf(90, 180, 270)) { if (a != osdAngle) add(a) }
                        }
                        for (angle in anglesToTry) {
                            val rotated = OpenCvOps.rotate90(prep200, angle)
                            val rotatedText = runCatching { mainTesseract.doOCR(rotated) }.getOrNull() ?: ""
                            if (rotatedText.contains(config.delimiter, ignoreCase = true)) {
                                matched = true; break
                            }
                        }
                    }
                }

                if (matched) {
                    matchedPageIndices.add(pageIdx)
                    onLog("  🗑 [${file.name}] P${pageIdx + 1}: contains ${config.delimiter} → will delete")
                } else {
                    keepPageIndices.add(pageIdx)
                }
            }
        } finally {
            document.close()
        }

        onFileProgress(0.9f)

        if (matchedPageIndices.isEmpty()) {
            onLog("  ℹ [${file.name}] No pages contained ${config.delimiter} — unchanged")
            return false
        }

        if (keepPageIndices.isEmpty()) {
            deleteOriginalFile(file, onLog)
            onLog("  [DEL] [${file.name}] All ${matchedPageIndices.size} pages matched -- file deleted")
            onFileProgress(1f)
            return true
        }

        val tmp = File(file.parentFile, "${file.nameWithoutExtension}_cleaned.tmp.pdf")
        PDDocument.load(file).use { src ->
            PDDocument().use { out ->
                keepPageIndices.forEach { out.addPage(src.getPage(it)) }
                out.save(tmp)
            }
        }

        if (!file.delete()) {
            onLog("  ⚠ [${file.name}] Could not replace original (file in use?)")
            tmp.delete()
            return false
        }
        tmp.renameTo(file)

        onLog("  ✅ [${file.name}] Deleted ${matchedPageIndices.size} page(s), kept ${keepPageIndices.size}")
        onFileProgress(1f)
        return true
    }

    // ── PAGE MERGE ────────────────────────────────────────────────────────────

    private suspend fun processPageMerge(
        targetFiles: List<File>,
        config: AppConfig,
        onProgress: (Float) -> Unit,
        onLog: suspend (String) -> Unit,
        onComplete: (suspend (Int, Int) -> Unit)?
    ) = supervisorScope {

        val sourceFolderB = config.sourceFolderB
        if (sourceFolderB == null || !sourceFolderB.exists() || !sourceFolderB.isDirectory) {
            onLog("❌ Source Folder B is not valid or does not exist"); return@supervisorScope
        }

        val sourcePdfsMap = sourceFolderB.listFiles()
            ?.filter { it.extension.equals("pdf", ignoreCase = true) }
            ?.associateBy { it.nameWithoutExtension }
            ?: emptyMap()

        if (sourcePdfsMap.isEmpty()) {
            onLog("❌ No PDF files found in Source Folder B"); return@supervisorScope
        }

        onLog("📂 ${sourcePdfsMap.size} source PDF(s) found in Folder B")
        onLog("🔄 Processing ${targetFiles.size} target PDF(s) from Folder A")
        onLog("   Operation : ${config.mergeOperation}")
        if (config.mergeOperation != MergeOperation.APPEND_END && config.mergeOperation != MergeOperation.REPLACE_LAST_PAGE)
            onLog("   Target page: ${config.targetPageNumber}")
        onLog("")

        val filesFinished = AtomicInteger(0)
        val totalFiles = targetFiles.size.toFloat()

        val timeTaken = measureTimeMillis {
            val jobs = targetFiles.map { targetFile ->
                async(Dispatchers.IO) {
                    processingLimiter.withPermit {
                        if (!isActive) return@withPermit false
                        try {
                            val result = processSingleMerge(targetFile, sourcePdfsMap, config, onLog)
                            filesFinished.incrementAndGet()
                            onProgress(filesFinished.get() / totalFiles)
                            result
                        } catch (e: Throwable) {
                            onLog("❌ ERROR on ${targetFile.name}: ${e.message}")
                            filesFinished.incrementAndGet()
                            false
                        }
                    }
                }
            }
            jobs.joinAll()

            val successCount = jobs.count { runCatching { it.getCompleted() }.getOrDefault(false) }
            val skipCount = targetFiles.size - successCount

            onLog("\n╔══════════════════════════════════════╗")
            onLog("║     ✅  PAGE MERGE COMPLETE           ║")
            onLog("║  📂 Total   : ${targetFiles.size.toString().padEnd(14)}║")
            onLog("║  ✅ Merged  : ${successCount.toString().padEnd(14)}║")
            onLog("║  ⚠  Skipped : ${skipCount.toString().padEnd(14)}║")
            onLog("╚══════════════════════════════════════╝")

            onComplete?.invoke(successCount, skipCount)
        }
        onLog(">>> Total time: ${timeTaken / 1000}s")
    }

    private suspend fun processSingleMerge(
        targetFile: File,
        sourcePdfsMap: Map<String, File>,
        config: AppConfig,
        onLog: suspend (String) -> Unit
    ): Boolean {

        val sourceFile = sourcePdfsMap[targetFile.nameWithoutExtension]
        if (sourceFile == null) {
            onLog("  ⏭ [${targetFile.name}] No matching file in Folder B — skipped")
            return false
        }

        PDDocument.load(targetFile).use { targetDoc ->
            PDDocument.load(sourceFile).use { sourceDoc ->

                val targetPageCount = targetDoc.numberOfPages
                val sourcePageCount = sourceDoc.numberOfPages
                val targetPageIdx = config.targetPageNumber - 1

                when (config.mergeOperation) {
                    MergeOperation.REPLACE_PAGE -> {
                        if (targetPageIdx < 0 || targetPageIdx >= targetPageCount) {
                            onLog("  ❌ [${targetFile.name}] Page ${config.targetPageNumber} out of range (has $targetPageCount pages)")
                            return false
                        }
                        targetDoc.removePage(targetPageIdx)
                        for (i in 0 until sourcePageCount) {
                            val sp = sourceDoc.getPage(i)
                            val insIdx = targetPageIdx + i
                            if (insIdx < targetDoc.numberOfPages) targetDoc.pages.insertBefore(
                                sp,
                                targetDoc.getPage(insIdx)
                            )
                            else targetDoc.addPage(sp)
                        }
                        onLog("  ✅ [${targetFile.name}] Replaced page ${config.targetPageNumber} with $sourcePageCount page(s) from [${sourceFile.name}]")
                    }

                    MergeOperation.REPLACE_LAST_PAGE -> {
                        if (targetPageCount < 1) {
                            onLog("  ❌ [${targetFile.name}] Document has no pages"); return false
                        }
                        targetDoc.removePage(targetPageCount - 1)
                        for (i in 0 until sourcePageCount) targetDoc.addPage(sourceDoc.getPage(i))
                        onLog("  ✅ [${targetFile.name}] Replaced last page (was #$targetPageCount) with $sourcePageCount page(s) from [${sourceFile.name}]")
                    }

                    MergeOperation.INSERT_BEFORE -> {
                        if (targetPageIdx < 0 || targetPageIdx >= targetPageCount) {
                            onLog("  ❌ [${targetFile.name}] Page ${config.targetPageNumber} out of range (has $targetPageCount pages)")
                            return false
                        }
                        for (i in 0 until sourcePageCount) {
                            val sp = sourceDoc.getPage(i)
                            val insIdx = targetPageIdx + i
                            if (insIdx < targetDoc.numberOfPages) targetDoc.pages.insertBefore(
                                sp,
                                targetDoc.getPage(insIdx)
                            )
                            else targetDoc.addPage(sp)
                        }
                        onLog("  ✅ [${targetFile.name}] Inserted $sourcePageCount page(s) before page ${config.targetPageNumber}")
                    }

                    MergeOperation.INSERT_AFTER -> {
                        if (targetPageIdx < 0 || targetPageIdx >= targetPageCount) {
                            onLog("  ❌ [${targetFile.name}] Page ${config.targetPageNumber} out of range (has $targetPageCount pages)")
                            return false
                        }
                        for (i in 0 until sourcePageCount) {
                            val sp = sourceDoc.getPage(i)
                            val insIdx = targetPageIdx + 1 + i
                            if (insIdx < targetDoc.numberOfPages) targetDoc.pages.insertBefore(
                                sp,
                                targetDoc.getPage(insIdx)
                            )
                            else targetDoc.addPage(sp)
                        }
                        onLog("  ✅ [${targetFile.name}] Inserted $sourcePageCount page(s) after page ${config.targetPageNumber}")
                    }

                    MergeOperation.APPEND_END -> {
                        for (i in 0 until sourcePageCount) targetDoc.addPage(sourceDoc.getPage(i))
                        onLog("  ✅ [${targetFile.name}] Appended $sourcePageCount page(s) to end")
                    }
                }

                targetDoc.save(File(targetFile.parentFile, "${targetFile.nameWithoutExtension}.pdf"))
            }
        }
        return true
    }

    // ── EXPORT UNDETECTED PAGES ───────────────────────────────────────────────

    private fun captureUndetectedPage(doc: PDDocument, pageIndex: Int, fileName: String, pageNumber: Int) {
        try {
            val baos = ByteArrayOutputStream()
            PDDocument().use { tmp -> tmp.importPage(doc.getPage(pageIndex)); tmp.save(baos) }
            synchronized(undetectedPageBytes) {
                undetectedPageBytes.add(baos.toByteArray())
                undetectedSummary.add("  $fileName  →  page $pageNumber")
                undetectedCount++
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun exportUndetectedPages(parentDir: File?, onLog: suspend (String) -> Unit) {
        if (undetectedCount == 0) {
            onLog("📄 No undetected pages to export."); return
        }

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = parentDir ?: File(System.getProperty("user.home"))
        val outputFile = File(dir, "undetected_pages_$ts.pdf")

        try {
            PDDocument().use { outputDoc ->
                val merger = PDFMergerUtility()
                undetectedPageBytes.forEach { bytes ->
                    PDDocument.load(bytes).use { pageDoc -> merger.appendDocument(outputDoc, pageDoc) }
                }
                outputDoc.save(outputFile)
            }
            onLog("📄 Exported $undetectedCount undetected page(s) to: ${outputFile.absolutePath}")

            File(dir, "undetected_pages_${ts}_summary.txt").writeText(buildString {
                appendLine("=".repeat(60))
                appendLine("UNDETECTED PAGES SUMMARY")
                appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine("=".repeat(60))
                appendLine()
                appendLine("Total undetected pages: $undetectedCount")
                appendLine()
                undetectedSummary.forEach { appendLine(it) }
            })
        } catch (e: Exception) {
            onLog("❌ Failed to export undetected pages: ${e.message}")
        }
    }

    // ── SINGLE PDF (OCR MODES) ────────────────────────────────────────────────

    private suspend fun processSinglePdf(
        file: File,
        config: AppConfig,
        onLog: suspend (String) -> Unit,
        onFileProgress: (Float) -> Unit
    ): Boolean {
        var shouldDeleteOriginal = false
        var isSuccess = false
        var remainingTempFile: File? = null

        val document = PDDocument.load(file)
        val totalPages = document.numberOfPages
        val renderer = PDFRenderer(document)
        val woMap = LinkedHashMap<String, MutableList<Int>>()
        var keyGroupCounter = 0

        val mainTesseract = Tesseract().apply {
            setDatapath(dataFolderPath); setLanguage("eng")
            // PSM 11 (sparse text) outperforms PSM 6 (uniform block) on scanned
            // work-order forms: it finds labels and numbers scattered across tables,
            // stamps, and multi-column layouts without assuming a single text block.
            // KEYWORD_GROUP uses PSM 3 (auto) to scan the full page freely.
            setPageSegMode(if (config.mode == ScanMode.KEYWORD_GROUP) 3 else 11)
            setVariable("load_system_dawg", "F"); setVariable("load_freq_dawg", "F")
            setVariable("classify_bln_numeric_mode", "1")
            // LSTM-only engine is more robust on degraded scans than the legacy engine
            setOcrEngineMode(net.sourceforge.tess4j.ITessAPI.TessOcrEngineMode.OEM_LSTM_ONLY)
            setVariable("user_defined_dpi", "300")
        }
        // OSD Tesseract is always created so rotation recovery works in every mode
        // (RENAME, SPLIT, KEYWORD, AUTO_ROTATE). The cost is one OSD call per page
        // only when the straight-pass scan misses — not on every page.
        val osdTesseract = Tesseract().apply {
            setDatapath(dataFolderPath)
            setLanguage("osd")
            setPageSegMode(0)
            setOcrEngineMode(net.sourceforge.tess4j.ITessAPI.TessOcrEngineMode.OEM_DEFAULT)
        }

        val droppedPageIndices = mutableSetOf<Int>()

        try {
            if (config.mode == ScanMode.RENAME_ONLY) {
                currentCoroutineContext().ensureActive()
                onFileProgress(0.1f)
                // Fast-path: try embedded text first (no render / no OCR)
                var found = tryExtractTextDirect(document, 0, config)
                if (found == null) {
                    // Two-pass OCR: run both DPIs and vote to defeat 5↔6 / 0↔6 confusion
                    val fast = tryScanPage(renderer, 0, 200f, config, mainTesseract, osdTesseract, file.name, "P1-FAST")
                    val deep = tryScanPage(renderer, 0, 300f, config, mainTesseract, osdTesseract, file.name, "P1-DEEP")
                    found = when {
                        fast == null && deep == null -> null
                        fast == null -> deep
                        deep == null -> fast
                        fast == deep -> fast   // both agree — no vote needed
                        else -> {
                            // disagree — run tiebreaker at 400 DPI on a tight top-crop
                            val tiebreakRaw = renderer.renderImageWithDPI(0, 400f)
                            val tiebreakH   = (tiebreakRaw.height * 0.30).toInt().coerceAtLeast(1)
                            val tiebreakImg = fixImageForOcr(
                                tiebreakRaw.getSubimage(0, 0, tiebreakRaw.width, tiebreakH), 400f
                            )
                            voteWoNumber(fast, deep, tiebreakImg, mainTesseract, config)
                        }
                    }
                }
                onFileProgress(0.9f)
                if (found != null) {
                    if (file.nameWithoutExtension == found) {
                        onLog("  ✅ [${file.name}] Already correct"); isSuccess = true
                    } else {
                        onLog("  ✅ [${file.name}] → $found.pdf")
                        saveRenameOnly(file, document, found)
                        shouldDeleteOriginal = true; isSuccess = true
                    }
                } else {
                    onLog("  ⚠ [${file.name}] No match on page 1 — skipped")
                    if (config.exportUndetected) captureUndetectedPage(document, 0, file.name, 1)
                }
            } else {
                var lastGroup = "Unassigned"
                for (pageIdx in 0 until totalPages) {
                    currentCoroutineContext().ensureActive()
                    onFileProgress(pageIdx.toFloat() / totalPages)

                    // ── Fast-path: try embedded text (no render / no OCR) ──
                    var found = tryExtractTextDirect(document, pageIdx, config)

                    // ── Two-pass OCR with vote to defeat 5↔6 / 0↔6 confusion ──
                    if (found == null) {
                        val fast = tryScanPage(renderer, pageIdx, 200f, config, mainTesseract, osdTesseract, file.name, "FAST")
                        val deep = tryScanPage(renderer, pageIdx, 300f, config, mainTesseract, osdTesseract, file.name, "DEEP")
                        found = when {
                            fast == null && deep == null -> null
                            fast == null -> deep
                            deep == null -> fast
                            fast == deep -> fast
                            else -> {
                                val tiebreakRaw = renderer.renderImageWithDPI(pageIdx, 400f)
                                val tiebreakH   = (tiebreakRaw.height * 0.30).toInt().coerceAtLeast(1)
                                val tiebreakImg = fixImageForOcr(
                                    tiebreakRaw.getSubimage(0, 0, tiebreakRaw.width, tiebreakH), 400f
                                )
                                voteWoNumber(fast, deep, tiebreakImg, mainTesseract, config)
                            }
                        }
                    }

                    if (found != null) {
                        when (config.mode) {
                            ScanMode.KEYWORD_GROUP -> {
                                if (config.unassignedAction == UnassignedAction.DROP) {
                                    lastGroup = found
                                    woMap.getOrPut(found) { mutableListOf() }.add(pageIdx)
                                    onLog("  🔹 [${file.name}] P${pageIdx + 1}: Matched '$found'")
                                } else {
                                    val key = "${found}_${(++keyGroupCounter).toString().padStart(3, '0')}"
                                    lastGroup = key
                                    woMap.getOrPut(key) { mutableListOf() }.add(pageIdx)
                                    onLog("  🔹 [${file.name}] P${pageIdx + 1}: New section '$key'")
                                }
                            }

                            else -> {
                                lastGroup = found
                                woMap.getOrPut(found) { mutableListOf() }.add(pageIdx)
                                onLog("  🔹 [${file.name}] P${pageIdx + 1}: $found")
                            }
                        }
                    } else {
                        if (config.exportUndetected) captureUndetectedPage(document, pageIdx, file.name, pageIdx + 1)
                        if (config.unassignedAction == UnassignedAction.APPEND_TO_PREVIOUS && lastGroup != "Unassigned") {
                            woMap.getOrPut(lastGroup) { mutableListOf() }.add(pageIdx)
                            onLog("  🔸 [${file.name}] P${pageIdx + 1}: Undetected → appended to $lastGroup")
                        } else {
                            droppedPageIndices.add(pageIdx)
                            onLog("  🔸 [${file.name}] P${pageIdx + 1}: Undetected → dropped")
                        }
                    }
                }

                if (woMap.isNotEmpty()) {
                    saveSplitOutput(file, document, woMap, config)
                    if (config.mode == ScanMode.KEYWORD_GROUP && config.extractAndDelete) {
                        remainingTempFile = buildRemainingTemp(file, document, woMap)
                        if (remainingTempFile == null) shouldDeleteOriginal = true
                    }
                    isSuccess = true
                } else {
                    onLog("  ⚠ [${file.name}] No data found — skipped")
                }
            }
        } finally {
            document.close()
        }

        if (shouldDeleteOriginal) deleteOriginalFile(file, onLog)
        remainingTempFile?.let { applyRemainingTemp(file, it, woMap, onLog) }

        onFileProgress(1f)
        return isSuccess
    }

    // ── OCR HELPERS ───────────────────────────────────────────────────────────

    /**
     * Fast-path: extract embedded text from a PDF page using PDFBox's text
     * stripper.  This is nearly instant (~1-5 ms) compared to OCR (~500-2000 ms)
     * and returns exact text.  Returns null for image-only / scanned pages.
     */
    private fun tryExtractTextDirect(doc: PDDocument, pageIndex: Int, config: AppConfig): String? {
        return try {
            val stripper = PDFTextStripper().apply {
                startPage = pageIndex + 1   // PDFTextStripper uses 1-based pages
                endPage   = pageIndex + 1
            }
            val text = stripper.getText(doc)
            if (text.isBlank()) return null
            if (config.mode == ScanMode.KEYWORD_GROUP) {
                if (text.contains(config.delimiter, ignoreCase = true)) config.delimiter else null
            } else {
                extractRegex(text, config)
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun deleteOriginalFile(file: File, onLog: suspend (String) -> Unit) {
        if (!file.exists()) return
        var deleted = file.delete()
        if (!deleted) {
            delay(100); System.gc(); deleted = file.delete()
        }
        if (!deleted) onLog("  ⚠ Could not delete original ${file.name}")
    }

    private fun tryScanPage(
        renderer: PDFRenderer, pageIndex: Int, dpi: Float,
        config: AppConfig, mainTess: Tesseract, osdTess: Tesseract?,
        fileName: String, tag: String
    ): String? {
        val raw = renderer.renderImageWithDPI(pageIndex, dpi)
        val isKeyword = config.mode == ScanMode.KEYWORD_GROUP

        // Full-page preprocessing is computed lazily: for the common case where
        // the region crop finds the target text, the full page is never preprocessed.
        val preparedFull: BufferedImage by lazy(LazyThreadSafetyMode.NONE) { fixImageForOcr(raw, dpi) }

        // For keyword mode: preprocess the full page (keyword can be anywhere).
        // For other modes: crop raw image first — preprocess only the crop
        //   (~9–12% of full-page pixel area) to minimise CLAHE + adaptive-threshold work.
        var result = if (isKeyword) {
            scanFullPage(preparedFull, config, mainTess, fileName, pageIndex, "$tag-FULL")
        } else {
            scanRegionRaw(raw, config, mainTess, fileName, pageIndex, "$tag-STRAIGHT")
        }

        if (result == null && !isKeyword) {
            result = scanFullPage(preparedFull, config, mainTess, fileName, pageIndex, "$tag-FULL")
        }
        if (result != null) return result
        if (!config.autoRotate) return null

        // ── Rotation fallback ────────────────────────────────────────────────
        // Stage 1: Try OSD to detect orientation (fast-path for the common case).
        // Stage 2: If OSD fails or returns 0° (unreliable on many scanned forms),
        //          brute-force try 90°, 180°, 270° and take the first match.
        //          This mirrors PdfRotationService's titleBlockOcrDetect approach
        //          but is cheaper since we only need to find a keyword/WO match,
        //          not measure confidence across all angles.
        val osdAngle = if (osdTess != null) {
            try {
                val osdResult = osdTess.doOCR(raw)
                val rotateVal = Regex("(?im)^\\s*Rotate\\s*:\\s*(\\d+)").find(osdResult)?.groupValues?.get(1)?.toIntOrNull()
                val orientVal = if (rotateVal == null) {
                    Regex("(?im)^\\s*Orientation in degrees\\s*:\\s*(\\d+)").find(osdResult)?.groupValues?.get(1)?.toIntOrNull()?.let { deg -> ((360 - deg) % 360) }
                } else null
                rotateVal ?: orientVal ?: 0
            } catch (e: Exception) {
                println("  ⚠ [OSD] Failed for $fileName P$pageIndex: ${e.message}")
                0
            }
        } else 0

        // Build the list of angles to try: OSD angle first (if non-zero), then the
        // remaining 90° multiples we haven't tried yet.
        val anglesToTry = buildList {
            if (osdAngle != 0) add(osdAngle)
            for (a in listOf(90, 180, 270)) {
                if (a != osdAngle) add(a)
            }
        }

        for (angle in anglesToTry) {
            val rotated = OpenCvOps.rotate90(preparedFull, angle)
            result = if (isKeyword) {
                scanFullPage(rotated, config, mainTess, fileName, pageIndex, "$tag-ROT$angle-FULL")
            } else {
                scanRegion(rotated, config, mainTess, fileName, pageIndex, "$tag-ROT$angle")
                    ?: scanFullPage(rotated, config, mainTess, fileName, pageIndex, "$tag-ROT$angle-FULL")
            }
            if (result != null) return result
        }

        return null
    }

    private fun scanRegion(
        image: BufferedImage,
        config: AppConfig,
        tess: Tesseract,
        fName: String,
        pIdx: Int,
        tag: String
    ): String? {
        // Top 30% — most common header position
        val topCropH = (image.height * 0.30).toInt().coerceAtLeast(1)
        val topCrop  = image.getSubimage(0, 0, image.width, topCropH)
        saveDebugImage(topCrop, config, fName, pIdx, "$tag-TOP")
        val topResult = runOcr(topCrop, tess, config)
        if (topResult != null) return topResult

        // Bottom-right 40%×30% — engineering drawing title block zone
        val brW = (image.width  * 0.40).toInt().coerceAtLeast(1)
        val brH = (image.height * 0.30).toInt().coerceAtLeast(1)
        val brCrop = image.getSubimage(image.width - brW, image.height - brH, brW, brH)
        saveDebugImage(brCrop, config, fName, pIdx, "$tag-BR")
        return runOcr(brCrop, tess, config)
    }

    /**
     * Crops the raw (unprocessed) image first, then preprocesses only each crop.
     * Reduces CLAHE + adaptive-threshold pixel work to ~9% (top-30%) and ~12%
     * (bottom-right 40%×30%) compared with preprocessing the full page first.
     * Used by [tryScanPage] for the straight-pass region scan.
     */
    private fun scanRegionRaw(
        raw: BufferedImage,
        config: AppConfig,
        tess: Tesseract,
        fName: String,
        pIdx: Int,
        tag: String
    ): String? {
        // Top 30% — most common header position
        val topCropH = (raw.height * 0.30).toInt().coerceAtLeast(1)
        val topCrop  = raw.getSubimage(0, 0, raw.width, topCropH)
        val topPrep  = fixImageForOcr(topCrop)
        saveDebugImage(topPrep, config, fName, pIdx, "$tag-TOP")
        val topResult = runOcr(topPrep, tess, config)
        if (topResult != null) return topResult

        // Bottom-right 40%×30% — engineering drawing title block zone
        val brW    = (raw.width  * 0.40).toInt().coerceAtLeast(1)
        val brH    = (raw.height * 0.30).toInt().coerceAtLeast(1)
        val brCrop = raw.getSubimage(raw.width - brW, raw.height - brH, brW, brH)
        val brPrep = fixImageForOcr(brCrop)
        saveDebugImage(brPrep, config, fName, pIdx, "$tag-BR")
        return runOcr(brPrep, tess, config)
    }

    private fun scanFullPage(
        image: BufferedImage,
        config: AppConfig,
        tess: Tesseract,
        fName: String,
        pIdx: Int,
        tag: String
    ): String? {
        saveDebugImage(image, config, fName, pIdx, tag)
        return runOcr(image, tess, config)
    }

    private fun runOcr(image: BufferedImage, tess: Tesseract, config: AppConfig): String? = runCatching {
        val text = tess.doOCR(image)
        if (config.mode == ScanMode.KEYWORD_GROUP) {
            if (text.contains(config.delimiter, ignoreCase = true)) config.delimiter else null
        } else extractRegex(text, config)
    }.getOrNull()

    // ── Digit confusion sets ──────────────────────────────────────────────────
    //
    // These are digit pairs that Tesseract LSTM genuinely confuses visually.
    // Unlike letter→digit swaps (O→0, l→1) which are deterministic, digit→digit
    // confusions are bidirectional and cannot be resolved by normalization alone —
    // we need multi-pass voting instead (see voteWoNumber).
    //
    // Primary confusion pairs observed in practice:
    //   5 ↔ 6  — closed top of 6 reads as 5 on degraded ink; open 6 reads as 5
    //   0 ↔ 6  — narrow 0 with serif reads as 6; wide 6 reads as 0
    //   0 ↔ 8  — secondary; less frequent
    //   1 ↔ 7  — rare but occasionally appears
    private val CONFUSED_DIGIT_PAIRS = setOf(
        '5' to '6', '6' to '5',
        '0' to '6', '6' to '0',
        '0' to '8', '8' to '0',
        '1' to '7', '7' to '1'
    )

    /**
     * Returns true if [a] and [b] are the same length and differ ONLY in
     * positions where the differing characters are a known confused pair.
     * e.g. "214205" vs "214206" → true (position 5: 5↔6)
     *      "214205" vs "214305" → false (position 3: 2↔3, not a confused pair)
     */
    private fun differOnlyInConfusedDigits(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        return a.zip(b).all { (ca, cb) ->
            ca == cb || (ca to cb) in CONFUSED_DIGIT_PAIRS
        }
    }

    /**
     * Multi-pass vote for the WO number to defeat 5↔6 / 0↔6 single-pass errors.
     *
     * Strategy:
     *  1. Run OCR at [dpi1] and [dpi2] (already rendered images supplied by caller).
     *  2. If both agree → return that number immediately (high confidence).
     *  3. If they disagree ONLY in confused-digit positions (5/6/0/8) →
     *     run a third OCR pass at [dpi3] on [tiebreakImage] (a tight crop re-rendered
     *     at higher DPI by caller) and use majority vote across all three results.
     *  4. If they disagree in non-confused positions → the images are likely different
     *     orientations or crops; return null and let the caller fall through to the
     *     next strategy.
     *
     * This is called from [processSinglePdf] after both the 200-DPI and 300-DPI
     * scans have run and found a candidate.
     */
    fun voteWoNumber(
        candidate1: String,
        candidate2: String,
        tiebreakImage: BufferedImage?,
        tess: Tesseract,
        config: AppConfig
    ): String {
        // Fast path: both passes agree
        if (candidate1 == candidate2) return candidate1

        // Both have the right digit count and differ only in confused digits →
        // run a tiebreaker OCR pass
        if (differOnlyInConfusedDigits(candidate1, candidate2) && tiebreakImage != null) {
            val candidate3 = runOcr(tiebreakImage, tess, config)
            // Majority vote: whichever of c1/c2 matches c3 wins
            return when (candidate3) {
                candidate1 -> candidate1
                candidate2 -> candidate2
                else -> {
                    // c3 is a third distinct value — pick whichever of c1/c2 has fewer
                    // differing positions with c3 (closest match wins)
                    val c3 = candidate3 ?: return@voteWoNumber candidate2
                    val diff1 = candidate1.zip(c3).count { (a, b) -> a != b }
                    val diff2 = candidate2.zip(c3).count { (a, b) -> a != b }
                    if (diff1 <= diff2) candidate1 else candidate2
                }
            }
        }

        // Disagreement in non-confused positions — prefer the 300-DPI result (candidate2
        // is always the higher-DPI pass when called from processSinglePdf)
        return candidate2
    }

    private fun extractRegex(text: String, config: AppConfig): String? {
        if (text.isBlank()) return null

        val delimPat = if (config.delimiter.equals("WO", ignoreCase = true))
        // Also match common OCR corruptions of "WO": W0, WC, VVO, VV0, W 0, W O
            "(?:W[O0C]|VV[O0]|W\\s+[O0]|Work\\s*Order)"
        else Regex.escape(config.delimiter)
        val delimRegex = Regex("(?i)$delimPat")

        val compiledPattern = config.pattern.map { char ->
            when (char) {
                '0' -> "\\d"
                '?' -> "[A-Za-z0-9]"
                else -> Regex.escape(char.toString())
            }
        }.joinToString("")
        val numRegex = Regex("\\b$compiledPattern\\b")

        // Normalize letter→digit OCR substitutions that ARE deterministic.
        // Note: 5↔6, 0↔6 are NOT handled here — they're bidirectional digit
        // confusions that require multi-pass voting (see voteWoNumber).
        fun normalizeOcrDigits(s: String): String = s
            .replace(Regex("(?<=[0-9OolIBSZo])O(?=[0-9OolIBSZo])", RegexOption.IGNORE_CASE), "0")
            .replace(Regex("(?<=[0-9])l(?=[0-9])"), "1")
            .replace(Regex("(?<=[0-9])I(?=[0-9])"), "1")
            .replace(Regex("(?<=[0-9])B(?=[0-9])"), "8")
            .replace(Regex("(?<=[0-9])S(?=[0-9])"), "5")
            .replace(Regex("(?<=[0-9])Z(?=[0-9])"), "2")

        fun findNum(s: String): String? =
            numRegex.find(s)?.value ?: numRegex.find(normalizeOcrDigits(s))?.value

        val lines = text.lines()
        for (i in lines.indices) {
            val match = delimRegex.find(lines[i]) ?: continue
            val after = lines[i].substring(match.range.last + 1)

            findNum(after)?.let { return it }
            if (i + 1 < lines.size) findNum(lines[i + 1])?.let { return it }
        }

        val clean  = text.replace("\n", "  ")
        val header = delimRegex.find(clean) ?: return null
        val chunk  = clean.substring(
            header.range.last + 1,
            (header.range.last + 81).coerceAtMost(clean.length)
        )
        return findNum(chunk)
    }

    private fun fixImageForOcr(original: BufferedImage, @Suppress("UNUSED_PARAMETER") dpi: Float = 0f): BufferedImage =
        OpenCvOps.prepareForOcr(
            original,
            useDenoise  = false,
            useAdaptive = true  // adaptive threshold at all DPIs — Otsu loses strokes on uneven scans
        )

    private suspend fun saveRenameOnly(originalFile: File, doc: PDDocument, wo: String) {
        val safeWo = wo.replace(Regex("[\\\\/:*?\"<>|]"), "-")
        doc.save(resolveCollision(originalFile.parentFile, "$safeWo.pdf"))
    }

    private suspend fun saveSplitOutput(originalFile: File, doc: PDDocument, map: Map<String, List<Int>>, config: AppConfig = AppConfig()) {
        // ── Create a subfolder for extracted files ──
        val outputDir = File(originalFile.parentFile, originalFile.nameWithoutExtension)
        if (!outputDir.exists()) outputDir.mkdirs()

        if (config.mode == ScanMode.KEYWORD_GROUP && config.keywordExtractSeparately) {
            // ── Separate mode: one PDF per matched page with a counter ──
            var counter = 1
            map.forEach { (wo, pages) ->
                if (wo == "Unassigned") return@forEach
                pages.forEach { pageIdx ->
                    PDDocument().use { newDoc ->
                        newDoc.importPage(doc.getPage(pageIdx))
                        val safeDelim = config.delimiter.replace(Regex("[\\\\/:*?\"<>|]"), "-")
                        val fileName = "${originalFile.nameWithoutExtension}_${safeDelim}_page$counter.pdf"
                        newDoc.save(resolveCollision(outputDir, fileName))
                        counter++
                    }
                }
            }
        } else {
            // ── Combined mode: one PDF per group (original behavior) ──
            map.forEach { (wo, pages) ->
                if (wo == "Unassigned") return@forEach
                PDDocument().use { newDoc ->
                    pages.forEach { newDoc.importPage(doc.getPage(it)) }
                    val safeWo = wo.replace(Regex("[\\\\/:*?\"<>|]"), "-")
                    newDoc.save(resolveCollision(outputDir, "$safeWo.pdf"))
                }
            }
        }
    }

    private fun buildRemainingTemp(
        originalFile: File, doc: PDDocument,
        map: Map<String, List<Int>>, dropped: Set<Int> = emptySet()
    ): File? {
        val extracted = map.filterKeys { it != "Unassigned" }.values.flatten().toSet()
        if (extracted.isEmpty()) return null
        val toRemove = extracted + dropped
        val remaining = (0 until doc.numberOfPages).filter { it !in toRemove }
        if (remaining.isEmpty()) return null
        val tmp = File(originalFile.parentFile, "${originalFile.nameWithoutExtension}_remaining.tmp.pdf")
        PDDocument().use { newDoc ->
            remaining.forEach { newDoc.importPage(doc.getPage(it)) }
            newDoc.save(tmp)
        }
        return tmp
    }

    private suspend fun applyRemainingTemp(
        originalFile: File, tmpFile: File,
        map: Map<String, List<Int>>,
        onLog: suspend (String) -> Unit
    ) {
        val extractedCount = map.filterKeys { it != "Unassigned" }.values.flatten().size
        if (!originalFile.delete()) {
            onLog("  ⚠ [${originalFile.name}] Could not replace original (file in use?)")
            tmpFile.delete(); return
        }
        tmpFile.renameTo(originalFile)
        onLog("  ✅ [${originalFile.name}] Removed $extractedCount extracted page(s) from original.")
    }

    private fun resolveCollision(dir: File, fileName: String): File {
        var f = File(dir, fileName)
        if (!f.exists()) return f
        val name = f.nameWithoutExtension;
        val ext = f.extension;
        var c = 1
        while (f.exists()) {
            f = File(dir, "${name}_part$c.$ext"); c++
        }
        return f
    }

    private fun saveDebugImage(img: BufferedImage, config: AppConfig, fName: String, pIdx: Int, tag: String) {
        if (config.debugMode) runCatching {
            val outDir = File("debug_images").apply { mkdirs() }
            ImageIO.write(img, "jpg", File(outDir, "${fName}_P${pIdx}_$tag.jpg"))
        }
    }
}