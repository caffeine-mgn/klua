package pw.binom.lua

import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal, API-level-independent replacement for [java.lang.ref.Cleaner],
 * which is Android API 33+ only. Keeping our own implementation lets the
 * JVM/Android-shared wrappers auto-release native resources on real devices
 * running older Android versions.
 *
 * A single daemon thread drains a [ReferenceQueue] of [PhantomReference]s.
 * When a registered referent becomes phantom-reachable its action runs exactly
 * once. Calling [RefCleanable.clean] explicitly runs the action immediately and
 * cancels the automatic run.
 *
 * Contract (identical to [java.lang.ref.Cleaner]): the action MUST NOT capture
 * the referent, or the strong reference would keep it alive forever and the
 * action would never fire.
 */
internal object RefCleaner {
    private val queue = ReferenceQueue<Any>()

    // Strong references to the registered phantom references. Without this the
    // ActionReference (which is only otherwise reachable through the wrapper
    // being collected) could itself be collected before it is enqueued, and the
    // action would silently never run. Entries are dropped as soon as the
    // action fires or the cleanable is cleaned explicitly.
    private val live = ConcurrentHashMap.newKeySet<ActionReference>()

    init {
        Thread({
            while (true) {
                val ref = try {
                    queue.remove()
                } catch (_: InterruptedException) {
                    continue
                }
                if (ref is ActionReference) {
                    live.remove(ref)
                    ref.fire()
                }
            }
        }, "klua-ref-cleaner").apply {
            isDaemon = true
            start()
        }
    }

    fun register(referent: Any, action: Runnable): RefCleanable {
        val cleanable = RefCleanable(action)
        val ref = ActionReference(referent, queue, cleanable)
        cleanable.attach(ref) { live.remove(ref) }
        live.add(ref)
        return cleanable
    }

    private class ActionReference(
        referent: Any,
        queue: ReferenceQueue<Any>,
        private val cleanable: RefCleanable,
    ) : PhantomReference<Any>(referent, queue) {
        fun fire() {
            try {
                cleanable.runAction()
            } catch (_: Throwable) {
                // A failing cleanup action must never kill the shared cleaner
                // thread — the rest of the registrations still need to run.
            }
        }
    }
}

/**
 * Handle returned by [RefCleaner.register]. Mirrors
 * `java.lang.ref.Cleaner.Cleanable`.
 */
internal class RefCleanable(action: Runnable) {
    private val action = action
    private val cleaned = AtomicBoolean(false)
    private var reference: PhantomReference<*>? = null
    private var detach: (() -> Unit)? = null

    internal fun attach(reference: PhantomReference<*>, detach: () -> Unit) {
        this.reference = reference
        this.detach = detach
    }

    /** Runs the cleanup action immediately (at most once); a later automatic run is skipped. */
    fun clean() {
        if (cleaned.compareAndSet(false, true)) {
            reference?.clear()
            detach?.invoke()
            action.run()
        }
    }

    internal fun runAction() {
        if (cleaned.compareAndSet(false, true)) {
            detach?.invoke()
            action.run()
        }
    }
}
