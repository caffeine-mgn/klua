/*
 * klua execution limits: a custom Lua allocator (memory cap) plus a
 * LUA_MASKCOUNT hook (instruction cap, wall-clock deadline, cooperative
 * cancellation). Shared by the JNI (.so packed into the JVM jar / Android AAR)
 * and the Kotlin/Native (posix) backends.
 *
 * All limits are opt-in. A value of 0 (or a NULL deadline) means "unlimited",
 * in which case the corresponding check is skipped. The control block is a
 * plain C allocation; the embedder creates it with klua_control_new(), hands
 * it to klua_newstate() and frees it with klua_control_free() after
 * lua_close().
 */
#ifndef KLUA_LIMITS_H
#define KLUA_LIMITS_H

#ifdef __cplusplus
extern "C" {
#endif

/* Reasons reported by klua_control_reason(). */
#define KLUA_REASON_NONE 0
#define KLUA_REASON_INSTRUCTIONS 1
#define KLUA_REASON_TIMEOUT 2
#define KLUA_REASON_MEMORY 3
#define KLUA_REASON_CANCEL 4

typedef struct klua_control {
    long long max_memory;
    long long used_memory;
    long long max_instructions;
    long long instructions;
    long long deadline_us;
    volatile int cancel;
    volatile int reason;
} klua_control;

/*
 * Creates a control block. max_memory / max_instructions <= 0 and
 * timeout_us <= 0 mean "unlimited". Returns NULL on allocation failure.
 */
klua_control *klua_control_new(long long max_memory, long long max_instructions, long long timeout_us);

/* Frees a control block created by klua_control_new(). NULL is a no-op. */
void klua_control_free(klua_control *c);

/*
 * Arms the wall-clock deadline for the current execution as
 * now(CLOCK_MONOTONIC) + timeout_us; timeout_us <= 0 disarms it.
 */
void klua_control_set_timeout(klua_control *c, long long timeout_us);

/* Sets the cooperative cancellation flag (non-zero cancels the running code). */
void klua_control_set_cancel(klua_control *c, int cancel);

/*
 * Clears the per-call instruction counter, the cancellation flag and the last
 * reason. Configured limits (memory/instruction/deadline) are preserved.
 * Call before every top-level eval/call.
 */
void klua_control_reset(klua_control *c);

/* Last limit reason (one of KLUA_REASON_*), or KLUA_REASON_NONE. */
int klua_control_reason(klua_control *c);

/* Bytes currently accounted by the Lua allocator (best effort). */
long long klua_control_used_memory(klua_control *c);

/*
 * Creates a lua_State whose allocator enforces c->max_memory and which has the
 * limit hook installed (LUA_MASKCOUNT). The control block is reachable from
 * the hook through lua_getextraspace(L). Returns NULL on failure (including a
 * memory limit too small to even create the state); the reason is then
 * KLUA_REASON_MEMORY.
 */
struct lua_State *klua_newstate(klua_control *c);

#ifdef __cplusplus
}
#endif

#endif /* KLUA_LIMITS_H */
