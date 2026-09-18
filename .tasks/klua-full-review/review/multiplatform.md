# Multiplatform expect/actual consistency review — klua

Scope: `expect` declarations in `src/commonMain/kotlin/pw/binom/lua/{LuaValue,ObjectContainer,LuaEngine,DebugTool}.kt`
against `actual` implementations in `src/jvmMain/...` and `src/posixMain/...` (posixMain is wired
to `linux*Main`, `androidNative*Main`, `mingw*Main`, `macos*Main` — see `build.gradle.kts:195–208`).

Coverage check: every top-level `expect` in the listed files has at least one matching `actual`
on both target trees (the only `expect fun` is `LuaEngine.printStack`, and `actual fun` exists in
both trees; the `expect sealed interface LuaValue` has matching `actual sealed interface` in both).
The drift below is **semantic / behavioural**, not "missing actual" — but each finding is the kind
of bug that surfaces only at runtime on one target and silently passes on the other.

---

## Issue 1
- **Title:** `LuaValue.LuaInt`/`Boolean`/`String` `equals()` is broken on POSIX — breaks `TableValue` lookups
- **Type:** Bug
- **Severity:** Error
- **Location:**
  - Expect side: `src/commonMain/kotlin/pw/binom/lua/LuaValue.kt:30-46` (the `LuaInt`, `Boolean`, `String` class shells)
  - JVM actual: `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:145-162`
    ```
    actual class LuaInt actual constructor(actual val value: Long) : LuaValue {
        ...
        override fun equals(other: Any?): kotlin.Boolean = other is LuaInt && value == other.value
    }
    actual class Boolean actual constructor(actual val value: kotlin.Boolean) : LuaValue {
        ...
        override fun equals(other: Any?): kotlin.Boolean = other is Boolean && value == other.value
    }
    actual class String actual constructor(actual val value: kotlin.String) : LuaValue {
        ...
        override fun equals(other: Any?): kotlin.Boolean = other is String && value == other.value
    }
    ```
  - POSIX actual: `src/posixMain/kotlin/pw/binom/lua/LuaValue.kt:84-104`
    ```
    actual class LuaInt actual constructor(actual val value: Long) : LuaValue {
        ...
        override fun equals(other: Any?): kotlin.Boolean = value == other
    }
    actual class Boolean actual constructor(actual val value: kotlin.Boolean) : LuaValue {
        ...
        override fun equals(other: Any?): kotlin.Boolean = value == other
    }
    actual class String actual constructor(actual val value: kotlin.String) : LuaValue {
        ...
        override fun equals(other: Any?): kotlin.Boolean = value == other
    }
    ```
- **Description:** POSIX `equals` is `value == other` where `value: Long/Boolean/String` and `other: Any?`. Kotlin desugars `==` to `value.equals(other)` (left-receiver), so the primitive's `equals` is asked whether a `LuaValue.LuaInt/Boolean/String` wrapper equals it — the receiver is the **primitive** (e.g. `Long.equals(LuaValue.LuaInt)` → false because the right side is not a `Long`). JVM uses the correct type guard first (`other is X && value == other.value`). Result: on POSIX, two wrappers with the same primitive value are **never equal**. Concrete failure: `TableValue` stores entries in `HashMap<LuaValue, LuaValue>` (`posixMain/.../LuaValue.kt:316–329`); `rawGet` is `map[key] ?: Nil`. Cross-platform code does `t["k".lua] = v; t["k".lua]` — JVM returns `v`, POSIX returns `Nil` (hash matches so no NPE, but `equals` says "not equal"). The same shape of bug is exactly what the JVM `setAC` comments call "the same problem `UserData` had before commit 595d8b4". This invalidates `TableValue.toMap()`, `rawGet`/`rawSet`, `of(list)` table construction, and every place common code round-trips a `LuaValue` through a `HashMap`/`MutableMap`. `Number` (posix line 79–88) is fine — only `LuaInt`, `Boolean`, `String` regressed.
- **Fix sketch:** Make the POSIX overrides structurally identical to JVM:
  ```
  override fun equals(other: Any?): kotlin.Boolean =
      other is LuaInt && value == other.value
  ```
  for all three classes. (`hashCode` already matches.)

