package pw.binom.lua

object StdOut {
    internal var func: ((String) -> Unit)? = null
    fun info(txt: String) {
        func?.invoke(txt)
    }
}