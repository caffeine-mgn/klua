package pw.binom.lua

import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

internal object NativeLoader {

    private const val VERSION = "1.0.1-debug"
    private val loaded = ConcurrentHashMap.newKeySet<String>()

    fun load() {
        val target = currentResource()
        val libFileName = currentLibFileName()
        val cached = extractedFile(libFileName)

        if (!cached.exists()) {
            extractFromJar(target, libFileName, cached)
        }
        if (loaded.add(libFileName)) {
            System.load(cached.absolutePath)
        }
    }

    private fun currentResource(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            os.contains("linux") && (arch.contains("amd64") || arch == "x86_64") -> "linux_x64"
            os.contains("linux") && (arch.contains("aarch64") || arch == "arm64") -> "linux_arm64"
            os.contains("mac") && (arch.contains("amd64") || arch == "x86_64") -> "macos_x64"
            os.contains("mac") && (arch.contains("aarch64") || arch == "arm64") -> "macos_arm64"
            os.contains("windows") && (arch.contains("amd64") || arch == "x86_64") -> "mingw_x64"
            else -> error("Unsupported OS/arch: $os / $arch")
        }
    }

    private fun currentLibFileName(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("windows") -> "klua.dll"
            os.contains("mac") -> "klua.dylib"
            else -> "libklua.so"
        }
    }

    private fun extractedFile(libFileName: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "jlua/$VERSION")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, libFileName)
    }

    private fun extractFromJar(target: String, libFileName: String, targetFile: File) {
        targetFile.parentFile?.mkdirs()
        val zipPath = "$target/$libFileName"
        val stream = NativeLoader::class.java.getResourceAsStream("/$zipPath")
            ?: error("Native library not found in jar at $zipPath")

        stream.use { input ->
            FileOutputStream(targetFile).use { out ->
                input.copyTo(out)
            }
        }

        if (!targetFile.exists()) {
            error("Failed to extract native library from jar at $zipPath")
        }
    }
}
