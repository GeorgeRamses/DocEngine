package nsf.georgeramsis.docengine

import org.bytedeco.javacv.Java2DFrameConverter
import org.bytedeco.javacv.OpenCVFrameConverter
import org.bytedeco.opencv.global.opencv_core.BORDER_REPLICATE
import org.bytedeco.opencv.global.opencv_core.ROTATE_180
import org.bytedeco.opencv.global.opencv_core.ROTATE_90_CLOCKWISE
import org.bytedeco.opencv.global.opencv_core.ROTATE_90_COUNTERCLOCKWISE
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.global.opencv_imgproc.ADAPTIVE_THRESH_GAUSSIAN_C
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY
import org.bytedeco.opencv.global.opencv_imgproc.INTER_CUBIC
import org.bytedeco.opencv.global.opencv_imgproc.INTER_LINEAR
import org.bytedeco.opencv.global.opencv_imgproc.THRESH_BINARY
import org.bytedeco.opencv.global.opencv_imgproc.THRESH_BINARY_INV
import org.bytedeco.opencv.global.opencv_imgproc.THRESH_OTSU
import org.bytedeco.opencv.global.opencv_photo
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Point2f
import org.bytedeco.opencv.opencv_core.Size
import java.awt.image.BufferedImage
import kotlin.math.abs

/**
 * Centralised OpenCV image-processing operations for DocEngine.
 *
 * Every public method accepts and returns [BufferedImage] so that callers
 * (PdfService, PdfRotationService, PDFProcessor, HandwritingPreprocessor)
 * do not depend on OpenCV types directly.
 *
 * Internally we convert to [Mat], run OpenCV calls, convert back, and
 * release the native [Mat] memory promptly.
 */
object OpenCvOps {

    // ── Thread-local converters (not thread-safe, so one per thread) ─────────

    private val frameConverter  = ThreadLocal.withInitial { OpenCVFrameConverter.ToMat() }
    private val java2dConverter = ThreadLocal.withInitial { Java2DFrameConverter() }

    // ─────────────────────────────────────────────────────────────────────────
    // BufferedImage ↔ Mat conversion
    // ─────────────────────────────────────────────────────────────────────────

    /** Convert a [BufferedImage] to an OpenCV [Mat]. Caller must [Mat.release] when done. */
    fun toMat(image: BufferedImage): Mat {
        val frame = java2dConverter.get().convert(image)
        return frameConverter.get().convert(frame)
    }

