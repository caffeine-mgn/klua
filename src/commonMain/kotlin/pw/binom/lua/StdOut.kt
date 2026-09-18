package pw.binom.lua

object StdOut {
    // The default sink forwards to platform println so messages are not
    // silently dropped before the embedder installs a custom func. The
    // `internal var` is mutable so production code can swap it for a
    // logger; cross-thread visibility is JVM-only (Volatile), POSIX-side
    // accepts single-threaded usage which matches callback dispatch.
    internal var func: ((String) -> Unit)? = { println(it) }
    fun info(txt: String) {
        // Call through the captured property so swapping func after a
        // previous call picks up the new sink on the next message.
        func?.invoke(txt)
    }
}
