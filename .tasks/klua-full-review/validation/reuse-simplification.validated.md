# Validation: Reuse & Simplification (R1-R24)

Reads of cited locations in `src/commonMain`, `src/jvmMain`, `src/posixMain`. Particularly checked R5 (the pcall bug with multiple reviewers) and R12 (confirmed dead-branch) against actual code.

## R1 — closureAutoGcFunction / userdataAutoGcFunction duplicate on POSIX
- verdict: TP
- severity: Warning
- evidence: src/posixMain/.../LuaEngine.kt:17-21 — `actual val closureAutoGcFunction = makeRef(LuaValue.FunctionValue(userdataGc))` and identical `actual val userdataAutoGcFunction = ...` (same expression). Direct read.
- trace: Two `luaL_ref` slots for the same cfunction. JVM genuinely has two distinct trampolines (src/jvmMain/.../LuaEngine.kt:11-23). Cross-cited Multi #9.

## R2 — LuaCtx / LuaStateAndLib dead duplicate
- verdict: TP
- severity: Warning
- evidence: src/posixMain/.../LuaCtx.kt:10-14, LuaStateAndLib.kt:6. Searches across scope confirm zero callers.
- trace: Dead code; cross-cited Multi "Notes".

## R3 — KotlinLuaFunctions.kt empty
- verdict: TP
- severity: Warning
- evidence: src/posixMain/.../KotlinLuaFunctions.kt:1-7. Direct read confirms only @file:OptIn + import.
- trace: Delete.

## R4 — InputVarargs / OutputVarargs unused interfaces
- verdict: TP
- severity: Warning
- evidence: src/commonMain/.../OutputVarargs.kt:3-5 + InputVarargs.kt:3-6. Direct read; no implementers anywhere in scope.
- trace: Dead surface.

