# Validation: Removed-behavior (Issue 1-15)

Read cited locations in `src/commonMain`, `src/jvmMain`, `src/posixMain`. Each finding cross-checked against the relevant Native/JNI or Cross-file reviewer where overlapping.

## Issue 1 — JVM setAC installs wrong __gc handler (StaticRefs leak)
- verdict: TP
- severity: Error
- evidence: src/jvmMain/.../LuaEngine.kt:170-177 — setAC overwrites `__gc` with `closureAutoGcFunction` (callback-id-dispose trampoline with a never-registered id).
- trace: Cross-confirmed by Line-by-line L2.

## Issue 2 — JVM ObjectContainer.removeClosure(FunctionRef) hardcoded false
- verdict: TP
- severity: Error (reviewer's Error is correct; compiler actually accepts the stub, so the leak is silent)
- evidence: src/jvmMain/.../ObjectContainer.kt:58 — `actual fun removeClosure(data: LuaValue.FunctionRef): Boolean = false`. Direct read.
- trace: Cross-confirmed by Multi #4.

## Issue 3 — POSIX LuaEngine.close() body empty
- verdict: TP
- severity: Error
- evidence: src/posixMain/.../LuaEngine.kt:21-23 — `actual override fun close() { }`. Direct read.
- trace: Cross-confirmed 5+ reviewers.

## Issue 4 — LuaContextRegistry strongly retains active LuaContext
- verdict: TP
- severity: Error
- evidence: src/posixMain/.../LuaContext.kt:31-49 + LuaContext.kt:14-17 (Cleaner registered on `state`, which is owned by `ctx`). Direct read confirms retention cycle.
- trace: Active engine Cleaner never fires; multi-engine overwrites slot. Combines with Issue 3.

## Issue 5 — JVM ObjectContainer.clear() leaves bridge entries in LuaNative.callbacks
- verdict: TP
- severity: Warning
- evidence: src/jvmMain/.../ObjectContainer.kt:54-56 — `actual fun clear() { closures.clear() }`. BridgeCleaner at lines 73-83.
- trace: Cross-cited via Nat#19.

## Issue 6 — POSIX ObjectContainer.removeClosure(FunctionValue) does not purge maps
- verdict: TP
- severity: Warning
- evidence: src/posixMain/.../ObjectContainer.kt:45-53 — only disposes LightUserData's StableRef, never removes the maps.
- trace: Next add(sameData) gets stale LightUserData(ptr=disposed).

## Issue 7 — POSIX createACClosure stores same StableRef in two places
- verdict: TP
- severity: Error
- evidence: src/posixMain/.../LuaEngine.kt:130-153 — StableRef stored as `__call` upvalue AND userdata payload; `userdataGc` disposes the ref, leaving closure upvalue dangling.
- trace: UAF on next closure call. Same structural shape as JVM `__gc` bug fixed in d90a8f8; POSIX never got the corresponding fix.

## Issue 8 — POSIX createAC(Any?) exception path leaks StableRef
- verdict: TP
- severity: Warning
- evidence: src/posixMain/.../LuaEngine.kt:172-180 — `catch (e: Throwable) { e.printStackTrace(); throw e }` does not dispose the freshly-created ref.
- trace: Unlike `createUserData(Any)` at lines 117-128 which correctly disposes.

## Issue 9 — Dead class LuaCtx
- verdict: TP
- severity: WeakWarning
- evidence: src/posixMain/.../LuaCtx.kt:1-15. Direct read.
- trace: Cross-cited R2.

## Issue 10 — KotlinLuaFunctions.kt empty file
- verdict: TP
- severity: WeakWarning
- evidence: src/posixMain/.../KotlinLuaFunctions.kt:1-7. Direct read.
- trace: Cross-cited R3.

## Issue 11 — Dead InputVarargs / OutputVarargs interfaces
- verdict: TP
- severity: WeakWarning
- evidence: src/commonMain/.../OutputVarargs.kt:3-5, InputVarargs.kt:3-6. Direct read; no implementers.
- trace: Cross-cited R4.

## Issue 12 — JVM printStack body is a stub
- verdict: TP
- severity: WeakWarning
- evidence: src/jvmMain/.../DebugTool.kt:1-9 — `println("JVM KLua implementation doesn't provide any Lua Stack")`. Direct read.
- trace: Cross-cited R14 (housekeeping).

## Issue 13 — StdOut.info silently swallows when no handler
- verdict: TP
- severity: Warning
- evidence: src/commonMain/.../StdOut.kt:1-8 — `fun info(txt: String) { func?.invoke(txt) }`. Direct read.
- trace: `internal var func` not volatile → race on concurrent read/write of func.

## Issue 14 — POSIX LuaEngine.makeRef / createAC(Any?) catch(Throwable) printStackTrace
- verdict: TP
- severity: WeakWarning
- evidence: src/posixMain/.../LuaEngine.kt:89-96, 172-180. Direct read.
- trace: Cross-cited Sec/Nat — production stderr noise.

## Issue 15 — POSIX CLOSURE_FUNCTION silently returns 0 on missing engine
- verdict: TP
- severity: Warning
- evidence: src/posixMain/.../KotlinLuaFunction.kt:55-57 — `val ctx = LuaContextRegistry.lookup(state) ?: return@staticCFunction 0`. Direct read.
- trace: Should `luaL_error(state, ...)` to surface failure at the call site.

## VERDICT COUNTS
- TP: 15
- FP: 0
- NeedMoreData: 0
- By severity (TP only): Error 4, Warning 6, WeakWarning 5

## TOP-RISK TP
- Issue 1 (Error): JVM setAC wrong `__gc` handler → StaticRefs leak per `createAC(LightUserData)`. Direct-read verified.
- Issue 2 (Error): JVM removeClosure(FunctionRef) hardcoded false → callback-id leak. Direct-read verified.
- Issue 3 (Error): POSIX close() empty + Issue 4 strong-retain cycle → active engine never closed. Direct-read verified.
- Issue 7 (Error): POSIX createACClosure double-storage of StableRef → closure upvalue UAF after `__gc`.