## Issue 2
- **Title:** `LuaEngine.close()` is a no-op on POSIX — `AutoCloseable` contract diverges across targets
- **Type:** Bug
- **Severity:** Error
- **Location:**
  - Expect side: `src/commonMain/kotlin/pw/binom/lua/LuaEngine.kt:3` (`expect class LuaEngine : AutoCloseable { ... override fun close() }`)
  - JVM actual: `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:35-37`
    ```
    actual override fun close() {
        ll.close()
    }
    ```
  - POSIX actual: `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:22-24`
    ```
    actual override fun close() {
    }
    ```
- **Description:** POSIX body is empty; teardown is delegated to `createCleaner` in `posixMain/.../LuaContext.kt:21-24`, which only fires when the `LuaContext` is GC-collected. Concrete divergence: on JVM `engine.close()` immediately (a) nulls the state pointer so `Cleaner` actions on `TableRef`/`FunctionRef`/`UserData` no-op (`jvmMain/.../LuaValue.kt:43–48,178,323`) and (b) tears down the C state; on POSIX `close()` does nothing — `LuaContextRegistry.current` still holds the entry, the C state is still alive, and subsequent calls into the engine succeed. The whole JVM-side "if engine is closed, cleaner is a no-op" pattern has **no POSIX counterpart**, so users who rely on `use(engine) { … }` for deterministic release get a silently-leaking Lua state on every native target. This violates `AutoCloseable` and the implied "close releases resources" contract documented for the rest of the API.
- **Fix sketch:** In POSIX `LuaEngine.close()` mirror the JVM contract: `lua_close(ll.state)` and set `ll.state` to a null sentinel so `LuaContextRegistry.unregister` fires and the Cleaner's `lua_close` becomes a no-op (e.g. factor `LuaContext.close()` that nulls `state` and call it from both the cleaner and `LuaEngine.close()`).

## Issue 3
- **Title:** POSIX `LuaEngine.call(functionName,...)` leaks one stack slot per "function not found" throw
- **Type:** Bug
- **Severity:** Error
- **Location:**
  - Expect side: `src/commonMain/kotlin/pw/binom/lua/LuaEngine.kt:67` (`fun call(functionName: String, vararg args: LuaValue): List<LuaValue>`)
  - JVM actual: `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:67-79` (pops before throwing)
  - POSIX actual: `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:54-66`
    ```
    actual fun call(functionName: String, vararg args: LuaValue): List<LuaValue> {
        lua_getglobal(ll.state, functionName)
        if (lua_isnil1(ll.state, -1)) {
            throw LuaException("Function \"$functionName\" not found")
        }
        if (!lua_isfunction1(ll.state, -1)) {
            lua_pop(ll.state, 1)
            throw LuaException("\"$functionName\" is not a function")
        }
        ...
    ```
- **Description:** POSIX path throws on the `isNil` branch **without popping**; the `!isFunction` branch correctly pops. `ll.state.checkState { ... }` is NOT used here (unlike every other native call site), so the imbalance is not caught. JVM always pops before throwing. Concrete failure: repeated calls like `engine.call("missing", ...)` accumulate one value on the Lua stack per call → eventual `LUA_MAXSTACK` overflow or corruption. The non-function branch is symmetric and fine, only the nil branch regressed.
- **Fix sketch:** Add `lua_pop(ll.state, 1)` immediately before the `throw` in the `isNil` branch — mirror the `!isFunction` branch and the JVM counterpart. (Optionally wrap the whole body in `ll.state.checkState { ... }` for early detection of any future imbalance.)

## Issue 4
- **Title:** JVM `ObjectContainer.removeClosure(FunctionRef)` is a permanent stub returning `false`
- **Type:** Bug
- **Severity:** Warning
- **Location:**
  - Expect side: `src/commonMain/kotlin/pw/binom/lua/ObjectContainer.kt:18`
    ```
    fun removeClosure(data: LuaValue.FunctionRef): Boolean
    ```
  - JVM actual: `src/jvmMain/kotlin/pw/binom/lua/ObjectContainer.kt:58`
    ```
    actual fun removeClosure(data: LuaValue.FunctionRef): Boolean = false
    ```
  - POSIX actual: `src/posixMain/kotlin/pw/binom/lua/ObjectContainer.kt:35-40`
    ```
    actual fun removeClosure(data: LuaValue.FunctionRef): Boolean {
        if (data.ptr != CLOSURE_FUNCTION) return false
        return remove(data.toValue())
    }
    ```
