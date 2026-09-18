# Validation: Cross-file tracer (1-14)

Spot-checks against cited locations in `src/commonMain`, `src/jvmMain`, `src/posixMain`, `src/jvmMain/c/klua_jni.c`. Findings map directly to consensus bugs found by other reviewers.

## F1 — JVM pcallCall mis-maps Lua 5.4 error codes
- verdict: TP
- severity: Error
- evidence: src/jvmMain/.../LuaValue.kt:440-457 — `when (r)` cases 4/5/6/else. Lua 5.4 status codes LUA_OK=0, LUA_ERRRUN=2, LUA_ERRMEM=4, LUA_ERRERR=5 per `src/nativeMain/lua/lua.h:51-55`. JNI returns raw code at src/jvmMain/c/klua_jni.c:570-572.
- trace: See L5 / R5 / Sec#3 — same fundamental bug, six reviewers confirm.

## F2 — POSIX LuaEngine.close() is a contract no-op
- verdict: TP
- severity: Error
- evidence: src/posixMain/.../LuaEngine.kt:17 (empty body) vs src/jvmMain/.../LuaEngine.kt:36-38 (`actual override fun close() { ll.close() }`). Direct read.
- trace: 5+ reviewer consensus.

## F3 — POSIX call(functionName,...) leaks stack on nil-branch
- verdict: TP
- severity: Error
- evidence: src/posixMain/.../LuaEngine.kt:55-69 — `if (lua_isnil1(ll.state, -1)) throw LuaException(...)` no lua_pop before throw. JVM counterpart at src/jvmMain/.../LuaEngine.kt:71-83 pops correctly.
- trace: Same as M#3 / L10.

## F4 — POSIX createUserData(LightUserData) installs no __gc
- verdict: TP
- severity: Error
- evidence: src/posixMain/.../LuaEngine.kt:107-114 — only `lua_newuserdata1` + `Heap.setPtrFromPtr(mem, value.lightPtr)`. No `metatable` set; no `value.dispose()` to drop original StableRef.
- trace: JVM counterpart at src/jvmMain/.../LuaEngine.kt:121-135 correctly installs `__gc` + disposes `value.ptr`.

## F5 — userdataAutoGcFunction dead-on-arrival on POSIX
- verdict: TP
- severity: Warning
- evidence: src/posixMain/.../LuaEngine.kt:12-14 vs JVM at src/jvmMain/.../LuaEngine.kt:7-8,17-29. POSIX uses identical `userdataGc` cfunction for both names. Verified at src/posixMain/.../KotlinLuaFunction.kt:24-41.
- trace: Confirmed via direct read. Both POSIX code paths happen to put `StableRef<Any>` at the userdata payload today, so this is silent today.

## F6 — POSIX LuaContextRegistry single-slot not unregistered on close()
- verdict: TP
- severity: Warning (under-rated; crosses with F2/F11 to make it Error)
- evidence: src/posixMain/.../LuaContext.kt:38-55 + LuaEngine.kt:17 — close() body empty does not call registry.unregister.
- trace: Cross-cited F11/C6/Sec#6/RB#4/Nat#5/M#6/L7. The combined close+registry bug is Error; this finding is the registry aspect.

## F7 — Locking discipline inconsistent
- verdict: TP
- severity: Warning
- evidence: src/jvmMain/.../StaticRefs.kt:13-44 (synchronized map) vs src/posixMain/.../ObjectContainer.kt:9-72 (bare HashMap). Direct read confirms.
- trace: Differences in thread-safety contract.

## F8 — POSIX UserData.dispose() / LightUserData.dispose() not idempotent
- verdict: TP
- severity: Warning
- evidence: src/jvmMain/.../LuaValue.kt:99-105, 121-129 (jvm short-circuits on null ptr) vs src/posixMain/.../LuaValue.kt:57-67, 96-100 (posix calls dispose unconditionally).
- trace: Second call on POSIX: "illegal state: stable ref is already disposed".

## F9 — Data.dispose() only on POSIX
- verdict: TP
- severity: WeakWarning
- evidence: src/commonMain/.../LuaValue.kt:14-17 (no dispose); src/posixMain/.../LuaValue.kt:13-15 (actual interface has dispose member).
- trace: Parity-blind surface.

## F10 — reserveCallbackId Kotlin external declaration mismatches JNI symbol
- verdict: TP
- severity: Warning
- evidence: src/jvmMain/.../LuaNative.kt:40 (`external fun reserveCallbackId`) vs src/jvmMain/c/klua_jni.c:413-418 (exports `Java_pw_binom_lua_LuaNative_registerCallback` not `reserveCallbackId`). No Kotlin caller; cross-cited.
- trace: Future call would throw UnsatisfiedLinkError.

## F11 — Dead UserDataAction Cleaner class on JVM
- verdict: TP
- severity: WeakWarning
- evidence: src/jvmMain/.../LuaValue.kt:70-99 (full class) vs line 31-33 (UserData registers RefAction instead). Direct read.
- trace: Cross-cited Nat #9.

## F12 — POSIX makeRef uses !! on lua_topointer
- verdict: TP
- severity: WeakWarning
- evidence: src/posixMain/.../LuaEngine.kt:84-101 — `lua_topointer(...)!!` in both makeRef overloads. Direct read.
- trace: NPE on values without internal pointer (booleans, nil, etc.).

## F13 — JVM makeUserdataGcRef / makeAutoGcRef unused internally
- verdict: TP
- severity: Info
- evidence: src/jvmMain/.../LuaEngine.kt:10-25 — both helpers defined, but `userdataAutoGcFunction`'s POSIX twin is `closureAutoGcFunction` (see F5), so the second pass would crash if invoked.
- trace: Couples to F5.

## F14 — pushCClosure ignores `n` upvalue count
- verdict: TP
- severity: Info
- evidence: src/jvmMain/.../LuaNative.kt:31 (Kotlin declaration passes `n`) vs src/jvmMain/c/klua_jni.c:376-384 (JNI pushes a single integer, ignores `n`). Direct read confirms.
- trace: API drift; future multi-upvalue usage would silently truncate.

## VERDICT COUNTS
- TP: 14
- FP: 0
- NeedMoreData: 0
- By severity (TP only): Error 4, Warning 5, WeakWarning 3, Info 2

## TOP-RISK TP
- F1 (Error): pcallCall mis-maps Lua 5.4 error codes — cross-confirmed 5+ times.
- F2 (Error): POSIX `LuaEngine.close()` no-op — cross-confirmed 5+ times.
- F3 (Error): POSIX call(functionName,...) stack leak — cross-cited M#3 / L10.
- F4 (Error): POSIX createUserData(LightUserData) installs no `__gc` → StableRef leak under user-input path.
