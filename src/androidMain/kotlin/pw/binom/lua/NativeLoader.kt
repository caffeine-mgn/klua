package pw.binom.lua

internal actual fun loadNativeLibrary() {
    System.loadLibrary("klua")
}
