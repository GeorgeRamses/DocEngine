package nsf.georgeramsis.docengine

import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import docengine.composeapp.generated.resources.Icon
import docengine.composeapp.generated.resources.Res
import ocr.NativeLibExtractor
import ocr.TessDataManager
import org.jetbrains.compose.resources.painterResource
import java.io.File

fun main() {
    // ── Guard: ensure java.io.tmpdir exists ──────────────────────────
    // Tess4j's LoadLibs.extractNativeResources() calls
    // Files.createTempDirectory("tess4j"), which writes into
    // java.io.tmpdir (%TEMP% on Windows).  If the user has emptied or
    // deleted that folder, the extraction fails and TessAPI class init
    // dies permanently.  Redirect to our own persistent temp folder.
    val appTmpDir = File(System.getProperty("user.home"), ".myapp/tmp")
    if (!appTmpDir.exists()) appTmpDir.mkdirs()
    System.setProperty("java.io.tmpdir", appTmpDir.absolutePath)

    // ── Pin JNA's own native-lib extraction (jnidispatch.dll) ────────
    // NOTE: the -Djna.tmpdir JVM arg in build.gradle.kts is the primary
    //       mechanism.  This System.setProperty is a safety net for
    //       IDE / debug runs where jvmArgs may not be applied.
    val jnaDir = File(System.getProperty("user.home"), ".myapp/jna")
    if (!jnaDir.exists()) jnaDir.mkdirs()
    System.setProperty("jna.tmpdir", jnaDir.absolutePath)

    System.setProperty("skiko.renderApi", "SOFTWARE")
    TessDataManager.tessDataPath

    // ── Extract native DLLs to a persistent directory ────────────────
    // Tess4j's own LoadLibs relies on ClassLoader.getResources() which
    // can fail in ProGuard'd releases (directory entries stripped).
    // Our extractor scans classpath JARs directly and sets
    // jna.library.path BEFORE any tess4j class is loaded.
    NativeLibExtractor.ensureNativeLibs()

    // ── Eager Tess4j init ────────────────────────────────────────────
    // Force LoadLibs + TessAPI static initialisation NOW, while we can
    // still report a clear error, instead of crashing deep inside a
    // coroutine later.
    try {
        Class.forName("net.sourceforge.tess4j.TessAPI")
    } catch (ex: Throwable) {
        System.err.println("╔══════════════════════════════════════════════╗")
        System.err.println("║  Tess4j native init failed.                 ║")
        System.err.println("║  Likely cause: %TEMP% was emptied/deleted.  ║")
        System.err.println("║  Restart the app — it should self-repair.   ║")
        System.err.println("╚══════════════════════════════════════════════╝")
        ex.printStackTrace()
    }

    application {
        Window(
            state = WindowState(position = WindowPosition.Aligned(Alignment.Center)),
            onCloseRequest = ::exitApplication,
            title = "DocEngine v${BuildConfig.VERSION}",
            icon = painterResource(Res.drawable.Icon)
        ) {
            App()
        }
    }
}
