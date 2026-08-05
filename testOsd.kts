import net.sourceforge.tess4j.Tesseract
import java.io.File
import javax.imageio.ImageIO

fun test() {
    val t = Tesseract().apply {
        setDatapath(File(System.getProperty("user.home"), ".myapp/tessdata").absolutePath)
        setLanguage("osd")
        setPageSegMode(0)
    }
    val files = File("debug_images").listFiles()
    if (files == null || files.isEmpty()) {
        println("No debug images found")
        return
    }
    val imgFile = files[0]
    println("Testing on {imgFile.name}")
    try {
        val result = t.doOCR(imgFile)
        println("Result: result")
    } catch (e: Exception) {
        println("Exception: {e.message}")
        e.printStackTrace()
    }
}
test()
