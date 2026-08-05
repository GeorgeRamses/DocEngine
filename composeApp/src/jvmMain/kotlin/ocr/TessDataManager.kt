package ocr

import java.io.File

object TessDataManager {

    private val REQUIRED_FILES = listOf("eng.traineddata", "osd.traineddata")

    val tessDataPath: String by lazy {
        extractTessData()
    }

    private fun extractTessData(): String {
        val tessBaseDir = File(
            System.getProperty("user.home"),
            ".myapp/tessdata"
        )
        if (!tessBaseDir.exists()) tessBaseDir.mkdirs()

        REQUIRED_FILES.forEach { fileName ->
            val target = File(tessBaseDir, fileName)
            if (!target.exists()) {
                val input = Thread.currentThread()
                    .contextClassLoader
                    .getResourceAsStream("tessdata/$fileName")
                    ?: error("tessdata/$fileName missing in resources")

                target.outputStream().use { out ->
                    input.use { it.copyTo(out) }
                }
            }
        }

        return tessBaseDir.absolutePath
    }
}