    /** Convert an OpenCV [Mat] back to a [BufferedImage]. */
    fun toBufferedImage(mat: Mat): BufferedImage {
        val frame = frameConverter.get().convert(mat)
        return java2dConverter.get().convert(frame)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Grayscale
    // ─────────────────────────────────────────────────────────────────────────

    /** Convert [image] to single-channel grayscale. Returns a new BufferedImage. */
    fun toGray(image: BufferedImage): BufferedImage {
        val src = toMat(image)
        val gray = Mat()
        try {
            if (src.channels() == 1) return image
            opencv_imgproc.cvtColor(src, gray, COLOR_BGR2GRAY)
            return toBufferedImage(gray)
        } finally {
            src.release()
            gray.release()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CLAHE — Contrast Limited Adaptive Histogram Equalization
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Applies CLAHE to a grayscale [image].
     *
     * Unlike a global histogram stretch, CLAHE operates on local tiles so
     * unevenly-lit scans, shadowed margins, and faded pencil marks are all
     * enhanced independently.
     *
     * @param clipLimit  contrast amplification limit (2.0 is a safe default)
     * @param tileSize   tile grid size for local histogram equalization
     */
    fun clahe(
        image: BufferedImage,
        clipLimit: Double = 2.0,
        tileSize: Int = 8
    ): BufferedImage {
        val src = toMat(image)
        val gray = Mat()
        val dst = Mat()
        try {
            if (src.channels() > 1) opencv_imgproc.cvtColor(src, gray, COLOR_BGR2GRAY) else src.copyTo(gray)
            val claheObj = opencv_imgproc.createCLAHE(clipLimit, Size(tileSize, tileSize))
            claheObj.apply(gray, dst)
            claheObj.close()
            return toBufferedImage(dst)
        } finally {
            src.release()
            gray.release()
            dst.release()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Adaptive Threshold (Gaussian)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Binarizes a grayscale [image] using Gaussian adaptive thresholding.
     *
     * @param blockSize  neighbourhood size (must be odd, e.g. 31)
     * @param C          constant subtracted from the weighted mean
     */
    fun adaptiveThreshold(
        image: BufferedImage,
        blockSize: Int = 31,
        C: Double = 15.0
    ): BufferedImage {
        val src = toMat(image)
        val gray = Mat()
        val dst = Mat()
        try {
            if (src.channels() > 1) opencv_imgproc.cvtColor(src, gray, COLOR_BGR2GRAY) else src.copyTo(gray)
            opencv_imgproc.adaptiveThreshold(
                gray, dst, 255.0,
                ADAPTIVE_THRESH_GAUSSIAN_C, THRESH_BINARY,
                blockSize, C
            )
            return toBufferedImage(dst)
        } finally {
            src.release()
            gray.release()
            dst.release()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Otsu Global Threshold
    // ─────────────────────────────────────────────────────────────────────────

    /** Binarizes a grayscale [image] using Otsu's global thresholding. */
    fun otsuThreshold(image: BufferedImage): BufferedImage {
        val src = toMat(image)
        val gray = Mat()
        val dst = Mat()
        try {
            if (src.channels() > 1) opencv_imgproc.cvtColor(src, gray, COLOR_BGR2GRAY) else src.copyTo(gray)
            opencv_imgproc.threshold(gray, dst, 0.0, 255.0, THRESH_BINARY or THRESH_OTSU)
            return toBufferedImage(dst)
        } finally {
            src.release()
            gray.release()
            dst.release()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Denoise
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Removes noise from a grayscale [image] using Non-Local Means Denoising.
     *
     * Effective against scanner noise, JPEG artifacts, and paper texture
     * without blurring text edges.
     *
     * @param h               filter strength (higher = more smoothing, 10 is safe)
     * @param templateWindow  size of the template patch (must be odd)
     * @param searchWindow    size of the area to search for patches (must be odd)
     */
    fun denoise(
        image: BufferedImage,
        h: Float = 10f,
        templateWindow: Int = 7,
        searchWindow: Int = 21
    ): BufferedImage {
        val src = toMat(image)
        val gray = Mat()
        val dst = Mat()
        try {
            if (src.channels() > 1) opencv_imgproc.cvtColor(src, gray, COLOR_BGR2GRAY) else src.copyTo(gray)
            opencv_photo.fastNlMeansDenoising(gray, dst, h, templateWindow, searchWindow)
            return toBufferedImage(dst)
        } finally {
            src.release()
            gray.release()
            dst.release()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Deskew
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Detects and corrects small rotational skew (±15°) in a scanned [image].
     *
     * Uses Canny edge detection → Hough line detection → median angle →
     * affine warp to straighten.  Returns the original image unchanged if
     * the detected skew is < 0.3° (negligible).
     */
    fun deskew(image: BufferedImage): BufferedImage {
        val src = toMat(image)
        val gray = Mat()
        val binary = Mat()
        try {
            if (src.channels() > 1) opencv_imgproc.cvtColor(src, gray, COLOR_BGR2GRAY) else src.copyTo(gray)

            // Invert + Otsu so text becomes white on black (foreground for contours)
            opencv_imgproc.threshold(
                gray, binary, 0.0, 255.0,
                THRESH_BINARY_INV or THRESH_OTSU
            )

            // Find contours
            val contours = org.bytedeco.opencv.opencv_core.MatVector()
            val hierarchy = Mat()
            opencv_imgproc.findContours(
                binary, contours, hierarchy,
                opencv_imgproc.RETR_LIST,
                opencv_imgproc.CHAIN_APPROX_SIMPLE
            )

            // Collect angles from minimum-area bounding rectangles of significant contours
            val angles = mutableListOf<Double>()
            val minArea = gray.rows().toDouble() * gray.cols() * 0.0001
            for (i in 0 until contours.size()) {
                val contour = contours.get(i)
                val area = opencv_imgproc.contourArea(contour)
                if (area < minArea) continue

                val rect = opencv_imgproc.minAreaRect(contour)
                var angle = rect.angle().toDouble()
                // minAreaRect returns angles in [-90, 0); normalise so horizontal ≈ 0
                if (rect.size().width() < rect.size().height()) angle += 90.0
                if (abs(angle) < 15.0) angles.add(angle)
            }

            contours.close()
            hierarchy.release()

            if (angles.isEmpty()) return image

            // Median angle
            angles.sort()
            val median = angles[angles.size / 2]

            // Skip if skew is negligible
            if (abs(median) < 0.3) return image

            // Rotate to correct skew
            val center = Point2f(gray.cols() / 2f, gray.rows() / 2f)
            val rotMat = opencv_imgproc.getRotationMatrix2D(center, median, 1.0)
            val corrected = Mat()
            try {
                opencv_imgproc.warpAffine(src, corrected, rotMat, src.size(), INTER_LINEAR, BORDER_REPLICATE, null)
                return toBufferedImage(corrected)
            } finally {
                rotMat.release()
                corrected.release()
            }
        } finally {
            src.release()
            gray.release()
            binary.release()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Rotate by 90° increments (fast, no interpolation)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Rotates [image] by exactly [angleCcw] degrees counter-clockwise.
     * Only supports 90, 180, 270.  Returns the original for 0 or other values.
     */
    fun rotate90(image: BufferedImage, angleCcw: Int): BufferedImage {
        val code = when (angleCcw) {
            90   -> ROTATE_90_COUNTERCLOCKWISE
            180  -> ROTATE_180
            270  -> ROTATE_90_CLOCKWISE
            else -> return image
        }
        val src = toMat(image)
        val dst = Mat()
        try {
            org.bytedeco.opencv.global.opencv_core.rotate(src, dst, code)
            return toBufferedImage(dst)
        } finally {
            src.release()
            dst.release()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Resize (bicubic upscale)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resizes [image] to [newWidth] × [newHeight] using bicubic interpolation.
     */
    fun resize(image: BufferedImage, newWidth: Int, newHeight: Int): BufferedImage {
        val src = toMat(image)
        val dst = Mat()
        try {
            opencv_imgproc.resize(src, dst, Size(newWidth, newHeight), 0.0, 0.0, INTER_CUBIC)
            return toBufferedImage(dst)
        } finally {
            src.release()
            dst.release()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Full preprocessing pipeline for OCR
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Complete OCR preprocessing pipeline:
     *  1. Grayscale
     *  2. CLAHE contrast enhancement
     *  3. Denoise (optional)
     *  4. Adaptive / Otsu binarization
     *
     * @param useDenoise     enable non-local means denoising (slower but better on noisy scans)
     * @param useAdaptive    true = Gaussian adaptive threshold; false = Otsu global threshold
     */
    fun prepareForOcr(
        image: BufferedImage,
        useDenoise: Boolean = false,
        useAdaptive: Boolean = false
    ): BufferedImage {
        val src = toMat(image)
        val gray = Mat()
        val enhanced = Mat()
        val denoised = Mat()
        val binary = Mat()
        try {
            // 1. Grayscale
            if (src.channels() > 1) opencv_imgproc.cvtColor(src, gray, COLOR_BGR2GRAY) else src.copyTo(gray)

            // 2. CLAHE
            val claheObj = opencv_imgproc.createCLAHE(2.0, Size(8, 8))
            claheObj.apply(gray, enhanced)
            claheObj.close()

            // 3. Denoise (optional)
            val preBinary = if (useDenoise) {
                opencv_photo.fastNlMeansDenoising(enhanced, denoised, 10f, 7, 21)
                denoised
            } else {
                enhanced
            }

            // 4. Binarize
            if (useAdaptive) {
                opencv_imgproc.adaptiveThreshold(
                    preBinary, binary, 255.0,
                    ADAPTIVE_THRESH_GAUSSIAN_C, THRESH_BINARY,
                    31, 15.0
                )
            } else {
                opencv_imgproc.threshold(preBinary, binary, 0.0, 255.0, THRESH_BINARY or THRESH_OTSU)
            }

            return toBufferedImage(binary)
        } finally {
            src.release()
            gray.release()
            enhanced.release()
            denoised.release()
            binary.release()
        }
    }
}
