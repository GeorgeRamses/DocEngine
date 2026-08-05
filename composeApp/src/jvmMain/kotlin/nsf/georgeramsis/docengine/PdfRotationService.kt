package nsf.georgeramsis.docengine

import net.sourceforge.tess4j.ITessAPI
import net.sourceforge.tess4j.Tesseract
import ocr.TessDataManager
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.File
import java.io.PrintWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean  // FIX: replaces @Volatile Boolean
import java.util.concurrent.atomic.AtomicInteger

// ─────────────────────────────────────────────────────────────────────────────
// Public result types
// ─────────────────────────────────────────────────────────────────────────────

data class RotationResult(
    val success: Boolean,
    val message: String,
    val pagesRotated: Int,
    val inputFile: File,
    val skipped: Boolean = false
)

data class BatchSummary(
    val totalFiles: Int,
    val succeeded: Int,
    val failed: Int,
    val skipped: Int,
    val elapsedMs: Long,
    val logFile: File?
)

// ─────────────────────────────────────────────────────────────────────────────
// Checkpoint
// ─────────────────────────────────────────────────────────────────────────────

class BatchCheckpoint(val file: File) {

    private val previouslyDone: Set<String> =
        if (file.exists()) file.readLines().map { it.trim() }.filter { it.isNotBlank() }.toSet()
        else emptySet()