## R5 — JVM has two divergent pcall result handlers
- verdict: TP
- severity: Error
- evidence: src/jvmMain/.../LuaEngine.kt:213-236 (`pcallProcessing`, statuses 2/4/5/else) and src/jvmMain/.../LuaValue.kt:432-459 (`pcallCall`, statuses 0/4/5/6/else). Direct-read confirmed.
- trace: `pcallCall` (called from `TableRef.call`, `FunctionRef.call`, `UserData.call` at src/jvmMain/.../LuaValue.kt:56,269,310) treats LUA_ERRRUN=2 as "Unknown pcall status: 2" → throws RuntimeException, dropping `LuaException` contract. Cross-cited 5 times (C1/Sec#3/L5/L8/M issue2). Confirmed via JNI source mention (status 2 = LUA_ERRRUN per lua.h).

## R6 — LuaNativeType constants defined but unused
- verdict: TP
- severity: WeakWarning
- evidence: src/jvmMain/.../LuaNative.kt:166-177 (definition) vs LuaContext.kt:62-110 (literals).
- trace: Dead constants; integrate.

## R7 — Six near-identical forEachIndexed of(table) overloads
- verdict: TP
- severity: Warning
- evidence: src/jvmMain/.../LuaValue.kt:443-462 and src/posixMain/.../LuaValue.kt:391-412. Direct read confirmed.
- trace: Collapse to one helper per target.

## R8 — setAC body copy-pasted between JVM and POSIX
- verdict: TP
- severity: WeakWarning
- evidence: src/jvmMain/.../LuaEngine.kt:200-208 vs src/posixMain/.../LuaEngine.kt:175-184. Identical body.
- trace: Forced duplication by expect/actual mechanism.

## R9 — createAC vs createUserData redundancy
- verdict: TP
- severity: Warning
- evidence: src/posixMain/.../LuaEngine.kt:186-194, 198-205; src/jvmMain/.../LuaEngine.kt:210-213, 215-220. Direct read.
- trace: Two paths to same outcome; potential metatable thrash.

## R10 — Two parallel monotonic counters on JVM
- verdict: TP
- severity: WeakWarning
- evidence: src/jvmMain/.../LuaNative.kt:14 (`nextCallbackId: AtomicInteger`) vs ObjectContainer.kt:104 (`nextClosureId`). Direct read.
- trace: POSIX has no equivalent.

## R11 — POSIX removeClosure(FunctionValue) always-true
- verdict: TP
- severity: Warning
- evidence: src/posixMain/.../ObjectContainer.kt:49-57 — final `return true` reachable when upvalue is not LightUserData<LuaFunction>. Direct read.
- trace: Cross-cited Multi #5.

## R12 — Dead `is LuaValue.Nil` in LuaValueWriter
- verdict: TP
- severity: WeakWarning
- evidence: src/posixMain/.../LuaValueWriter.kt:10-11 — `LuaValue.Nil,\nis LuaValue.Nil -> lua_pushnil(state)`. Direct read.
- trace: `LuaValue.Nil` is an `object`; `when` covers the value already.

## R13 — StdOut vs println inconsistency
- verdict: TP
- severity: WeakWarning
- evidence: src/jvmMain/.../DebugTool.kt:5-8 (`println`) vs src/posixMain/.../DebugTool.kt:7-37 (`StdOut.info`).
- trace: StdOut redirection bypassed on JVM.

## R14 — DebugTool printStack deprecated lifecycle
- verdict: TP
- severity: WeakWarning
- evidence: src/commonMain/.../DebugTool.kt:5-8 + jvmMain/posixMain actuals. All `@Deprecated`. Direct read.
- trace: Housekeeping.

## R15 — Magic Lua status / type integers on JVM
- verdict: TP
- severity: WeakWarning
- evidence: src/jvmMain/.../LuaContext.kt:62-110 (raw -1, 0, 1..7) vs POSIX counterparts using LUA_OK / LUA_TNIL etc.
- trace: Promote a commonMain constants file.

## R16 — takeIfNotNil stdlib rewrite candidate
- verdict: TP
- severity: Info
- evidence: src/commonMain/.../LuaValueExtends.kt:95-96.
- trace: Marginal.

## R17 — TableValue.toList / TableRef.toList copy-paste
- verdict: TP
- severity: Info
- evidence: src/jvmMain/.../LuaValue.kt:413, 433 vs src/posixMain/.../LuaValue.kt:373, 411. Direct read.
- trace: A commonMain extension captures both.

## R18 — verbose TableValue(...) metatable construction
- verdict: TP
- severity: Info
- evidence: src/jvmMain/.../LuaEngine.kt:131, 136, 168, 181, 201, 217 and POSIX:144, 161, 181. Direct read.
- trace: A `meta(...)` factory hides intent.

## R19 — commented println / TODO() leftovers
- verdict: TP
- severity: WeakWarning
- evidence: src/posixMain/.../LuaValueReader.kt:8-10, 111 + LuaValueWriter.kt:41-44. Direct read.
- trace: Cleanup.

## R20 — LuaValue.RefObject.callToString magic pop 2
- verdict: TP
- severity: WeakWarning
- evidence: src/posixMain/.../LuaValue.kt:424-432.
- trace: Use checkState or a try/finally.

## R21 — Heap.setPtrFromPtr param name `value` shadowing
- verdict: TP
- severity: Info
- evidence: src/posixMain/.../Lua.kt:28-31 + caller src/posixMain/.../LuaEngine.kt:138. Direct read.
- trace: Rename param to drop the `value =` named-arg workaround.

## R22 — POSIX ObjectContainer.clear() wrong-cast cross-cut
- verdict: TP
- severity: Warning
- evidence: src/posixMain/.../ObjectContainer.kt:65-71 — `it.asStableRef<LuaFunction>().dispose()` over arbitrary Any values stored via add().
- trace: Currently safe by accident (StableRef ignores witness type), but fragile if K/N runtime starts checking.

## R23 — Closure factory convergence JVM vs POSIX
- verdict: TP
- severity: Info
- evidence: src/jvmMain/.../ObjectContainer.kt:22-50 + LuaEngine.kt:155-181.
- trace: JVM has two near-identical bridge paths; POSIX converged via CLOSURE_FUNCTION.

## R24 — klua_jni.c pcall returns raw status
- verdict: TP
- severity: Info
- evidence: src/jvmMain/c/klua_jni.c:570-572 (return raw lua_pcallk) — restated.
- trace: See R5/R15.

## VERDICT COUNTS
- TP: 24
- FP: 0
- NeedMoreData: 0
- By severity (TP only): Error 1, Warning 8, WeakWarning 9, Info 6

## TOP-RISK TP
- R5 (Error): Two divergent pcall result handlers, runtime errors become `RuntimeException("Unknown pcall status: 2")` instead of `LuaException`. Direct-read verified; cross-confirmed by 4+ reviewers.
