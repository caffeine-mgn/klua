# Validation: Native/JNI safety (1-20)

Spot-read cited code in `src/jvmMain/kotlin/pw/binom/lua/*`, `src/jvmMain/c/klua_jni.c`, and `src/posixMain/kotlin/pw/binom/lua/*`. Findings #1, #2, #3, #6, #14 are the most consequential.

## F1 — JVM wrapper LuaContext escapes and becomes UAF after engine.close()
- verdict: TP
- severity: Error
- evidence: src/jvmMain/.../LuaContext.kt:18-31 (companion `wrap(statePtr)` via secondary ctor at lines 15-17). The wrapper's `statePtr` (line 4) is private and only nulled inside the wrapper's own `close()` (lines 111-119); the engine's `close()` nullifies the engine's LuaContext's statePtr but never touches the wrapper's.
- trace: `LuaNative.invokeCallback` (src/jvmMain/.../LuaNative.kt:120-137) creates a fresh wrapper; tables/functions/userdata returned to JVM retain it; Cleaner actions on those (src/jvmMain/.../LuaValue.kt:335-345 RefAction.run) call `LuaNative.unref(<freed ptr>, ...)` after the engine has been closed and the state's memory freed.

## F2 — JVM UserData.dispose() does not cancel its Cleaner → double luaL_unref
- verdict: TP
- severity: Error
- evidence: src/jvmMain/.../LuaValue.kt:60-67 (UserData.dispose) and `:28-34` (Cleaner registration `RefAction(ll, refId)`). Direct read.
- trace: dispose() calls `LuaNative.unref(statePtr, REGISTRY, refId)` but does not call `cleanable.clean()`. When wrapper becomes phantom-reachable, RefAction runs and calls unref again → corrupts registry freelist (self-loop). Comment block at lines 70-94 defines `UserDataAction` that would have done the right thing but is never wired in.

## F3 — JNI setIUservalue ignores v, pops stack top
- verdict: TP
- severity: Error
- evidence: src/jvmMain/c/klua_jni.c:677-681 — `(void)v; lua_setiuservalue(state, idx, 1);` — `v` discarded, `lua_setiuservalue` pops whatever is currently on top.
- trace: Argument is silently thrown away; the next user assuming `v` is the assigned value gets garbage.

## F4 — POSIX LuaEngine.close() empty
- verdict: TP
- severity: Error
- evidence: src/posixMain/.../LuaEngine.kt:22-24 — `actual override fun close() { }`. Direct read.
- trace: Cross-cited F11/C6/RB/Multiple.

## F5 — POSIX LuaContextRegistry single global slot → multi-engine corruption
- verdict: TP
- severity: Error
- evidence: src/posixMain/.../LuaContext.kt:37-55 (`private var current: Pair<LuaState, LuaContext>?`). Direct read.
- trace: Cross-confirmed 6+ times.

## F6 — POSIX CLOSURE_FUNCTION heuristic mis-strips userdata
- verdict: TP
- severity: Error
- evidence: src/posixMain/.../KotlinLuaFunction.kt:78-92 — `if (count > 0 && lua_type(state, 1) == LUA_TUSERDATA) ... else ...`. Direct read.
- trace: For `ObjectContainer.makeClosure { ud -> ... }` called as `closure(someUd, x)`, the heuristic treats `someUd` as self and drops it. Fix: split CLOSURE_FUNCTION into AC_CLOSURE_FUNCTION (always strips idx 1) and plain CLOSURE_FUNCTION (never does).

## F7 — JVM createACClosure retains two registry entries with no Kotlin unref path
- verdict: TP
- severity: Warning
- evidence: src/jvmMain/.../LuaEngine.kt:170-189. Direct read.
- trace: Two FunctionRef wrappers per closure, each adds one registry slot; only released when the wrappers GC — not on a deterministic path.

## F8 — POSIX pcallProcessing stack imbalance on multi-value errors
- verdict: TP
- severity: Warning
- evidence: src/posixMain/.../LuaEngine.kt:194-216 — multi-value error branch pops only the traceback string, leaves N original values.
- trace: `checkState` would surface this as IllegalStateException masking the original `LuaException`.

## F9 — JVM UserDataAction dead class
- verdict: TP
- severity: WeakWarning
- evidence: src/jvmMain/.../LuaValue.kt:70-94 (full class) vs line 31-33 (UserData registers RefAction). Direct read.
- trace: Cross-cited C11.

