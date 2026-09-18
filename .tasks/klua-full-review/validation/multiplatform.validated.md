# Validation: Multiplatform expect/actual (Issue 1-11)

Verified by direct read of `src/commonMain`, `src/jvmMain`, `src/posixMain`. Cross-source-set bugs were checked on BOTH sides.

## Issue 1 — POSIX LuaInt/Boolean/String.equals broken
- verdict: TP
- severity: Error
- evidence: src/posixMain/.../LuaValue.kt:84-104 — `actual class LuaInt ... override fun equals(other: Any?) = value == other` (Long.equals). JVM counterpart at src/jvmMain/.../LuaValue.kt:145-162 uses correct `other is LuaInt && value == other.value` guard.
- trace: `value == other` desugars to `value.equals(other)` where `value` is the primitive `Long/Boolean/String` on the left; primitive's `.equals()` asks whether `other` is itself a Long/Boolean/String (boxed) — for a `LuaValue.LuaInt` wrapper the answer is `false`. Confirmed: read line 92-94 directly. Breaks every `HashMap<LuaValue, _>` lookup (e.g. `TableValue`).

## Issue 2 — POSIX LuaEngine.close() no-op
- verdict: TP
- severity: Error
- evidence: src/posixMain/.../LuaEngine.kt:22-24 — `actual override fun close() { }`. JVM counterpart at src/jvmMain/.../LuaEngine.kt:35-37 calls `ll.close()`.
- trace: Confirmed via direct read. `AutoCloseable` contract violated on POSIX. Cross-cited by 5 other reviewers.

## Issue 3 — POSIX call(functionName) leaks stack on nil-branch
- verdict: TP
- severity: Error
- evidence: src/posixMain/.../LuaEngine.kt:54-56 — `if (lua_isnil1(ll.state, -1)) throw LuaException("Function ... not found")`. JVM counterpart at src/jvmMain/.../LuaEngine.kt:71-83 pops before throwing.
- trace: Confirmed. `checkState` not used; the !isFunction branch correctly pops. Asymmetric bug.

## Issue 4 — JVM ObjectContainer.removeClosure(FunctionRef) hardcoded false
- verdict: TP
- severity: Warning
- evidence: src/jvmMain/.../ObjectContainer.kt:58 — `actual fun removeClosure(data: LuaValue.FunctionRef): Boolean = false`. POSIX counterpart at src/posixMain/.../ObjectContainer.kt:35-40 actively disposes.
- trace: Cross-source-set drift. JVM entry in `LuaNative.callbacks` never freed; POSIX path works.

## Issue 5 — POSIX removeClosure(FunctionValue) final return true
- verdict: TP
- severity: Warning
- evidence: src/posixMain/.../ObjectContainer.kt:42-51 — final `return true` after `if (...) func.dispose(); return true`. Reachable when `func.value is not LuaFunction`.
- trace: Direct read confirms. Caller believes cleanup ran when no ref was disposed.

## Issue 6 — add/remove semantics diverge across targets
- verdict: TP
- severity: Warning
- evidence: src/jvmMain/.../ObjectContainer.kt:42-44 (JVM always allocate via `StaticRefs.intern`); src/posixMain/.../ObjectContainer.kt:18-29 (POSIX dedups via `objToPtr[data]`).
- trace: Same source, two semantics. Memory profile and `remove(obj)` semantics differ.

## Issue 7 — clear() leaks StaticRefs entries on JVM
- verdict: TP
- severity: Warning
- evidence: src/jvmMain/.../ObjectContainer.kt:54-56 — `actual fun clear() { closures.clear() }`. POSIX counterpart (lines 67-72) iterates and disposes both maps.
- trace: Common docstring on `ObjectContainer.clear()` ("prevent memory leak") is false on JVM for objects added via `add()`.

## Issue 8 — add(null)/remove(null) semantics diverge
- verdict: TP
- severity: Warning
- evidence: JVM `add(null)` interns at a fresh slot (StaticRefs.intern at src/jvmMain/.../StaticRefs.kt:11-14); POSIX short-circuits (src/posixMain/.../ObjectContainer.kt:21).
- trace: Cross-platform helper calling `container.remove(null)` is no-op on POSIX, success on JVM.

## Issue 9 — closureAutoGcFunction ≡ userdataAutoGcFunction on POSIX
- verdict: TP
- severity: Warning
- evidence: src/posixMain/.../LuaEngine.kt:12-15 — both are `makeRef(LuaValue.FunctionValue(userdataGc))`. JVM counterpart at src/jvmMain/.../LuaEngine.kt:7-8,17-34 actually has two distinct trampolines.
- trace: Confirmed direct read. Both names point at the same cfunction on POSIX — only behaviorally safe because both code paths store a `StableRef<Any>`. If a future target diverges, silent mis-dispose.

## Issue 10 — DebugTool visibility asymmetry
- verdict: TP
- severity: Info
- evidence: src/commonMain/.../DebugTool.kt:8 (`internal expect`) vs src/jvmMain/.../DebugTool.kt:4 + src/posixMain/.../DebugTool.kt:7 (public actual).
- trace: Kotlin compiles. Style nit; symmetric behavioural impact.

## Issue 11 — Unused Cleaner import
- verdict: TP
- severity: WeakWarning
- evidence: src/jvmMain/.../ObjectContainer.kt:3 — `import java.lang.ref.Cleaner`.
- trace: Easy to verify by grep. Dead import; classes use `BridgeCleaner` instead.

## VERDICT COUNTS
- TP: 11
- FP: 0
- NeedMoreData: 0
- By severity (TP only): Error 3, Warning 6, Info 1, WeakWarning 1

## TOP-RISK TP
- Issue 1 (Error): POSIX `LuaInt/Boolean/String.equals` broken — every `TableValue` lookup returns Nil on POSIX. Direct-read verified at LuaValue.kt:84-104.
- Issue 2 (Error): POSIX `LuaEngine.close()` no-op — AutoCloseable violation.
- Issue 3 (Error): POSIX `call(functionName)` leaks Lua stack on nil-branch.