    private val writer: PrintWriter = PrintWriter(
        Files.newBufferedWriter(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    )

    fun isDone(f: File): Boolean = f.absolutePath in previouslyDone
    val resumedCount: Int get() = previouslyDone.size

    @Synchronized
    fun markDone(f: File) { writer.println(f.absolutePath); writer.flush() }

    fun close() = writer.close()

    companion object {
        fun defaultPath(inputFolder: File): File = File(inputFolder, ".rotation_checkpoint")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Internal types
// ─────────────────────────────────────────────────────────────────────────────

private data class OsdResult(val correctionCcw: Int, val confidence: Double)

// ─────────────────────────────────────────────────────────────────────────────
// PdfRotationService
// ─────────────────────────────────────────────────────────────────────────────

class PdfRotationService {

    companion object {
        private const val DETECT_DPI = 150f

        // FIX: OCR_DPI set once here instead of inside every parallel OCR task.
        // Each crop is always 2× upscaled before Tesseract — this value never changes.
        private const val OCR_DPI = (DETECT_DPI * 2).toInt()

        private val N_CPU              = Runtime.getRuntime().availableProcessors()
        // ANGLE_POOL runs all 8 OCR tasks per page in parallel (4 angles × 2 crops).
        // Sized at max(8, N_CPU) so that even on a 4-core machine we keep 8 slots
        // busy (the OCR threads are mostly waiting on native Tesseract, not CPU-bound).
        val ANGLE_POOL                 = Executors.newFixedThreadPool(N_CPU.coerceAtLeast(8))
        private val MAX_PARALLEL_FILES = (N_CPU / 4).coerceIn(1, 8)
        private const val MAX_IN_FLIGHT = 16

        init {
            System.setProperty("OMP_NUM_THREADS", "1")
            System.setProperty("OMP_THREAD_LIMIT", "1")
            // FIX: shut down ANGLE_POOL cleanly on JVM exit.
            // Without this the fixed thread pool leaks threads and prevents
            // clean application exit.
            Runtime.getRuntime().addShutdownHook(Thread { ANGLE_POOL.shutdownNow() })
        }

        // Minimum gap the stronger crop must show when its partner is flat.
        // Set just below the weakest observed real signal (205819 p1: 9.93).
        private const val MIN_STRONG_CROP_GAP = 9.5
        private const val OSD_CONF_HIGH     = 4.0
        private const val OSD_FALLBACK_CONF = 1.5

        // OCR confidence thresholds.
        // Gap between winner and runner-up score to act.
        private const val MIN_CONF_GAP    = 8.0
    }

    // FIX: AtomicBoolean is the correct tool for a cancellation flag accessed
    // from multiple threads. @Volatile on a primitive boolean works in practice
    // but AtomicBoolean makes the intent explicit and is the idiomatic choice.
    private val cancelRequested = AtomicBoolean(false)
    fun requestCancel() { cancelRequested.set(true) }
    fun resetCancel()   { cancelRequested.set(false) }

    @Volatile var diagEnabled: Boolean = false

    private val osdTessLocal   = ThreadLocal.withInitial { buildOsdTesseractOrNull() }
    // Each ANGLE_POOL thread owns one Tesseract instance — no lock contention.
    private val angleTessLocal = ThreadLocal.withInitial { buildTesseract() }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    fun rotateFolderInPlace(
        folder: File,
        checkpoint: BatchCheckpoint? = null,
        onProgress: (current: Int, total: Int, message: String) -> Unit,
        onFileComplete: (RotationResult) -> Unit
    ): BatchSummary {
        val files = folder.listFiles { f ->
            f.isFile && f.extension.equals("pdf", ignoreCase = true)
        }?.sortedBy { it.name.lowercase() }.orEmpty()
        return rotateFilesInPlace(files, checkpoint, onProgress, onFileComplete)
    }

    fun rotateFilesInPlace(
        files: List<File>,
        checkpoint: BatchCheckpoint? = null,
        onProgress: (current: Int, total: Int, message: String) -> Unit,
        onFileComplete: (RotationResult) -> Unit
    ): BatchSummary {
        val startMs      = System.currentTimeMillis()
        val succeeded    = AtomicInteger(0)
        val failed       = AtomicInteger(0)
        val skippedCount = AtomicInteger(checkpoint?.resumedCount ?: 0)
        val doneCount    = AtomicInteger(skippedCount.get())
        val total        = files.size

        if (files.isEmpty()) return BatchSummary(0, 0, 0, 0, 0L, null)

        val filePool  = Executors.newFixedThreadPool(minOf(MAX_PARALLEL_FILES, total))
        val semaphore = Semaphore(MAX_IN_FLIGHT)
        val futures   = mutableListOf<Future<*>>()

        for (file in files) {
            if (cancelRequested.get()) break

            if (checkpoint?.isDone(file) == true) {
                onFileComplete(RotationResult(true, "Skipped (already processed).", 0, file, skipped = true))
                val n = doneCount.incrementAndGet()
                onProgress(n, total, "Skipped ${file.name} (resumed)")
                continue
            }

            semaphore.acquire()
            futures += filePool.submit {
                try {
                    if (cancelRequested.get()) {
                        onFileComplete(RotationResult(false, "Rotation cancelled.", 0, file))
                        failed.incrementAndGet()
                        return@submit
                    }
                    val n = doneCount.incrementAndGet()
                    onProgress(n, total, "Rotating ${file.name}…")
                    val result = rotateFileInPlace(file) { msg -> onProgress(n, total, msg) }
                    if (result.success) { checkpoint?.markDone(file); succeeded.incrementAndGet() }
                    else failed.incrementAndGet()
                    onFileComplete(result)
                } finally {
                    semaphore.release()
                }
            }
        }

        filePool.shutdown()
        futures.forEach { runCatching { it.get() } }
        checkpoint?.close()

        val elapsed = System.currentTimeMillis() - startMs
        val logFile = checkpoint?.let {
            writeBatchLog(
                File(it.file.parentFile, ".rotation_log.txt"),
                total, succeeded.get(), failed.get(), skippedCount.get(), elapsed
            )
        }
        return BatchSummary(total, succeeded.get(), failed.get(), skippedCount.get(), elapsed, logFile)
    }

    fun rotateFileInPlace(
        inputFile: File,
        onProgress: (String) -> Unit
    ): RotationResult {
        if (!inputFile.exists() || !inputFile.isFile)
            return RotationResult(false, "File not found: ${inputFile.name}", 0, inputFile)

        val tempFile     = File(inputFile.parentFile, "${inputFile.nameWithoutExtension}.rotate.tmp")
        var pagesRotated = 0

        val diagWriter = if (diagEnabled)
            File(inputFile.parentFile, "${inputFile.nameWithoutExtension}.rotation-diag.log")
                .printWriter()
        else null
        fun dlog(msg: String) { diagWriter?.println(msg); diagWriter?.flush() }
        val log: (String) -> Unit = { msg -> onProgress(msg); if (diagEnabled) dlog(msg) }

        diagWriter?.let {
            dlog("=== Rotation diagnostic: ${inputFile.name} ===")
            dlog("Timestamp : ${LocalDateTime.now()}")
            dlog("DPI       : $DETECT_DPI")
            dlog("")
        }

        return try {
            PDDocument.load(inputFile).use { doc ->
                val renderer = PDFRenderer(doc).also { it.isSubsamplingAllowed = true }
                val osdTess  = osdTessLocal.get()
                dlog("Pages         : ${doc.numberOfPages}")
                dlog("")

                var inconclusivePages = 0

                for (pageIndex in 0 until doc.numberOfPages) {
                    if (cancelRequested.get())
                        return RotationResult(false, "Rotation cancelled.", pagesRotated, inputFile)

                    onProgress("Checking page ${pageIndex + 1} / ${doc.numberOfPages}")
                    dlog("--- Page ${pageIndex + 1} ---")

                    val page = doc.getPage(pageIndex)
                    val existingTag = page.rotation
                    dlog("  PDF rotation tag = ${existingTag}°")

                    // Render WITHOUT the existing rotation tag so the title block
                    // always lands at the bottom-right of the raw image, regardless
                    // of what /Rotate is stored in the PDF.
                    page.rotation = 0
                    val gray = renderer.renderImageWithDPI(pageIndex, DETECT_DPI, ImageType.GRAY)
                    page.rotation = existingTag  // restore immediately after render

                    dlog("  Rendered image   = ${gray.width}×${gray.height} px  (raw, tag stripped for detection)")

                    // rawCorrection is CCW degrees; PDF /Rotate is CW degrees.
                    // null = inconclusive, leave existing tag unchanged.
                    val rawCorrection: Int? = detectCorrection(gray, osdTess, pageIndex + 1, log)
                    gray.flush()

                    if (rawCorrection == null) {
                        inconclusivePages++
                        dlog("  FINAL rawCorrection = inconclusive  existingTag = ${existingTag}°  → no change")
                        dlog("")
                        onProgress("  ⚠ Page ${pageIndex + 1}: orientation uncertain — left unchanged")
                    } else {
                        val newTag = normRot(-rawCorrection)
                        dlog("  FINAL rawCorrection = ${rawCorrection}° CCW  existingTag = ${existingTag}°  newTag = ${newTag}°")
                        dlog("")

                        if (newTag != existingTag) {
                            page.rotation = newTag
                            pagesRotated++
                            onProgress("  ✓ Page ${pageIndex + 1}: tag ${existingTag}° → ${newTag}°")
                        } else {
                            onProgress("  ✓ Page ${pageIndex + 1}: already correct (${existingTag}°)")
                        }
                    }
                }

                if (pagesRotated > 0) doc.save(tempFile)
                inconclusivePages  // pass out of use block via last expression
            }.let { inconclusivePages ->

                dlog("=== Result: $pagesRotated page(s) rotated ===")
                diagWriter?.close()

                when {
                    pagesRotated > 0 -> {
                        replaceOriginal(tempFile.toPath(), inputFile.toPath())
                        RotationResult(true, "Rotated $pagesRotated page(s).", pagesRotated, inputFile)
                    }
                    inconclusivePages > 0 ->
                        RotationResult(true, "⚠ No rotation applied — $inconclusivePages page(s) uncertain (manual check recommended).", 0, inputFile)
                    else ->
                        RotationResult(true, "✓ No rotation needed.", 0, inputFile)
                }}
        } catch (ex: Exception) {
            dlog("=== EXCEPTION: ${ex.message} ===")
            diagWriter?.close()
            tempFile.delete()
            RotationResult(false, "Failed: ${ex.message}", pagesRotated, inputFile)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Detection
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns the CCW correction angle needed to make the page upright, or null if
     * the detection was inconclusive and the existing tag should be left unchanged.
     *   null  = inconclusive — do NOT modify page.rotation
     *   0     = positively detected as already upright — set page.rotation = 0
     *   90/180/270 = content needs this many degrees CCW to become upright
     */
    private fun detectCorrection(
        gray: BufferedImage,
        osdTess: Tesseract?,
        pageNum: Int,
        log: (String) -> Unit
    ): Int? {
        // Stage 1: OSD fast-path (high-confidence only)
        val osd = osdTess?.let { runOsd(gray, it) }
        // Accept OSD result for any angle (including 0° = already upright) when confidence is high.
        // This short-circuits Stage 2 (8 parallel OCR calls) for the common case of correctly
        // oriented pages, replacing 8 Tesseract calls with a single OSD call.
        if (osd != null && osd.confidence >= OSD_CONF_HIGH) {
            log("  [p$pageNum] OSD fast-path: ${osd.correctionCcw}° conf=${fmt(osd.confidence)}")
            return osd.correctionCcw
        }

        // Stage 2: Crop title block → OCR at 4 rotations → pick highest confidence
        val result = titleBlockOcrDetect(gray, pageNum, log)
        if (result != null) return result

        // Stage 3: OSD fallback
        if (osd != null && osd.correctionCcw != 0 && osd.confidence >= OSD_FALLBACK_CONF) {
            log("  [p$pageNum] OSD fallback ${osd.correctionCcw}°")
            return osd.correctionCcw
        }

        log("  [p$pageNum] inconclusive — leaving existing tag unchanged")
        return null
    }

    /**
     * Crops two independent zones of the page at each of 4 rotation candidates,
     * OCRs them, and returns the correction angle. Returns null if inconclusive.
     *
     * Crop A: right 60% × bottom 30%  — title block / footer zone
     * Crop B: right 25% × full height — right column / title block column
     *
     * ── Performance ───────────────────────────────────────────────────────────
     * 1. PRE-RENDER: the four rotated thumbnails are produced once and shared
     *    between both crops, halving image-rotation operations from 8 to 4.
     * 2. PARALLEL OCR: all 8 OCR calls run simultaneously on ANGLE_POOL.
     *    Each worker uses its own ThreadLocal Tesseract — no lock contention.
     *    On a 4-core machine wall time drops to ~1 OCR call (~200 ms) vs ~1600 ms.
     */
    private fun titleBlockOcrDetect(
        gray: BufferedImage, pageNum: Int, log: (String) -> Unit
    ): Int? {

        // Pre-render all 4 rotations once — shared by both crops below
        val ANGLES    = listOf(0, 90, 180, 270)
        val rotations = ANGLES.map { angle ->
            if (angle == 0) gray else rotateGray(gray, angle)
        }

        // Submit all 8 OCR tasks in parallel.
        // Each task: crop → upscale 2× → OCR → flush upscaled image.
        fun makeCropFutures(
            cropFn: (BufferedImage) -> BufferedImage
        ): List<Pair<Int, java.util.concurrent.Future<Double>>> =
            ANGLES.mapIndexed { i, angle ->
                val rotated = rotations[i]
                angle to ANGLE_POOL.submit(Callable {
                    val sub  = cropFn(rotated)
                    val upW  = sub.width  * 2
                    val upH  = sub.height * 2
                    val up   = BufferedImage(upW, upH, BufferedImage.TYPE_BYTE_GRAY).also { dst ->
                        val g = dst.createGraphics()
                        g.setRenderingHint(
                            java.awt.RenderingHints.KEY_INTERPOLATION,
                            java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC
                        )
                        g.drawImage(sub, 0, 0, upW, upH, null)
                        g.dispose()
                    }
                    val tess  = angleTessLocal.get()
                    val score = avgWordConfidence(tess, up)
                    up.flush()  // FIX: release native image memory promptly
                    score
                })
            }

        val futuresA = makeCropFutures { rotated ->
            val cW = (rotated.width  * 0.60).toInt().coerceAtLeast(1)
            val cH = (rotated.height * 0.30).toInt().coerceAtLeast(1)
            rotated.getSubimage(rotated.width - cW, rotated.height - cH, cW, cH)
        }
        val futuresB = makeCropFutures { rotated ->
            val cW = (rotated.width  * 0.25).toInt().coerceAtLeast(1)
            rotated.getSubimage(rotated.width - cW, 0, cW, rotated.height)
        }

        // Collect results (futures are already running in parallel)
        val scoresA = futuresA.map { (angle, f) -> angle to f.get() }
        val scoresB = futuresB.map { (angle, f) -> angle to f.get() }

        // FIX: flush the 3 intermediate rotation images (angle=0 is the original gray)
        rotations.drop(1).forEach { it.flush() }

        val bestA = scoresA.maxByOrNull { it.second }!!
        val gapA  = bestA.second - scoresA.filter { it.first != bestA.first }.maxOf { it.second }

        val bestB = scoresB.maxByOrNull { it.second }!!
        val gapB  = bestB.second - scoresB.filter { it.first != bestB.first }.maxOf { it.second }

        log("  [p$pageNum] cropA (right60%×bot30%): ${scoresA.joinToString { "${it.first}°=${fmt(it.second)}" }} best=${bestA.first}° gap=${fmt(gapA)}")
        log("  [p$pageNum] cropB (right25%×full):   ${scoresB.joinToString { "${it.first}°=${fmt(it.second)}" }} best=${bestB.first}° gap=${fmt(gapB)}")

        // ── Vote — IDENTICAL to original ─────────────────────────────────────

        // Both crops agree → use combined gap
        if (bestA.first == bestB.first) {
            val angle       = bestA.first
            val combinedGap = gapA + gapB
            val minGap      = minOf(gapA, gapB)
            log("  [p$pageNum] both crops agree: ${angle}° combinedGap=${fmt(combinedGap)} minGap=${fmt(minGap)}")

            val oneFlat = minGap < 5.0
            if (oneFlat) {
                // Only block if the stronger crop is also weak.
                // When one crop has a meaningful gap (≥ MIN_STRONG_CROP_GAP) and
                // the other is flat, the flat crop has no signal — not a contradiction.
                // Observed: 09-3219 (34.16/2.28), 09-20823 p2 (18.68/3.90),
                //            205819 p1 (9.93/1.56).
                val maxGap = maxOf(gapA, gapB)
                if (maxGap < MIN_STRONG_CROP_GAP) {
                    log("  [p$pageNum] one crop flat and maxGap ${fmt(maxGap)} < ${fmt(MIN_STRONG_CROP_GAP)} — inconclusive")
                    return null
                }
                log("  [p$pageNum] one crop flat but strong crop decisive (maxGap=${fmt(maxGap)}) — proceeding")
            }
            // Combined gap check — skipped when one crop is flat (already validated above)
            if (!oneFlat && combinedGap < MIN_CONF_GAP * 1.8) {
                log("  [p$pageNum] combinedGap too low — inconclusive")
                return null
            }
            if (angle == 0) return 0
            log("  [p$pageNum] → ${angle}° correction")
            return angle
        }

        // Crops disagree — determine winner by larger gap
        val winnerAngle: Int
        val winnerGap:   Double
        val loserAngle:  Int
        val loserGap:    Double
        if (gapA >= gapB) {
            winnerAngle = bestA.first; winnerGap = gapA; loserAngle = bestB.first; loserGap = gapB
        } else {
            winnerAngle = bestB.first; winnerGap = gapB; loserAngle = bestA.first; loserGap = gapA
        }
        log("  [p$pageNum] crops disagree — winner=${winnerAngle}°/${fmt(winnerGap)} loser=${loserAngle}°/${fmt(loserGap)}")

        if (winnerAngle == 0 && loserAngle != 0) {
            if (winnerGap < 2.0 * loserGap) {
                if (loserGap >= 6.0) {
                    log("  [p$pageNum] 0° not dominant (${fmt(winnerGap)} < 2×${fmt(loserGap)}) — prefer ${loserAngle}°")
                    return normRot(-loserAngle)
                } else {
                    log("  [p$pageNum] 0° not dominant but loser gap too weak — inconclusive")
                    return null
                }
            } else {
                if (winnerGap < MIN_CONF_GAP) {
                    log("  [p$pageNum] 0° dominant but gap too low — inconclusive")
                    return null
                }
                log("  [p$pageNum] 0° dominant — no rotation")
                return 0
            }
        }

        // ── Both crops have strong, contradictory signals → full-page tiebreaker ─
        // When loserGap ≥ MIN_CONF_GAP both crops would individually be confident
        // enough to trigger rotation, but they disagree.  The larger-gap heuristic
        // is unreliable here (e.g. a full-height strip can outscore the title block
        // because it contains more text, not because the angle is correct).
        // Run full-page OCR at both candidate angles and compare word quality.
        if (loserGap >= MIN_CONF_GAP) {
            val tiebreaker = fullPageTiebreaker(gray, winnerAngle, loserAngle, pageNum, log)
            if (tiebreaker != null) return tiebreaker
            log("  [p$pageNum] tiebreaker inconclusive — leaving unchanged")
            return null
        }

        // When loserGap ≈ 0 one crop has no signal — we're effectively trusting
        // a single crop. Single crops need a higher threshold than genuine disagreement.
        // Observed false positive: 205814 p1 — loserGap=0.57, winnerGap=10.02 → wrong rotation.
        // When loserGap ≥ 2 both crops have signal but disagree; MIN_CONF_GAP is sufficient.
        val requiredGap = if (loserGap < 2.0) 15.0 else MIN_CONF_GAP
        if (winnerGap < requiredGap) {
            log("  [p$pageNum] winning gap ${fmt(winnerGap)} < ${fmt(requiredGap)} in disagreement (loserGap=${fmt(loserGap)}) — inconclusive")
            return null
        }
        log("  [p$pageNum] → ${winnerAngle}° correction (gap=${fmt(winnerGap)})")
        return winnerAngle
    }

    /**
     * Full-page OCR tiebreaker: renders the raw image at both candidate angles,
     * runs Tesseract on the entire page, and picks the angle that produces more
     * high-confidence words with a better average confidence.
     *
     * Score = avgConf(conf≥50 words) + confidentWordCount × 0.5
     * Returns the winning CCW angle, or null if the gap is too small.
     *
     * Both OCR calls run in parallel on [ANGLE_POOL] so wall time ≈ one OCR call.
     */
    private fun fullPageTiebreaker(
        gray: BufferedImage,
        angleA: Int, angleB: Int,
        pageNum: Int, log: (String) -> Unit
    ): Int? {
        log("  [p$pageNum] both crops have strong signals — running full-page OCR tiebreaker")

        data class TBScore(val angle: Int, val nWords: Int, val avgConf: Double, val score: Double)

        fun scoreAngle(angle: Int): TBScore {
            val rotated = if (angle == 0) gray else rotateGray(gray, angle)
            try {
                val tess  = angleTessLocal.get()
                val words = try {
                    tess.getWords(rotated, ITessAPI.TessPageIteratorLevel.RIL_WORD)
                        .filter { it.confidence >= 50f && it.text.any { c -> c.isLetter() } }
                } catch (_: Exception) { emptyList() }

                if (words.isEmpty()) return TBScore(angle, 0, 0.0, 0.0)
                val avg   = words.sumOf { it.confidence.toDouble() } / words.size
                val score = avg + words.size * 0.5
                return TBScore(angle, words.size, avg, score)
            } finally {
                if (angle != 0) rotated.flush()
            }
        }

        val futureA = ANGLE_POOL.submit(Callable { scoreAngle(angleA) })
        val futureB = ANGLE_POOL.submit(Callable { scoreAngle(angleB) })
        val sA = futureA.get()
        val sB = futureB.get()

        log("  [p$pageNum] tiebreaker ${angleA}° → ${sA.nWords} conf≥50 words, avgConf=${fmt(sA.avgConf)}, score=${fmt(sA.score)}")
        log("  [p$pageNum] tiebreaker ${angleB}° → ${sB.nWords} conf≥50 words, avgConf=${fmt(sB.avgConf)}, score=${fmt(sB.score)}")

        val winner = if (sA.score >= sB.score) sA else sB
        val loser  = if (sA.score >= sB.score) sB else sA
        val gap    = winner.score - loser.score

        if (gap < 10.0) {
            log("  [p$pageNum] tiebreaker gap ${fmt(gap)} too small — inconclusive")
            return null
        }

        log("  [p$pageNum] tiebreaker resolved: ${winner.angle}° (gap=${fmt(gap)})")
        return winner.angle
    }

    /**
     * Rotates a grayscale BufferedImage by the given CCW angle (90/180/270).
     * Uses Graphics2D so it works on any image type, not just binary rasters.
     */
    private fun rotateGray(src: BufferedImage, angleCcw: Int): BufferedImage {
        val (dstW, dstH) = if (angleCcw == 90 || angleCcw == 270)
            src.height to src.width else src.width to src.height
        val dst = BufferedImage(dstW, dstH, BufferedImage.TYPE_BYTE_GRAY)
        val g   = dst.createGraphics()
        when (angleCcw) {
            90  -> { g.translate(0, src.width);  g.rotate(Math.toRadians(-90.0)) }
            180 -> { g.translate(src.width, src.height); g.rotate(Math.PI) }
            270 -> { g.translate(src.height, 0); g.rotate(Math.toRadians(90.0)) }
        }
        g.drawImage(src, 0, 0, null)
        g.dispose()
        return dst
    }

    /**
     * Combined orientation score: avgConf + wordCount × 0.5.
     * Returns 0.0 on blank image or any Tesseract failure — treated as no signal.
     */
    private fun avgWordConfidence(tess: Tesseract, img: BufferedImage): Double {
        // FIX: explicit catch instead of runCatching to avoid swallowing Error subclasses
        val words = try {
            tess.getWords(img, ITessAPI.TessPageIteratorLevel.RIL_WORD)
                .filter { it.text.any { c -> c.isLetter() } }
        } catch (_: Exception) {
            return 0.0
        }
        if (words.isEmpty()) return 0.0
        val avgConf   = words.sumOf { it.confidence.toDouble() } / words.size
        val wordBonus = words.size * 0.5
        return avgConf + wordBonus
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OSD
    // ─────────────────────────────────────────────────────────────────────────

    private fun runOsd(gray: BufferedImage, osdTess: Tesseract): OsdResult? =
        try { parseOsdOutput(osdTess.doOCR(gray)) } catch (_: Throwable) { null }

    private fun parseOsdOutput(text: String?): OsdResult? {
        if (text.isNullOrBlank()) return null
        val rotateVal = Regex("""(?im)^\s*Rotate\s*:\s*(\d+)\s*$""")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val orientVal = if (rotateVal == null)
            Regex("""(?im)^\s*Orientation in degrees\s*:\s*(\d+)\s*$""")
                .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { normRot(360 - it) }
        else null
        val correctionCcw = rotateVal ?: orientVal ?: return null
        val confidence    = Regex("""(?im)^\s*Orientation confidence\s*:\s*([0-9]+(?:\.[0-9]+)?)\s*$""")
            .find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        return OsdResult(normRot(correctionCcw), confidence)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    private fun fmt(d: Double)  = "%.2f".format(d)
    private fun normRot(r: Int) = ((r % 360) + 360) % 360

    private fun writeBatchLog(
        logFile: File, total: Int, succeeded: Int, failed: Int, skipped: Int, elapsedMs: Long
    ): File {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        logFile.appendText("""
            ═══════════════════════════════════════
            Batch completed : $ts
            Total files     : $total
            Succeeded       : $succeeded
            Failed          : $failed
            Skipped/Resumed : $skipped
            Elapsed         : ${formatElapsed(elapsedMs)}
            ═══════════════════════════════════════

        """.trimIndent() + "\n")
        return logFile
    }

    private fun formatElapsed(ms: Long): String {
        val s = ms / 1000
        return when { s < 60 -> "${s}s"; s < 3600 -> "${s/60}m ${s%60}s"; else -> "${s/3600}h ${(s%3600)/60}m" }
    }

    private fun replaceOriginal(tmp: Path, orig: Path) {
        try { Files.move(tmp, orig, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(tmp, orig, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun buildTesseract(): Tesseract = Tesseract().apply {
        setDatapath(TessDataManager.tessDataPath)
        setLanguage("eng")
        setPageSegMode(ITessAPI.TessPageSegMode.PSM_SPARSE_TEXT)
        setOcrEngineMode(ITessAPI.TessOcrEngineMode.OEM_LSTM_ONLY)
        // FIX: OCR_DPI set once here instead of inside every parallel task.
        // Crops are always 2× upscaled before OCR so this value is constant.
        setVariable("user_defined_dpi", OCR_DPI.toString())
        setVariable("preserve_interword_spaces", "1")
        // Accept short tokens: revision codes (A, B1, R0), scale denominators (5, 10, 50)
        setVariable("tessedit_min_word_length", "1")
    }

    private fun buildOsdTesseractOrNull(): Tesseract? {
        if (!File(TessDataManager.tessDataPath, "osd.traineddata").exists()) return null
        return Tesseract().apply {
            setDatapath(TessDataManager.tessDataPath)
            setLanguage("osd")
            setPageSegMode(ITessAPI.TessPageSegMode.PSM_OSD_ONLY)
            setOcrEngineMode(ITessAPI.TessOcrEngineMode.OEM_DEFAULT)
            setVariable("user_defined_dpi", DETECT_DPI.toInt().toString())
        }
    }
}
