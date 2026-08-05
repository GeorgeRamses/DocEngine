package nsf.georgeramsis.docengine

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class CompletionStats(
    val total: Int,
    val success: Int,
    val skipped: Int,
    val elapsedSec: Long
)

class PdfViewModel {

    // ── UI STATE ──────────────────────────────────────────────────────────────

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress = _progress.asStateFlow()

    private val _completionStats = MutableStateFlow<CompletionStats?>(null)
    val completionStats = _completionStats.asStateFlow()

    val logs = mutableStateListOf<String>()

    private var job:     Job?        = null
    private var service: PdfService? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── ACTIONS ───────────────────────────────────────────────────────────────

    fun startScanning(files: List<File>, config: AppConfig) {
        if (_isScanning.value) return

        _isScanning.value = true
        _progress.value   = 0f
        _completionStats.value = null

        val startTime = System.currentTimeMillis()
        addLog("DocEngine ${BuildConfig.VERSION_FULL} started")
        addLog("")
        addLog("┌────────────────────────────────────────┐")
        addLog("  Starting · ${files.size} file(s) · ${config.mode.name}")
        addLog("└────────────────────────────────────────┘")

        service = PdfService()

        job = scope.launch(Dispatchers.IO) {
            var lastProgressUpdate = 0L

            try {
                service!!.processInput(
                    inputFiles  = files,
                    config      = config,
                    onProgress  = { newProgress ->
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 50 || newProgress == 1f) {
                            _progress.value = newProgress
                            lastProgressUpdate = now
                        }
                    },
                    onLog       = { msg ->
                        withContext(Dispatchers.Main) { addLog(msg) }
                    },
                    onComplete  = { success, skipped ->
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000
                        withContext(Dispatchers.Main) {
                            _completionStats.value = CompletionStats(
                                total      = files.size,
                                success    = success,
                                skipped    = skipped,
                                elapsedSec = elapsed
                            )
                        }
                    }
                )

                withContext(Dispatchers.Main) {
                    _progress.value = 1f
                    addLog(">>> Process completed successfully.")
                }

            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    addLog(">>> CRITICAL ERROR: ${e.message}")
                    e.printStackTrace()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _isScanning.value = false
                    service = null
                }
            }
        }

        job?.invokeOnCompletion { cause ->
            if (cause is CancellationException)
                scope.launch { addLog(">>> Operation cancelled.") }
        }
    }

    fun cancelScanning() {
        // Signal the Java-thread-pool services first (cooperative cancellation)
        service?.cancelAll()
        // Then cancel the coroutine job
        job?.cancel()
        _isScanning.value = false
        addLog(">>> Stopping…")
    }

    fun clearLogs() {
        logs.clear()
        logs.add("Ready.")
    }

    suspend fun exportLogs(lastDirectory: File?): String = withContext(Dispatchers.IO) {
        if (logs.isEmpty()) return@withContext "⚠️ No logs to export"
        try {
            val ts         = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val dir        = lastDirectory ?: File(System.getProperty("user.home"))
            val outputFile = File(dir, "execution_log_$ts.txt")

            outputFile.writeText(buildString {
                appendLine("=".repeat(60))
                appendLine("EXECUTION LOG")
                appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine("=".repeat(60))
                appendLine()
                logs.forEach { appendLine(it) }
                appendLine()
                appendLine("=".repeat(60))
                appendLine("End of Log")
            })
            "📄 Log exported to: ${outputFile.absolutePath}"
        } catch (e: Exception) {
            "❌ Failed to export log: ${e.message}"
        }
    }

    // ── PRIVATE HELPERS ───────────────────────────────────────────────────────

    private fun addLog(msg: String) {
        logs.add(msg)
        if (logs.size > 2000) logs.removeAt(0)
    }
}

