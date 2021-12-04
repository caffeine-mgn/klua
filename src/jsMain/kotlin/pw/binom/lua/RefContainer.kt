package pw.binom.lua

object RefContainer {
    private class RefItem(val obj: JSWeakRef<Any>, val func: () -> Unit)

    private val refs = HashMap<Int, RefItem>()
    private val refs2 = ArrayList<RefItem>()

    fun push(any: Any, func: () -> Unit) {
        refs2 += RefItem(obj = JSWeakRef(any), func = func)
    }

    fun pushRef(ref: Int, obj: Any, func: () -> Unit) {
        if (ref in refs.keys) {
            return
        }
        refs[ref] = RefItem(
            obj = JSWeakRef(obj), func = func
        )
    }

    fun delete(ref: Int) {
        val r = refs.remove(ref) ?: return
        r.func()
    }

    fun cleanUp() {
        val it2 = refs.iterator()
        while (it2.hasNext()) {
            val element = it2.next()
            if (element.value.obj.value == null) {
                element.value.func()
                it2.remove()
            }
        }
        val it1=refs2.iterator()
        while (it1.hasNext()) {
            val element = it1.next()
            if (element.obj.value == null) {
                element.func()
                it1.remove()
            }
        }
    }
}

@JsName("WeakRef")
internal external class JSWeakRef<T : Any> {
    constructor(value: T)

    val value: T?
        @JsName("deref")
        get
}