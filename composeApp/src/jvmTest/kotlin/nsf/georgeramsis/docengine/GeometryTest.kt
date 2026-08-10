package nsf.georgeramsis.docengine

import org.junit.Test
import java.io.File
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class GeometryTest {

    @Test
    fun testGeometryOcr() = runBlocking {
        println("=== STARTING GEOMETRY TEST ===")
        val sourcePdf = File("C:/Users/George.ramsis/.gemini/antigravity/brain/0a34945b-093b-4348-ad6a-978abddd5e19/.user_uploaded/media_1786341461910.pdf")
        if (!sourcePdf.exists()) {
            println("PDF FILE DOES NOT EXIST")
            return@runBlocking
        }

        val testDir = File("build/tmp/testocr")
        testDir.mkdirs()
        val pdfFile = File(testDir, "test.pdf")
        Files.copy(sourcePdf.toPath(), pdfFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

        val config = AppConfig(
            delimiter = "WO",
            pattern = "000000",
            mode = ScanMode.RENAME_ONLY
        )
        
        val service = PdfService()
        
        service.processInput(
            inputFiles = listOf(pdfFile),
            config = config,
            onProgress = {},
            onLog = { println("LOG: $it") }
        )
    }
}
