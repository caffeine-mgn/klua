package pw.binom.lua

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal object NativeLoader {

    private const val VERSION = "1.2.0-debug"
    private val loaded = ConcurrentHashMap.newKeySet<String>()
    // Per-target extraction lock so concurrent load() calls don't race the
    // extract step. The first System.load must run exactly once.
    private val extractLocks = ConcurrentHashMap<String, ReentrantLock>()

    fun load() {
        val target = currentResource()
        val libFileName = currentLibFileName()
        val cached = extractedFile(libFileName)
        val lock = extractLocks.computeIfAbsent(libFileName) { ReentrantLock() }
        lock.withLock {
            extractIfNeeded(target, libFileName, cached)
            if (loaded.add(libFileName)) {
                System.load(cached.toAbsolutePath().toString())
            }
        }
    }

    /**
     * Compute SHA-256 of the bytes inside the jar at runtime and compare to
     * the on-disk file. Re-extract if missing or hashed incorrectly. This
     * closes the CWE-426 vector where an attacker pre-plants a malicious .so
     * at the predictable `/tmp/jlua/<version>` path before the victim JVM
     * starts: the hash won't match, the file is regenerated from the jar
     * contents the attacker cannot tamper with.
     */
    private fun extractIfNeeded(target: String, libFileName: String, cached: Path) {
        val inJarHash = readInJarSha256(target, libFileName)
        if (Files.exists(cached) && inJarHash.contentEquals(sha256OfFile(cached))) {
            // Already extracted correctly. Refuse to load if permissions
            // have been widened by something else.
            if (isSecurelyOwned(cached)) return
        }
        // Make sure cache dir exists with owner-only perms (POSIX).
        Files.createDirectories(cached.parent, *posixOwnerOnlyAttrs())
        val temp = Files.createTempFile(cached.parent, "klua-", ".tmp")
        try {
            writeFromJar(target, libFileName, temp)
            val writtenHash = sha256OfFile(temp)
            if (!inJarHash.contentEquals(writtenHash)) {
                error("Native library integrity check failed after extract")
            }
            tightenPermissions(temp)
            Files.move(
                temp,
                cached,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun readInJarSha256(target: String, libFileName: String): ByteArray {
        val zipPath = "/$target/$libFileName"
        val stream = NativeLoader::class.java.getResourceAsStream(zipPath)
            ?: error("Native library not found in jar at $zipPath")
        return stream.use { sha256OfStream(it) }
    }

    private fun writeFromJar(target: String, libFileName: String, targetFile: Path) {
        val zipPath = "/$target/$libFileName"
        val stream = NativeLoader::class.java.getResourceAsStream(zipPath)
            ?: error("Native library not found in jar at $zipPath")
        stream.use { input -> Files.newOutputStream(targetFile).use { input.copyTo(it) } }
    }

    private fun sha256OfFile(path: Path): ByteArray = Files.newInputStream(path).use { sha256OfStream(it) }

    private fun sha256OfStream(input: InputStream): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var n = input.read(buf)
        while (n >= 0) {
            if (n > 0) digest.update(buf, 0, n)
            n = input.read(buf)
        }
        return digest.digest()
    }

    private fun tightenPermissions(file: Path) {
        // Make the binary owner-rwx by default; POSIX additionally restrict
        // group/other perms to nothing.
        try {
            val f = file.toFile()
            f.setReadable(true, true)
            f.setWritable(true, true)
            f.setExecutable(true, true)
        } catch (_: SecurityException) { /* best-effort */ }
        try {
            val updated = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
            Files.setPosixFilePermissions(file, updated)
        } catch (_: UnsupportedOperationException) { /* not POSIX */ }
    }

    private fun isSecurelyOwned(file: Path): Boolean = try {
        val perms = Files.getPosixFilePermissions(file)
        val allowed = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        perms.all { it in allowed }
    } catch (_: UnsupportedOperationException) {
        // Non-POSIX FS — best-effort, accept.
        true
    } catch (_: java.io.IOException) {
        false
    }

    private fun posixOwnerOnlyAttrs(): Array<FileAttribute<*>> = try {
        arrayOf(
            PosixFilePermissions.asFileAttribute(
                EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            ),
        )
    } catch (_: UnsupportedOperationException) {
        emptyArray()
    }

    private fun currentResource(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            os.contains("linux") && (arch.contains("amd64") || arch == "x86_64") -> "linux_x64"
            os.contains("linux") && (arch.contains("aarch64") || arch.contains("arm64")) -> "linux_arm64"
            os.contains("mac") && (arch.contains("amd64") || arch == "x86_64") -> "macos_x64"
            os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm64")) -> "macos_arm64"
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

    /**
     * Per-user cache directory. Using `${user.home}/.cache/klua` on POSIX,
     * `${user.home}/klua` on Windows, with a version-keyed subdir.
     *
     * This replaces the previous predictable `${java.io.tmpdir}/jlua/<version>`
     * path which was a shared, world-readable location and a CWE-426 vector.
     */
    private fun extractedFile(libFileName: String): Path {
        val base = System.getProperty("user.home") ?: System.getProperty("java.io.tmpdir")
        val os = System.getProperty("os.name").lowercase()
        val subdir = if (os.contains("windows")) "klua" else ".cache/klua"
        return Path.of(base, subdir, VERSION, libFileName)
    }
}