- **Description:** JVM returns hardcoded `false`. The expect contract documents no special-case, and POSIX actively honours the operation (walks the upValue stable ref and disposes it). Concrete failure: cross-platform code that does `container.removeClosure(fnRef)` after `makeClosure` works on POSIX but silently does nothing on JVM — the closure entry is never freed from `LuaNative.callbacks`, so the `LuaCallbackBridge` it points at leaks for the JVM lifetime. Exactly the class of bug the comment in `jvmMain/.../LuaEngine.kt:11–14` describes having been fixed for the GC path; this one is the symmetric orphan-entry bug for the closure path on JVM.
- **Fix sketch:** Look up the bridge by `data.refId` (the JVM analogue of POSIX's `CLOSURE_FUNCTION` sentinel) in `LuaNative.callbacks`, call `disposeCallback(id)`, then return `true` iff a removal occurred. The mapping from `FunctionRef` → `callbackId` exists at construction time (see `makeAutoGcRef` in `jvmMain/.../LuaEngine.kt:17–30`); the only missing piece is the bookkeeping that lets `removeClosure` recover that id from the wrapper.

## Issue 5
- **Title:** POSIX `ObjectContainer.removeClosure(FunctionValue)` reports success without disposing
- **Type:** Bug
- **Severity:** Warning
- **Location:**
  - Expect side: `src/commonMain/kotlin/pw/binom/lua/ObjectContainer.kt:19`
  - POSIX actual: `src/posixMain/kotlin/pw/binom/lua/ObjectContainer.kt:42-51`
    ```
    actual fun removeClosure(data: LuaValue.FunctionValue): Boolean {
        if (data.upValues.size != 1) return false
        val func = data.upValues[0]
        if (func is LuaValue.LightUserData && func.value is LuaFunction) {
            func.dispose()
            return true
        }
        return true            // ← bug: returns true even if nothing was disposed
    }
    ```
- **Description:** The final `return true` is reachable when the upValue is not a `LightUserData<LuaFunction>` (e.g. someone constructs a `FunctionValue` directly with arbitrary upValues). The caller is told the closure was removed when no stable ref was disposed. JVM's `closures.remove(data.callbackId) != null` correctly returns `false` in that case.
- **Fix sketch:** Change the final `return true` to `return false` (or restructure as a single `if` with `func.dispose()` then `return true`, and `return false` after the size check, matching JVM's "was it really removed?" semantics).

## Issue 6
- **Title:** `ObjectContainer.add()` deduplicates on POSIX but always allocates on JVM
- **Type:** Bug / Concurrency
- **Severity:** Warning
- **Location:**
  - Expect side: `src/commonMain/kotlin/pw/binom/lua/ObjectContainer.kt:11` (`fun add(data: Any?): LuaValue.LightUserData`)
  - JVM actual: `src/jvmMain/kotlin/pw/binom/lua/ObjectContainer.kt:42-44`
    ```
    actual fun add(data: Any?): LuaValue.LightUserData {
        val ptr = StaticRefs.intern(data)
        return LuaValue.LightUserData(ptr)
    }
    ```
  - POSIX actual: `src/posixMain/kotlin/pw/binom/lua/ObjectContainer.kt:18-29`
    ```
    actual fun add(data: Any?): LuaValue.LightUserData {
        if (data == null) return LuaValue.LightUserData(null)
        val exist = objToPtr[data]
        if (exist != null) return LuaValue.LightUserData(exist)
        val dataStableRef = StableRef.create(data)
        ptrToObj[dataStableRef.asCPointer()] = data
        objToPtr[data] = dataStableRef.asCPointer()
        return LuaValue.LightUserData(dataStableRef.asCPointer())
    }
    ```
- **Description:** JVM `StaticRefs.intern` (jvmMain/.../StaticRefs.kt:11–14) always allocates a fresh counter key — same `data` passed twice gets two slots and two `LightUserData` wrappers with different `ptr`s. POSIX uses `objToPtr[data]` to deduplicate by identity. Concrete failure: cross-platform `container.add(obj); container.add(obj)` returns identical wrappers on POSIX but distinct ones on JVM; memory profile differs (JVM `StaticRefs` grows linearly with `add` calls), and `remove(obj)` removes a single slot on POSIX but the **first** matching slot on JVM (`removeIfMatches` iterates and removes the first entry by `===`). Behaviour is observably different — same source, two semantics.
- **Fix sketch:** Pick one contract (recommend POSIX's dedup — it avoids the slot leak and matches `remove`'s identity check) and apply to both. For JVM, change `add` to consult an existing identity map before calling `StaticRefs.intern`; symmetrically update `StaticRefs.intern` callers to deduplicate.

## Issue 7
- **Title:** `ObjectContainer.clear()` leaks `StaticRefs` entries on JVM
- **Type:** Bug
- **Severity:** Warning
- **Location:**
  - Expect side: `src/commonMain/kotlin/pw/binom/lua/ObjectContainer.kt:24` (KDoc: "Clean all added objects and closures. Call for prevent memory leak")
  - JVM actual: `src/jvmMain/kotlin/pw/binom/lua/ObjectContainer.kt:54-56`
    ```
    actual fun clear() {
        closures.clear()
    }
    ```
  - POSIX actual: `src/posixMain/kotlin/pw/binom/lua/ObjectContainer.kt:67-72`
    ```
    actual fun clear() {
        ptrToObj.keys.forEach { it.asStableRef<LuaFunction>().dispose() }
        ptrToObj.clear()
        objToPtr.clear()
    }
    ```
- **Description:** JVM `clear()` only drops the `closures` map; every `StaticRefs` slot created via `add()` remains. POSIX disposes every stable ref and clears both maps. The expect docstring is on the JVM actual a lie: calling `clear()` does **not** "prevent memory leak" for objects that went through `add()`. The slots only disappear when their `LightUserData` wrapper is GC'd (see `jvmMain/.../LuaValue.kt:107-127`), which may be much later than `clear()`.
- **Fix sketch:** Have JVM `clear()` also iterate and drop every `StaticRefs` slot that belongs to a wrapper this container owns. Easiest is to keep a parallel identity map mirroring POSIX's `objToPtr` (which also fixes Issue 6) and `StaticRefs.dispose` each value's ptr; clear the closure map the same way as today.

## Issue 8
- **Title:** `ObjectContainer.add(null)` / `remove(null)` semantics diverge across targets
- **Type:** Bug
- **Severity:** Warning
- **Location:**
  - Expect side: `src/commonMain/kotlin/pw/binom/lua/ObjectContainer.kt:11,15` (signature: `add(data: Any?)`, `remove(data: Any)`)
  - JVM `add`: `src/jvmMain/.../ObjectContainer.kt:42-44` — `StaticRefs.intern(null)` stores `null` under a fresh slot and returns a non-null `LightUserData(ptr)`.
  - JVM `remove`: `src/jvmMain/.../StaticRefs.kt:36-46` (`removeIfMatches(null)` matches and removes the slot, returns `true`).
  - POSIX `add`: `src/posixMain/.../ObjectContainer.kt:18-29` — short-circuits and returns `LuaValue.LightUserData(null)` (null pointer, no slot).
  - POSIX `remove`: `src/posixMain/.../ObjectContainer.kt:31-36` — `objToPtr.remove(null)` always returns null, so `remove(null)` always returns `false`.
- **Description:** Different platforms encode null differently: JVM persists it under a `StaticRefs` key (so `get(LightUserData(ptr))` returns `null`), POSIX short-circuits. JVM's `remove(null)` succeeds, POSIX's returns `false`. Concrete failure: cross-platform helper that calls `container.remove(null)` to clean up a sentinel is a no-op on POSIX even if the JVM side reports success.
- **Fix sketch:** Document and pick one behaviour. If POSIX is canonical ("null means no entry"), update JVM to special-case `null` in both `add` and `remove`; if JVM is canonical, update POSIX to store `null` under a generated slot.

## Issue 9
- **Title:** `closureAutoGcFunction` and `userdataAutoGcFunction` share one trampoline on POSIX but use two on JVM
- **Type:** Design / Concurrency
- **Severity:** Warning
- **Location:**
  - Expect side: `src/commonMain/kotlin/pw/binom/lua/LuaEngine.kt:6-7`
    ```
    val closureAutoGcFunction: LuaValue.FunctionRef
    val userdataAutoGcFunction: LuaValue.FunctionRef
    ```
  - JVM actual: `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:7-8,17-34`
    ```
    actual val closureAutoGcFunction  = makeAutoGcRef()       // pushGcFunction      → disposeCallback(id)
    actual val userdataAutoGcFunction = makeUserdataGcRef()  // pushUserdataGcFunction → disposeUserdata(mem)
    ```
  - POSIX actual: `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:12-15`
    ```
    actual val closureAutoGcFunction: LuaValue.FunctionRef  = makeRef(LuaValue.FunctionValue(userdataGc))
    actual val userdataAutoGcFunction: LuaValue.FunctionRef = makeRef(LuaValue.FunctionValue(userdataGc))
    ```
    `userdataGc` defined at `src/posixMain/kotlin/pw/binom/lua/KotlinLuaFunction.kt:24-41` — single trampoline that disposes whatever `StableRef<Any>` is at the userdata payload.
- **Description:** The expect surface promises two distinct auto-GC trampolines; JVM provides two (`pushGcFunction` keyed on a callback id, vs `pushUserdataGcFunction` keyed on the userdata's `mem` address), POSIX provides one (the `userdataGc` cfunction from `KotlinLuaFunction.kt`) reused for both names. The asymmetry happens to be safe today because both POSIX code paths (closure upValues via `CLOSURE_FUNCTION`, `createUserData(Any)`) put a `StableRef<Any>` at the userdata payload — but the public API surface now disagrees with what JVM does. If a future native target introduces a payload that is *not* a `StableRef<Any>` for one of these roles (e.g. a payload that needs the callback-id-based dispose that JVM's `makeAutoGcRef` performs), POSIX will silently mis-dispose. Also: on POSIX `userdataAutoGcFunction` is **never read** (verified by grep — only `closureAutoGcFunction` is referenced from `posixMain/.../LuaEngine.kt:122,154,162,164`), so the second member is dead weight that misleads callers about platform semantics. This is the shape of bug the commit d90a8f8 fixed for `__gc` callbacks — `closureAutoGcFunction` pointing at `userdataGc` is essentially "both names point at the same trampoline", the same drift symptom.
- **Fix sketch:** Either (a) collapse the expect surface to one auto-GC function (the model POSIX actually implements), or (b) introduce a real second trampoline on POSIX for `closureAutoGcFunction` whose body disposes the `StableRef<LuaFunction>` it reads from the closure's upValues (`CLOSURE_FUNCTION` already does the upValue read path; the trampoline would mirror it for `__gc`). Option (b) makes the contract real across platforms.

## Issue 10
- **Title:** `DebugTool.printStack` expect is `internal`, actuals are public — visibility asymmetry
- **Type:** BestPractice
- **Severity:** Info
- **Location:**
  - Expect side: `src/commonMain/kotlin/pw/binom/lua/DebugTool.kt:8`
    ```
    internal expect fun LuaEngine.printStack(message: String? = null)
    ```
  - JVM actual: `src/jvmMain/kotlin/pw/binom/lua/DebugTool.kt:4`
    ```
    actual fun LuaEngine.printStack(message: String?) { ... }
    ```
  - POSIX actual: `src/posixMain/kotlin/pw/binom/lua/DebugTool.kt:7`
    ```
    actual fun LuaEngine.printStack(message: String?) { ... }
    ```
- **Description:** Kotlin allows actuals to be more visible than the expect (`public` actual for `internal` expect is legal), so this compiles. The IDE inspection profile flags nothing. The behavioural consequence is symmetric (both actuals are public, both call sites compiled against the `internal` expect see no public exposure), so it's only a style nit. Worth noting because the file is `@Deprecated` and meant to be removed; if the team decides to delete it, drop the `internal` rather than relying on the public actual.
- **Fix sketch:** Either delete the file (the deprecated debug tool has no other consumer) or add `internal` to both actuals for symmetry.

## Issue 11
- **Title:** Unused `Cleaner` import in `jvmMain/.../ObjectContainer.kt`
- **Type:** BestPractice
- **Severity:** WeakWarning
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/ObjectContainer.kt:3` (`import java.lang.ref.Cleaner`)
- **Description:** Confirmed by `run_inspections` (KotlinUnusedImport). The class uses a `BridgeCleaner` defined inline, not `java.lang.ref.Cleaner`. Comment-only mentions of "Cleaner" elsewhere in the file don't count as uses. Dead import.
- **Fix sketch:** Delete the import line.

---

## Notes that did **not** turn into findings

- The 4 top-level `expect` declarations all have matching `actual`s on both target trees (verified by `grep -rn "^actual "` against `src/jvmMain`/`src/posixMain`). No missing-actual landmines.
- `posixMain` is wired by `build.gradle.kts:195–208` (`dependsOn("linux*Main", posixMain)`, `androidNative*Main`, `mingw*Main`, `macos*Main`). The "posix" name vs the fact that **mingwX64 (Windows)** also depends on it is mildly misleading but mechanically fine — the actuals don't assume POSIX headers, only `kotlinx.cinterop` (which is available on mingwX64 too).
- `expect sealed interface LuaValue` members all have matching `actual` in both trees (`FunctionValue`, `Number`, `Data`, `UserData`, `LightUserData`, `LuaInt`, `Boolean`, `String`, `Callable`, `Meta`, `Table`, `Ref`, `RefObject`, `TableValue`, `TableRef`, `FunctionRef`, `Nil`, `companion`). Both `actual sealed interface LuaValue` declarations are sealed.
- `LuaValue.TableValue` constructors match: expect declares `()` / `(Map)` / `vararg Pair`, both actuals mirror them (`jvmMain/.../LuaValue.kt:346–355`, `posixMain/.../LuaValue.kt:301–311`). Common test (`commonTest/.../CommonLuaEngineTest.kt:135–248`) constructs `LuaValue.TableValue()` and `LuaValue.TableValue("k".lua to v)` — only the no-arg and `vararg Pair` overloads are exercised, both supported on both actuals.
- `LuaValue.companion` `of(...)` overload set matches across actuals (9 overloads each).
- `Ref` extra members (`val refId: Int` on JVM, `val ref: LuaRef` on POSIX) are not part of the expect surface — common code can't see them. No drift.
- `KotlinLuaFunction.kt` (`posixMain`) is a 7-line file with only imports — no actuals there. Safe.
- `LuaCtx.kt` and `LuaStateAndLib.kt` (posix) are leftover classes with no expect counterpart; harmless.

---

## FINDINGS COUNT

Total: 11 (3 Error, 6 Warning, 1 Info, 1 WeakWarning)

## SUMMARY

The cross-platform expect/actual skeleton is structurally complete — every top-level expect in
`commonMain` has a matching actual on both the JVM and POSIX trees, and the type signatures line up.
The bugs hide in the **bodies**:

- The biggest semantic break is Issue 1: POSIX `LuaInt`/`Boolean`/`String` `equals` is wrong,
  so any `HashMap<LuaValue, _>` lookup (which is exactly what `TableValue` is) silently returns
  misses on every native target.
- Issue 2 (`LuaEngine.close()` no-op on POSIX) and Issue 3 (stack leak on the "function not
  found" throw path) break `AutoCloseable` and slowly corrupt the Lua stack — exactly the class
  of latent drift that recent commits were patching on the JVM side.
- The four `ObjectContainer` issues (Issues 4–8) are the same family of "one platform is a stub,
  the other actually disposes" drift that historically drove leaks (per the comments in
  `jvmMain/.../LuaEngine.kt`).
- Issue 9 (one trampoline wearing two names on POSIX) is the textbook expect/actual drift
  d90a8f8 was supposed to close — the names disagree across targets even though the behaviour
  happens to work today.

The `DebugTool` and unused-import items are housekeeping.