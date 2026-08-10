package nsf.georgeramsis.docengine

import org.junit.Test
import java.io.File
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper

class TextLayerTest {
    @Test
    fun testTextLayer() {
        val pdfFile = File("C:/Users/George.ramsis/.gemini/antigravity/brain/0a34945b-093b-4348-ad6a-978abddd5e19/.user_uploaded/media_1786341461910.pdf")
        val doc = PDDocument.load(pdfFile)
        val stripper = PDFTextStripper()
        stripper.sortByPosition = true
        val text = stripper.getText(doc)
        println("TEXT LAYER LENGTH WITH SORT: ${text.length}")
        println(text.take(800))
        doc.close()
    }
}
