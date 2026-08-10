package nsf.georgeramsis.docengine

import org.junit.Test
import kotlin.test.assertEquals

class RegexTest {
    @Test
    fun testRegex() {
        val configPattern = "000000"
        val compiledPattern = configPattern.map { char ->
            when (char) {
                '0' -> "\\d"
                '?' -> "[A-Za-z0-9]"
                else -> Regex.escape(char.toString())
            }
        }.joinToString("")
        val numRegex = Regex("\\b$compiledPattern\\b")

        fun normalizeOcrDigits(s: String): String = s
            .replace(Regex("(?<=[0-9OolIBSZo\\-])O(?=[0-9OolIBSZo\\-])", RegexOption.IGNORE_CASE), "0")
            .replace(Regex("(?<=[0-9])l(?=[0-9])"), "1")
            .replace(Regex("(?<=[0-9])I(?=[0-9])"), "1")
            .replace(Regex("(?<=[0-9])B(?=[0-9])"), "8")
            .replace(Regex("(?<=[0-9])S(?=[0-9])"), "5")
            .replace(Regex("(?<=[0-9])Z(?=[0-9])"), "2")
            .replace(Regex("(?<=[0-9])[\\-—_](?=[0-9])"), "1")

        fun extractNum(s: String): String? {
            val norm = normalizeOcrDigits(s)
            println("Normalized: $norm")
            return numRegex.find(s)?.value ?: numRegex.find(norm)?.value
        }

        val result = extractNum("2-0274")
        println("Result: $result")
        assertEquals("210274", result)
    }
}
