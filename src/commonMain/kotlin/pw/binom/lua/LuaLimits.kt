package pw.binom.lua

import kotlin.time.Duration

/**
 * Optional execution limits for a [LuaEngine]. All limits are opt-in: the
 * default, [UNLIMITED], disables every check and preserves the historical
 * behaviour.
 *
 * Limits apply per top-level operation ([LuaEngine.eval], [LuaEngine.call]).
 * Lua's `pcall` is transparent to them — a limit that trips inside a protected
 * call still aborts the whole operation, so untrusted code cannot swallow its
 * own budget with `pcall`.
 */
data class LuaLimits(
    /**
     * Maximum number of Lua VM instructions executed per eval/call.
     * `null` means unlimited. Enforced by a `LUA_MASKCOUNT` hook (period
     * 10 000 instructions), so a limit smaller than the period may let a few
     * thousand extra instructions through before aborting.
     */
    val maxInstructions: Long? = null,

    /**
     * Maximum wall-clock time per eval/call, measured with a monotonic clock.
     * `null` means unlimited.
     */
    val timeout: Duration? = null,

    /**
     * Maximum number of bytes the Lua allocator may hold at once. `null` means
     * unlimited. The counter includes Lua's internal structures (stacks, tables,
     * string hashes), so leave headroom above the payload you expect to allocate.
     */
    val maxMemoryBytes: Long? = null,
) {
    companion object {
        /** No limits — the historical behaviour. */
        val UNLIMITED = LuaLimits()
    }
}

/** Which limit aborted a [LuaEngine] operation. */
enum class LuaLimitKind {
    INSTRUCTIONS,
    TIMEOUT,
    MEMORY,
    CANCELLED,
}

/**
 * Thrown by [LuaEngine.eval]/[LuaEngine.call] when a limit configured through
 * [LuaLimits] (or [LuaEngine.cancel]) aborts execution.
 */
class LuaLimitException(
    val kind: LuaLimitKind,
    message: String,
) : LuaException(message)

/** Mirrors the KLUA_REASON_* constants in `klua_limits.h`. */
internal object LuaLimitReason {
    const val NONE = 0
    const val INSTRUCTIONS = 1
    const val TIMEOUT = 2
    const val MEMORY = 3
    const val CANCEL = 4

    fun kindOf(reason: Int): LuaLimitKind? = when (reason) {
        INSTRUCTIONS -> LuaLimitKind.INSTRUCTIONS
        TIMEOUT -> LuaLimitKind.TIMEOUT
        MEMORY -> LuaLimitKind.MEMORY
        CANCEL -> LuaLimitKind.CANCELLED
        else -> null
    }

    fun message(kind: LuaLimitKind): String = when (kind) {
        LuaLimitKind.INSTRUCTIONS -> "Lua instruction limit exceeded"
        LuaLimitKind.TIMEOUT -> "Lua execution timeout exceeded"
        LuaLimitKind.MEMORY -> "Lua memory limit exceeded"
        LuaLimitKind.CANCELLED -> "Lua execution cancelled"
    }
}
