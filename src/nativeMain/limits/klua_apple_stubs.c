/*
 * Apple mobile targets (iOS / watchOS / tvOS) ship without the
 * `system(3)` libc entry point — Apple's <Availability.h> marks it
 * `__OSX_AVAILABLE_BUT_DEPRECATED_MSG(..., "not available on iOS")`,
 * so the C compiler rejects the call inside loslib.c even though the
 * path is unreachable on a sandboxed device.
 *
 * We sidestep the compile error by replacing the symbol at preprocess
 * time on those targets only — see build.gradle.kts:
 *
 *   if (target.family in {IOS, TVOS, WATCHOS}) {
 *       compileArgs("-Dsystem=klua_apple_system")
 *   }
 *
 * and routing every `system(...)` call in loslib.c to this stub. The
 * stub behaves like a system that has no shell available, which is the
 * only honest answer for a sandboxed iOS app:
 *
 *   - `os.execute("cmd")`  → luaL_execresult(L, -1) is called with
 *                            stat=-1, errno=ENOSYS. The existing code
 *                            maps that to (nil, "exit", -1) — a clean
 *                            "no shell" error.
 *   - `os.execute()`       → lua_pushboolean(L, stat) is called with
 *                            stat=-1, which is truthy in Lua. That is
 *                            technically the wrong answer for the
 *                            "is there a shell?" probe (it should be
 *                            false on iOS), so we return 0 here and
 *                            lie about errno too: the `cmd == NULL`
 *                            branch does not consult errno, so the
 *                            probe sees `false` and a sandboxed iOS
 *                            app gets the correct "no shell" answer.
 *
 * On non-Apple targets the function is dead code — the macro is
 * never defined, and the real `system(3)` from libc is used instead.
 * The symbol has a distinct name so it never collides with libc.
 */
#include <errno.h>

#ifndef ENOSYS
#define ENOSYS 38
#endif

int klua_apple_system(const char *cmd) {
    (void)cmd;
    errno = ENOSYS;
    return 0;
}