## F10 — POSIX removeClosure(FunctionValue) reports success without disposing non-LuaFunction upvalues
- verdict: TP
- severity: Warning
- evidence: src/posixMain/.../ObjectContainer.kt:54-63. Direct read.
- trace: Cross-cited R11/Multip#5.

## F11 — POSIX ObjectContainer.clear() casts all StableRefs to StableRef<LuaFunction>
- verdict: TP
- severity: Warning
- evidence: src/posixMain/.../ObjectContainer.kt:69-74 — `it.asStableRef<LuaFunction>().dispose()`. Direct read.
- trace: Cross-cited R22.

## F12 — JVM StaticRefs.dispose races between Kotlin Cleaner and C trampoline
- verdict: TP
- severity: Warning
- evidence: src/jvmMain/.../LuaValue.kt:81-94 + src/jvmMain/c/klua_jni.c:205-217. Direct read.
- trace: Three sites drop the same entry: UserDataAction, C-trampoline, createUserData inner dispose. StaticRefs.dispose is idempotent so safe today, but logic duplication.

## F13 — JVM invokeCallback `(1L shl 31) or 0` sentinel — bit-packing brittleness
- verdict: TP
- severity: Info
- evidence: src/jvmMain/.../LuaNative.kt:129-135 vs src/jvmMain/c/klua_jni.c:149-155. Direct read.
- trace: nresults.toLong() can exceed 2^31 → high bits dropped.

## F14 — JVM readValue TableRef ref branch stale-index risk
- verdict: TP (informational; couples to F1)
- severity: Info
- evidence: src/jvmMain/.../LuaContext.kt:79-85. Reviewer correctly notes this was the original 02de357 GC stress trigger.
- trace: Single-threaded discipline documented but not enforced; F1 fixes the cross-thread entry point.

## F15 — POSIX makeRef uses !! on lua_topointer
- verdict: TP
- severity: WeakWarning
- evidence: src/posixMain/.../LuaEngine.kt:84-93. Direct read.
- trace: NPE on legitimate Lua primitives (boolean/nil/number). Cross-cited C12.

## F16 — POSIX ObjectContainer.add dedups by equals
- verdict: TP
- severity: WeakWarning
- evidence: src/posixMain/.../ObjectContainer.kt:13-24. Direct read.
- trace: Cross-cited Multi #6 / L15.

## F17 — JVM NativeLoader non-atomic extract path
- verdict: TP
- severity: Warning
- evidence: src/jvmMain/.../NativeLoader.kt:14-23 + 51-67. Direct read.
- trace: Cross-cited Sec#1 / L20.

## F18 — JNI_OnLoad caches GlobalRefs but never releases
- verdict: TP
- severity: WeakWarning
- evidence: src/jvmMain/c/klua_jni.c:48-66 (cache code); no JNI_OnUnload present (the C file does not contain it).
- trace: Two GlobalRefs/JVM is harmless but a classloader-reload scenario would leak.

## F19 — JVM LuaNative.callbacks unbounded growth under Cleaner starvation
- verdict: TP
- severity: WeakWarning
- evidence: src/jvmMain/.../ObjectContainer.kt:23-37 + LuaNative.kt:99-114. Direct read.
- trace: BridgeCleaner action is best-effort; per commit 8a7374d race notes; cross-cited F8.

## F20 — JVM LightUserData(ptr=0L) primary ctor skips Cleaner
- verdict: TP
- severity: Info (reviewer agrees)
- evidence: src/jvmMain/.../LuaValue.kt:81-90. Direct read.
- trace: Defensive note only.

## VERDICT COUNTS
- TP: 20
- FP: 0
- NeedMoreData: 0
- By severity (TP only): Error 6, Warning 5, WeakWarning 7, Info 2

## TOP-RISK TP
- F1 (Error): JVM wrapper LuaContext escape → UAF after engine.close(). Direct-read verified: secondary ctor at LuaContext.kt:15-17 + `wrap` companion:18-31; engine close():111-119 only nullifies engine's own statePtr, not the wrapper's.
- F2 (Error): JVM UserData.dispose() double luaL_unref → registry freelist corruption (self-loop, aliasing).
- F3 (Error): JNI setIUservalue silently discards `v` and pops unrelated stack top.
- F4 (Error): POSIX LuaEngine.close() no-op — cross-confirmed 5+ reviewers.
- F5 (Error): POSIX LuaContextRegistry single-slot — cross-confirmed 6+ reviewers.
- F6 (Error): POSIX CLOSURE_FUNCTION heuristic mis-strips legitimate userdata → Silent StableRef leak when user passes a userdata as first arg to a plain closure.
