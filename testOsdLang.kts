import net.sourceforge.tess4j.Tesseract
import java.io.File
import javax.imageio.ImageIO

fun test() {
    val t = Tesseract().apply {
        setDatapath("D:/George/Projects/Kotlin/DocEngine/tessdata")
        setPageSegMode(0)
    }
    // We would doOCR here
    println("Created Tesseract with PSM 0 but lang = eng")
}
test()
