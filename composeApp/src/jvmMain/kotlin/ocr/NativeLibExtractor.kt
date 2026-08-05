package ocr

import java.io.File
import java.net.JarURLConnection
import java.util.jar.JarFile

/**
 * Extracts Tess4j / Lept4j native DLLs from classpath JARs into a
 * persistent directory (`~/.myapp/native/<platform>/`).
 *
 * This is a safety net for the case where Tess4j's own `LoadLibs`
 * fails to extract native resources — typically because ProGuard
 * stripped directory entries or `%TEMP%` was emptied.
 *
 * Call [ensureNativeLibs] **before** any `net.sourceforge.tess4j.*`
 * class is loaded.  It sets `jna.library.path` so that JNA can find
 * the extracted DLLs.
 */
object NativeLibExtractor {

    /** Platform resource prefix used by JNA (e.g. "win32-x86-64"). */
    private val RESOURCE_PREFIX: String by lazy {
        val osName = System.getProperty("os.name", "").lowercase()
        val arch   = System.getProperty("os.arch", "").lowercase()
        val os = when {
            "win" in osName   -> "win32"
            "mac" in osName || "darwin" in osName -> "darwin"
            "linux" in osName -> "linux"
            else -> "unknown"
        }
        val bits = when {
            "amd64" in arch || "x86_64" in arch -> "x86-64"
            "aarch64" in arch                   -> "aarch64"
            "x86" in arch                       -> "x86"
            else -> arch
        }
        "$os-$bits"
    }

    private val nativeDir: File by lazy {
        File(System.getProperty("user.home"), ".myapp/native/$RESOURCE_PREFIX")
    }

    /**
     * Ensures that the native libraries for the current platform are
     * extracted to `~/.myapp/native/<platform>/` and that
     * `jna.library.path` includes that directory.
     *
     * This method is idempotent — on subsequent calls it only checks
     * whether the directory already contains a marker file.
     */
    fun ensureNativeLibs() {
        nativeDir.mkdirs()

        // If we've already extracted, just set the path and return.
        val markerFiles = nativeDir.listFiles { f -> f.extension == "dll" || f.extension == "so" || f.extension == "dylib" }
        if (markerFiles != null && markerFiles.size > 2) {
            prependLibraryPath(nativeDir.absolutePath)
            return
        }

        // Extract all native resources from classpath JARs
        val classLoader = Thread.currentThread().contextClassLoader
        var extracted = 0

        try {
            val resources = classLoader.getResources(RESOURCE_PREFIX)
            while (resources.hasMoreElements()) {
                val url = resources.nextElement()
                if (url.protocol == "jar") {
                    extracted += extractFromJar(url)
                } else if (url.protocol == "file") {
                    extracted += extractFromDir(File(url.toURI()))
                }
            }
        } catch (_: Exception) {
            // getResources() failed — try the fallback approach
        }

        // Fallback: scan all classpath JARs manually
        if (extracted == 0) {
            extracted = extractFromClasspath(classLoader)
        }

        if (extracted > 0) {
            prependLibraryPath(nativeDir.absolutePath)
        }
    }

    /** Extract native files from a JAR URL. */
    private fun extractFromJar(url: java.net.URL): Int {
        var count = 0
        try {
            val conn = url.openConnection() as JarURLConnection
            val jar  = conn.jarFile
            count = extractMatchingEntries(jar)
        } catch (_: Exception) { }
        return count
    }

    /** Extract native files from a directory on disk. */
    private fun extractFromDir(dir: File): Int {
        if (!dir.isDirectory) return 0
        var count = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile) {
                val target = File(nativeDir, file.name)
                if (!target.exists()) {
                    file.copyTo(target)
                    count++
                }
            }
        }
        return count
    }

    /** Scan all classpath entries for JARs containing the native prefix. */
    private fun extractFromClasspath(@Suppress("UNUSED_PARAMETER") classLoader: ClassLoader): Int {
        var count = 0
        val classpath = System.getProperty("java.class.path", "")
        if (classpath.isBlank()) return 0

        classpath.split(File.pathSeparator).forEach { entry ->
            val file = File(entry)
            if (file.isFile && file.extension == "jar") {
                try {
                    JarFile(file).use { jar ->
                        count += extractMatchingEntries(jar)
                    }
                } catch (_: Exception) { }
            }
        }
        return count
    }

    /** Extract entries matching the platform prefix from a JarFile. */
    private fun extractMatchingEntries(jar: JarFile): Int {
        var count = 0
        val prefix = "$RESOURCE_PREFIX/"
        for (entry in jar.entries()) {
            if (entry.name.startsWith(prefix) && !entry.isDirectory) {
                val fileName = entry.name.substringAfterLast("/")
                if (fileName.isNotBlank()) {
                    val target = File(nativeDir, fileName)
                    if (!target.exists()) {
                        try {
                            jar.getInputStream(entry).use { input ->
                                target.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            count++
                        } catch (_: Exception) { }
                    }
                }
            }
        }
        return count
    }

    /** Prepend a directory to `jna.library.path` if not already present. */
    private fun prependLibraryPath(dir: String) {
        val current = System.getProperty("jna.library.path", "")
        if (current.contains(dir)) return
        val newPath = if (current.isEmpty()) dir else "$dir${File.pathSeparator}$current"
        System.setProperty("jna.library.path", newPath)
    }
}


