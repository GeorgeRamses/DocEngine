package nsf.georgeramsis.docengine

import org.bytedeco.javacv.Java2DFrameConverter
import org.bytedeco.javacv.OpenCVFrameConverter
import org.bytedeco.opencv.global.opencv_core.ROTATE_180
import org.bytedeco.opencv.global.opencv_core.ROTATE_90_CLOCKWISE
import org.bytedeco.opencv.global.opencv_core.ROTATE_90_COUNTERCLOCKWISE
import org.bytedeco.opencv.global.opencv_imgproc
import org.bytedeco.opencv.global.opencv_imgproc.ADAPTIVE_THRESH_GAUSSIAN_C
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY
import org.bytedeco.opencv.global.opencv_imgproc.THRESH_BINARY
import org.bytedeco.opencv.global.opencv_imgproc.THRESH_OTSU
import org.bytedeco.opencv.global.opencv_photo
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Size
import java.awt.image.BufferedImage

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
