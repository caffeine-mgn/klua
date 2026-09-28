package pw.binom.lua

/**
 * Loads the bundled `libklua` shared library into the current process.
 *
 * Desktop JVM unpacks the platform `.so`/`.dylib`/`.dll` from the jar and
 * loads it from a per-user cache ([NativeLoader]); Android loads the
 * ABI-matched `libklua.so` packaged in the AAR's `jniLibs` through the system
 * linker by its logical name.
 */
internal expect fun loadNativeLibrary()
