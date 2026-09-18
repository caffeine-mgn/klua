# Reuse & Simplification review — klua

Scope: src/commonMain, src/jvmMain, src/posixMain under `pw/binom/lua`. ~2481 lines reviewed across 33 files.

---

## Finding R1 — `closureAutoGcFunction` and `userdataAutoGcFunction` compute the same value twice

- **Type:** Maintainability / Design
- **Severity:** Warning
- **Location:** `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:17-21`
  ```kotlin
  actual val closureAutoGcFunction: LuaValue.FunctionRef =
      makeRef(LuaValue.FunctionValue(userdataGc))
  actual val userdataAutoGcFunction: LuaValue.FunctionRef =
      makeRef(LuaValue.FunctionValue(userdataGc))
  ```
- **Description:** Both properties are `makeRef(LuaValue.FunctionValue(userdataGc))` — identical expressions that allocate two independent `FunctionRef` wrappers (two separate `luaL_ref` slots, two separate `lua_pushcclosure` calls) pointing at the very same cfunction. The names imply they are different things; the implementation proves otherwise. The JVM side (`jvmMain/LuaEngine.kt:11-23`) does distinguish them via the per-callbackId `pushGcFunction` / shared `pushUserdataGcFunction` path, so on POSIX the duplication is gratuitous. `createACClosure` (line 144) also constructs the metatable with both `__call` and `__gc` referring to the same ref — only one is needed.
- **Fix sketch:** Keep a single `autoGcFunction` field (or a `private val`); reuse it in every metatable. If two refs are intentional (one for closure payload, one for userdata payload so that Lua's __gc finds a value either way), then name them after the slot they populate rather than the cfunction they wrap.

---

## Finding R2 — `LuaCtx` and `LuaStateAndLib` are dead duplicate-of-`LuaContext`

- **Type:** Maintainability
- **Severity:** Warning
- **Location:**
  - `src/posixMain/kotlin/pw/binom/lua/LuaCtx.kt:10-14`
  - `src/posixMain/kotlin/pw/binom/lua/LuaStateAndLib.kt:6`
- **Description:** `LuaCtx` is `class LuaCtx(private constructor(state: LuaState)) { constructor(): this(luaL_newstate() ?: ...); createCleaner(state) { lua_close(it) } }` — same construction + cleanup pattern as `LuaContext` (`posixMain/LuaContext.kt:14-23`). `LuaStateAndLib` is a one-line wrapper around `LuaState`. Neither is referenced anywhere in `src/`, tests, or other subprojects (verified by full-tree search — only the declaration site appears, plus internal historian XML).
- **Fix sketch:** Delete both files. If `LuaCtx` was kept for a planned alternate init (no `luaL_openlibs`), keep the design in a single class with a flag, not a second near-clone.

---

## Finding R3 — `KotlinLuaFunctions.kt` is an empty stub file

- **Type:** Maintainability
- **Severity:** Warning
- **Location:** `src/posixMain/kotlin/pw/binom/lua/KotlinLuaFunctions.kt:1-7`
- **Description:** The file contains only a file-level `@OptIn(...)`, `package`, and one `import platform.internal_lua.*`. No types, no functions, no extension declarations. Verified zero usages anywhere in the project. It looks like a placeholder that was never filled.
- **Fix sketch:** Delete the file.

---

## Finding R4 — `OutputVarargs` and `InputVarargs` are unused interface stubs

- **Type:** Maintainability
- **Severity:** Warning
- **Location:**
  - `src/commonMain/kotlin/pw/binom/lua/OutputVarargs.kt:3-5`
  - `src/commonMain/kotlin/pw/binom/lua/InputVarargs.kt:3-6`
- **Description:** Both are declared `expect`-less public interfaces; nothing in `commonMain`, `jvmMain`, `posixMain`, the test sources, or sample code implements or references either of them (verified by full-tree search returning only the declarations and prior-review XML). They contribute to the public API surface but are unused dead surface.
- **Fix sketch:** Delete both. If a future "varargs" API is planned, reintroduce with at least one consumer in-tree to avoid re-creating the same dead stub.

---

## Finding R5 — Two divergent pcall-result handlers on JVM (`pcallProcessing` vs `pcallCall`)

- **Type:** Bug / Maintainability
- **Severity:** Error
- **Location:**
  - `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:217-237` (`pcallProcessing`, statuses `0/2/4/5/else`, throws `LuaException` for status 2)
  - `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:438-460` (`pcallCall`, statuses `0/4/5/6/else`, treats status 4 as "runtime error", throws `RuntimeException` for everything)
- **Description:** `LuaNative.pcall` (verified in `src/jvmMain/c/klua_jni.c:570-572`) returns the raw `lua_pcallk` result, i.e. `LUA_OK=0`, `LUA_ERRRUN=2`, `LUA_ERRMEM=4`, `LUA_ERRERR=5`. `pcallProcessing` maps `2 → LuaException`, `4 → RuntimeException("memory allocation error")`, `5 → RuntimeException("message handler")`. `pcallCall` maps `4 → LuaException("runtime error")`, `5 → RuntimeException("memory allocation error")`, `6 → RuntimeException("message handler")`, and treats anything not 0/4/5/6 — including status `2` — as `RuntimeException("Unknown pcall status: 2")`. So a runtime Lua error raised from `TableRef.call(...)` / `FunctionRef.call(...)` / `UserData.call(...)` is silently re-thrown as `RuntimeException("Unknown pcall status: 2")`, never as a `LuaException`. The two functions should be one. Magic numbers should be named constants (the posix side already uses `LUA_OK`/`LUA_ERRRUN`/`LUA_ERRMEM`/`LUA_ERRERR` from cinterop — see `posixMain/LuaEngine.kt:171-204`).
- **Fix sketch:** Promote a single `LuaStatus` object with `OK=0, ERRRUN=2, ERRMEM=4, ERRERR=5`, move it to `commonMain` (the values are stable across all Lua 5.4 builds), and have both JVM call sites dispatch through one function. Use the same exception type (`LuaException`) for the user-visible `ERERRUN` path on both code paths.

---

## Finding R6 — `LuaNativeType` object is defined but never referenced

- **Type:** Maintainability
- **Severity:** WeakWarning
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaNative.kt:166-177`
- **Description:** Private object with `NONE=-1, NIL=0, BOOLEAN=1, LIGHTUSERDATA=2, NUMBER=3, STRING=4, TABLE=5, FUNCTION=6, USERDATA=7, THREAD=8`. The actual consumer, `LuaContext.readValueAt` (`src/jvmMain/kotlin/pw/binom/lua/LuaContext.kt:62-122`), spells the integers out as literals (`-1, 0 -> LuaValue.Nil; 1 -> LuaValue.Boolean; ...`). The constants exist and are unused — exactly the kind of "I imported this but never refactored the caller" residue that misleads readers into thinking the type system is enforced.
- **Fix sketch:** Either reference the constants (`LuaNativeType.STRING -> ...`) or delete the object. Prefer the former — combine with Finding R5 and put status/type constants in a shared `commonMain` file.

---

## Finding R7 — `LuaValue.of(table: List<LuaValue>)` / `of(table: Array<LuaValue>)` / `of(table: List, metatable)` are six near-identical `forEachIndexed` loops

- **Type:** Maintainability
- **Severity:** Warning
- **Location:**
  - `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:443-462` (three near-identical copies)
  - `src/posixMain/kotlin/pw/binom/lua/LuaValue.kt:391-412` (three near-identical copies)
- **Description:** Every overload repeats the same body: `HashMap<LuaValue, LuaValue>(); forEachIndexed { i, v -> result[of(i.toLong() + 1)] = v }; return TableValue(...)`. The `Array` and `List` versions differ only in the receiver type — they could share `Iterable<LuaValue>`. The `(table, metatable)` variants differ only by passing the second arg to `TableValue(...)`. Six copies of essentially the same loop, two per target.
- **Fix sketch:** One private helper per target:
  ```kotlin
  private fun sequenceToTable(seq: Iterable<LuaValue>, metatable: LuaValue? = null): TableValue {
      val m = HashMap<LuaValue, LuaValue>()
      seq.forEachIndexed { i, v -> m[of(i.toLong() + 1)] = v }
      return if (metatable == null) TableValue(m) else TableValue(m, metatable)
  }
  ```
  The four public `of(...)` overloads then become one-liners.

---

## Finding R8 — `setAC` body is copy-pasted verbatim between JVM and POSIX `LuaEngine` actuals

- **Type:** Maintainability
- **Severity:** WeakWarning
- **Location:**
  - `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:200-208`
  - `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:175-184`
- **Description:** Both bodies are identical:
  ```kotlin
  val table = userdata.metatable
  if (table is LuaValue.Table) {
      table["__gc".lua] = closureAutoGcFunction
  } else {
      userdata.metatable = LuaValue.TableValue("__gc".lua to closureAutoGcFunction)
  }
  ```
  expect/actual forces per-target implementations of a non-open member, so true deduplication is not possible, but the duplication is a hint that the API surface (`setAC`) is unnecessary — see R9.
- **Fix sketch:** Either accept the per-target duplication and document the reason (expect/actual open vs closed), or convert the `setAC` operation to a `commonMain` extension on `LuaValue.UserData` whose body is shared.

---

## Finding R9 — `createAC` and `createUserData` are operationally the same on POSIX; `setAC` after `createUserData` is wasted work on JVM

- **Type:** Maintainability / Bug
- **Severity:** Warning
- **Location:**
  - `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:186-194` (`createAC(LightUserData)`) and `:198-205` (`createAC(Any?)`)
  - `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:210-213` (`createAC(LightUserData)`) and `:215-220` (`createAC(Any?)`)
- **Description:** POSIX `createAC(value: Any?)` just calls `createAC(LightUserData(ref))`. JVM `createAC(LightUserData)` calls `createUserData(LightUserData)` (which already sets `metatable = LuaValue.TableValue("__gc" to userdataAutoGcFunction)`) and then `setAC(ud)` (which overwrites `metatable` with `closureAutoGcFunction`). For the LightUserData path that means the metatable is allocated and then thrown away. Same for `createAC(Any?)`: it calls `createUserData(value)` (allocates metatable, sets to `userdataAutoGcFunction`) and the caller is expected to call `setAC` separately. Two ways to do the same thing; users will pick at random and get different behavior depending on which.
- **Fix sketch:** Collapse to one entry point: `createUserData` always installs the auto-GC metatable; delete `createAC`, or make `createAC` a deprecated alias that just delegates.

---

## Finding R10 — Two parallel monotonic ID generators on JVM, none on POSIX

- **Type:** Maintainability
- **Severity:** WeakWarning
- **Location:**
  - `src/jvmMain/kotlin/pw/binom/lua/LuaNative.kt:14` (`nextCallbackId: AtomicInteger`)
  - `src/jvmMain/kotlin/pw/binom/lua/ObjectContainer.kt:104` (`counter: AtomicInteger(1)` for `nextClosureId()`)
- **Description:** `LuaNative.nextCallbackId()` (used by `createACClosure` in `LuaEngine.kt:160`) and `ObjectContainer.nextClosureId()` (used by `makeClosure` in `ObjectContainer.kt:22`) both maintain independent counters with the same purpose: hand out a unique integer for a Lua-side closure. POSIX has zero counters and re-uses Lua's own `luaL_ref` integer for everything (see `LuaRef`/`posixMain/lua_funcs.kt:48`), which is enough. The JVM could do the same — `nextCallbackId` could be the same `StaticRefs.counter` and `nextClosureId` could just call into it, or be removed entirely (`makeClosure` doesn't actually need a stable id — the bridge is held in `LuaNative.callbacks` keyed by the id, but the id only has to be unique among live callbacks, which `LuaNative.nextCallbackId` already guarantees).
- **Fix sketch:** Either consolidate the JVM counters into one (`AtomicInteger` in `LuaNative`), or remove `nextClosureId` and route `ObjectContainer.makeClosure` through `LuaNative.nextCallbackId()`.

---

## Finding R11 — `ObjectContainer.removeClosure(FunctionValue)` final branch is unreachable / always-true

- **Type:** Bug / Maintainability
- **Severity:** Warning
- **Location:** `src/posixMain/kotlin/pw/binom/lua/ObjectContainer.kt:49-57`
- **Description:** The body:
  ```kotlin
  actual fun removeClosure(data: LuaValue.FunctionValue): Boolean {
      if (data.upValues.size != 1) return false
      val func = data.upValues[0]
      if (func is LuaValue.LightUserData && func.value is LuaFunction) {
          func.dispose()
          return true
      }
      return true   // ← always-true fallback
  }
  ```
  Both the `if` body and the path after it return `true`, so the `if`-condition is the only thing that determines `true`/`false`. The condition itself is also over-broad: it disposes the upvalue only when `func.value is LuaFunction`, but the function still returns `true` in every other case (e.g. upvalue is a non-`LuaFunction` lightuserdata, or the size-check failed earlier — already returned `false`). The trailing `return true` is dead by construction.
- **Fix sketch:**
  ```kotlin
  if (data.upValues.size != 1) return false
  val func = data.upValues[0]
  val ptr = (func as? LuaValue.LightUserData)?.lightPtr ?: return false
  ptr.asStableRef<Any>().dispose()
  ptrToObj.remove(ptr); objToPtr.remove(ptrToObj[ptr] ?: return true)
  return true
  ```
  Decide what "removed" means — dispose the stable ref, drop both maps, return `true`.

---

## Finding R12 — Redundant `is LuaValue.Nil` branch in `LuaValueWriter.pushValue`

- **Type:** Maintainability
- **Severity:** WeakWarning
- **Location:** `src/posixMain/kotlin/pw/binom/lua/LuaValueWriter.kt:10-11`
  ```kotlin
  LuaValue.Nil,
  is LuaValue.Nil -> lua_pushnil(state)
  ```
- **Description:** `LuaValue.Nil` is an `object` (singleton). `when` already matches singletons by equality, so the `LuaValue.Nil,` branch covers the value. The `is LuaValue.Nil` clause can never trigger — `LuaValue.Nil::class` is the only value the type check would accept, and that value already matched the first branch. Two clauses for the same outcome; one is dead.
- **Fix sketch:** Drop the `is LuaValue.Nil` clause, keep `LuaValue.Nil -> lua_pushnil(state)`.

---

## Finding R13 — StdOut vs println inconsistency between JVM and POSIX `printStack`

- **Type:** Maintainability
- **Severity:** WeakWarning
- **Location:**
  - `src/jvmMain/kotlin/pw/binom/lua/DebugTool.kt:5-8` (uses `println(...)`)
  - `src/posixMain/kotlin/pw/binom/lua/DebugTool.kt:7-37` (uses `StdOut.info(...)`)
- **Description:** Two implementations of the same deprecated `LuaEngine.printStack` extension, but one talks to the swappable `StdOut` (`commonMain/StdOut.kt`) and the other bypasses it entirely with `println`. Anyone who wires `StdOut.func = { ... }` to redirect output gets POSIX output and loses JVM output. The common source-set declares `StdOut` precisely so this kind of divergence is avoidable.
- **Fix sketch:** Both `actual`s should call `StdOut.info(...)`. Or, since `printStack` is `@Deprecated`, delete both files and the `expect` declaration in `commonMain/DebugTool.kt` (see R14).

---

## Finding R14 — `DebugTool.kt` (expect + actuals) is fully `@Deprecated` and used only by tests

- **Type:** Maintainability
- **Severity:** WeakWarning
- **Location:**
  - `src/commonMain/kotlin/pw/binom/lua/DebugTool.kt:5-8`
  - `src/jvmMain/kotlin/pw/binom/lua/DebugTool.kt:1-9`
  - `src/posixMain/kotlin/pw/binom/lua/DebugTool.kt:1-38`
- **Description:** All three are marked `@Deprecated(message = "Debug Tool", level = DeprecationLevel.WARNING)` and `internal`. Full-tree search shows the only production-side references are within the three files themselves. If the debug helper is no longer maintained, drop it; otherwise drop the `@Deprecated` and keep it consistent.
- **Fix sketch:** Decide based on intent: either deprecate-and-keep with a single shared implementation in `commonMain` (the JVM body is trivial — it admits it has no stack to print), or delete all three.

---

## Finding R15 — Magic Lua status / type integers on JVM

- **Type:** BestPractice
- **Severity:** WeakWarning
- **Location:**
  - `src/jvmMain/kotlin/pw/binom/lua/LuaContext.kt:62-110` (raw `-1, 0, 1..7` in `readValueAt`)
  - `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:217-237` (`0/2/4/5/else` in `pcallProcessing`)
  - `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:438-460` (`0/4/5/6/else` in `pcallCall`)
  - `src/jvmMain/kotlin/pw/binom/lua/LuaNative.kt:139` (`(1L shl 31) or 0` — error sentinel for `invokeCallback`)
- **Description:** POSIX uses named constants from cinterop (`LUA_OK`, `LUA_ERRRUN`, `LUA_ERRMEM`, `LUA_ERRERR`, `LUA_TNIL`, `LUA_TSTRING`, ...). JVM spells the same values out as integers and even defines a `LuaNativeType` private object that nobody uses. The `(1L shl 31)` sentinel in `invokeCallback` is also a magic number — a named constant `INVOKE_ERROR_BIT = 1L shl 31` would document the wire contract with the C side.
- **Fix sketch:** A `commonMain` file `LuaStatus.kt` with `OK=0, ERRRUN=2, ERRSYNTAX=3, ERRMEM=4, ERRERR=5` and a `LuaType.kt` with the type codes — shared by both targets.

---

## Finding R16 — `LuaValue.takeIfNotNil` is a one-liner wrapper for a stdlib idiom

- **Type:** BestPractice
- **Severity:** Info
- **Location:** `src/commonMain/kotlin/pw/binom/lua/LuaValueExtends.kt:95-96`
- **Description:**
  ```kotlin
  val LuaValue.takeIfNotNil
      get() = if (isNil) null else this
  ```
  is exactly `takeUnless { isNil }` (or `if (isNil) null else this` inlined at the call site). Six of the twelve call sites for `orNull()`-style accessors in the same file use the `...OrNull()` + `?: throw ...` pattern; a single `valueOrNull`/`valueOrThrow` family would be more discoverable.
- **Fix sketch:** Replace with `val LuaValue.takeIfNotNil get() = takeUnless { isNil }`, or inline at the (rare) call site.

---

## Finding R17 — Repeated `(1..rawSize).map { ... }` + `of(it.toLong())` pattern across `TableValue.toList` and `TableRef.toList`

- **Type:** Maintainability
- **Severity:** Info
- **Location:**
  - `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:413, 433`
  - `src/posixMain/kotlin/pw/binom/lua/LuaValue.kt:373, 411`
- **Description:** Four copies of `(1..rawSize).map { map[of(it.toLong())] ?: Nil }` (in `TableValue`) and `(1..rawSize).map { get(of(it.toLong())) }` (in `TableRef`). Identical modulo `map[...]` vs `get(...)`.
- **Fix sketch:** `fun Table.toList(): List<LuaValue> = (1..rawSize).map { get(of(it.toLong())) }` in `commonMain` — both `TableValue` and `TableRef` can override with a one-liner delegating to `get`.

---

## Finding R18 — Verbose `LuaValue.TableValue(...)` metatable construction repeated 7 times

- **Type:** BestPractice
- **Severity:** Info
- **Location:**
  - `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:131, 136, 168, 181, 201, 217`
  - `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:144, 161, 181`
- **Description:** `LuaValue.TableValue("__gc".lua to closureAutoGcFunction)` and `LuaValue.TableValue("__gc".lua to userdataAutoGcFunction)` and `LuaValue.TableValue("__call".lua to fnRef, "__gc".lua to gcFnRef)` appear in many places — same shape, different keys. A factory `luaTableOf(vararg pairs: Pair<String, LuaValue>)` or `metatableOf(__gc = ref)` would make the intent obvious.
- **Fix sketch:**
  ```kotlin
  internal fun meta(vararg pairs: Pair<String, LuaValue>) =
      LuaValue.TableValue(*pairs.map { it.first.lua to it.second }.toTypedArray())
  ```
  Use as `meta("__gc" to closureAutoGcFunction)` or `meta("__call" to fnRef, "__gc" to gcFnRef)`.

---

## Finding R19 — `posixMain/LuaValueReader.kt` and `LuaValueWriter.kt` carry commented-out debug noise and `TODO()` placeholders

- **Type:** Maintainability
- **Severity:** WeakWarning
- **Location:**
  - `src/posixMain/kotlin/pw/binom/lua/LuaValueReader.kt:8-10` (`// println("LuaValueReader #-2")` and similar)
  - `src/posixMain/kotlin/pw/binom/lua/LuaValueReader.kt:111` (`val typename: String = "unknown" // TODO()//lua_typename1(this, type)?.toKString()`)
  - `src/posixMain/kotlin/pw/binom/lua/LuaValueWriter.kt:41-44` (commented-out `// is LuaValue.Callable -> TODO()` etc.)
- **Description:** These were left behind by the cleanup in commits `4c8164b` / `8f80c73` (per the task notes). `// TODO()` is not actionable without archaeology, and `// println(...)` debug residue adds noise to every `readValue` call.
- **Fix sketch:** Delete the commented-out debug `println`s; replace the `// TODO()` placeholder with `error("Unknown lua type: $type")` (or, better, `LUA_TTHREAD -> throw UnsupportedOperationException("threads")` etc.).

---

## Finding R20 — `LuaValue.RefObject.callToString` uses magic stack-pop count `2` with an inline comment that already admits the reasoning is brittle

- **Type:** Maintainability / Bug-adjacent
- **Severity:** WeakWarning
- **Location:** `src/posixMain/kotlin/pw/binom/lua/LuaValue.kt:424-432`
- **Description:**
  ```kotlin
  // luaL_tolstring pushes onto the stack when it has to compute a
  // string representation: 0 net change for __tostring metamethod
  // case (luaL_callmeta replaces in place, but here we count
  // callmeta as +1 because Lua uses a copy+replace path) and +1
  // for the default type-fallback cases (numbers, booleans, nil,
  // tables without __tostring). Either way pop(2) balances.
  ```
  Three lines of apology for the magic number. The comment itself says the reasoning is heuristic. A `LuaState.checkState { ... }` (already defined in `posixMain/lua_funcs.kt:18-25`) would catch any imbalance rather than rely on a hand-counted pop.
- **Fix sketch:** Wrap the body in `state.checkState { ... }` and pop the documented delta (1 or 2) based on what `luaL_tolstring` actually pushed; or wrap in `try { ... } finally { lua_settop(state, topBefore) }`.

---

## Finding R21 — `Heap.setPtrFromPtr` parameter name `value` shadows Kotlin stdlib's `value` (and is called with a confusing literal `null` from `createUserData(LightUserData(null))`)

- **Type:** BestPractice
- **Severity:** Info
- **Location:**
  - `src/posixMain/kotlin/pw/binom/lua/Lua.kt:28-31` (`fun setPtrFromPtr(ptr: COpaquePointer, value: COpaquePointer?)`)
  - `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:138` (`Heap.setPtrFromPtr(mem, value = value.lightPtr)`)
  - `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:217-220` (`createAC(value: Any?)` uses `value?.let { StableRef.create(it) }?.asCPointer()` — passes `null` to `LightUserData(null)` which then constructs a `LightUserData(null)` whose `.lightPtr` is null)
- **Description:** The `value` parameter name collides with the extension property `LuaValue.value` and with the parameter on the calling `createAC(value: Any?)` — the call site reads `Heap.setPtrFromPtr(mem, value = value.lightPtr)` and forces the named-arg form to disambiguate. A clearer name (`newPtr`) makes the call site read `Heap.setPtrFromPtr(mem, value.lightPtr)`.
- **Fix sketch:** Rename `value` → `newPtr`; remove the named argument at the call site.

---

## Finding R22 — `ObjectContainer.clear()` in POSIX casts every `StableRef` to `StableRef<LuaFunction>` regardless of stored type

- **Type:** Bug-adjacent (cross-cuts Reuse review only for the *clear pattern*)
- **Severity:** Warning
- **Location:** `src/posixMain/kotlin/pw/binom/lua/ObjectContainer.kt:65-71`
- **Description:** `add(data: Any?)` stores arbitrary `Any`, but `clear()` calls `it.asStableRef<LuaFunction>().dispose()` on every key — `ClassCastException` on any non-`LuaFunction` value. This is a latent bug but relevant here because it is a hand-rolled teardown of a `Map<CPointer, Any>` where the right canonical form is `ptrToObj.values.forEach { (it as? LuaFunction)?.let { /* ... */ } }` or store the type explicitly. The same `clear()` should also remove the dual `objToPtr` keys, which it does — but again, the cast is wrong.
- **Fix sketch:** Iterate `ptrToObj.keys.forEach { it.asStableRef<Any>().dispose() }`. (Also, see R11 for the symmetric removal logic.)

---

## Finding R23 — `ObjectContainer.makeClosure` on JVM is the same shape as `createACClosure` on JVM, but neither calls the other

- **Type:** Design / Maintainability
- **Severity:** Info
- **Location:**
  - `src/jvmMain/kotlin/pw/binom/lua/ObjectContainer.kt:22-50`
  - `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:155-181`
- **Description:** Both register a `LuaCallbackBridge`, push a cfunction, capture its pointer, build a `FunctionRef`, then build a userdata around it. The closure-side (`ObjectContainer.makeClosure`) hands the user a `FunctionValue`; the engine-side (`createACClosure`) hands the user a `UserData` with `__call` metatable. Two near-identical cfunction-registration paths, two independent counters (R10), two ways to get a callback id. The posix side already factored this into one cfunction (`CLOSURE_FUNCTION` in `KotlinLuaFunction.kt:38-92`) used by both `ObjectContainer.makeClosure` and `createACClosure` — JVM should converge.
- **Fix sketch:** Factor the "register cfunction + build FunctionRef + allocate bridge" into one private helper on JVM (analogous to POSIX's `CLOSURE_FUNCTION`).

---

## Finding R24 — `klua_jni.c:570-572` returns raw `lua_pcallk` status; JVM must not assume status mapping

- **Type:** BestPractice
- **Severity:** Info
- **Location:** `src/jvmMain/c/klua_jni.c:570-572` (read for context), `src/jvmMain/.../LuaEngine.kt:80-94, 217-237`, `src/jvmMain/.../LuaValue.kt:438-460`
- **Description:** Already captured under R5 / R15. Restated here as a single observation: the C side passes through whatever status Lua returned, so the JVM cannot redefine the constants without breaking the wire — define them once in `commonMain` (see R15) and use them on every site.

---

## FINDINGS COUNT
24

## SUMMARY
- Dead surface to remove: R2 (`LuaCtx`, `LuaStateAndLib`), R3 (`KotlinLuaFunctions.kt` — empty file), R4 (`OutputVarargs` / `InputVarargs`), R14 (deferred decision on `DebugTool.kt`).
- Real correctness bug hidden in duplicated code: **R5** — JVM has two divergent pcall processors and one of them (`pcallCall`) treats status 2 (LUA_ERRRUN) as "Unknown pcall status", so runtime errors raised through `TableRef.call(...)` / `FunctionRef.call(...)` / `UserData.call(...)` surface as `RuntimeException` instead of `LuaException`. Fix by consolidating into one handler with named status constants.
- Magic numbers that should be named constants (mirroring POSIX side): R6 (LuaNativeType), R15 (status / type ints), R20 (callToString's hand-counted pop).
- Six-line copy-paste of `of(table)` factories: R7.
- Two-counter ID duplication: R10.
- API surface redundancy / dead branches: R9 (`createAC`/`createUserData`), R11 (always-true branch), R12 (`is LuaValue.Nil` after `LuaValue.Nil`).
- Style / stdlib-idiom cleanups: R13 (StdOut vs println), R16 (takeIfNotNil), R17 (toList copy-paste), R18 (metatable factory), R21 (param-name shadowing).
- Residual cleanup the task history calls out: R19 (TODO / commented println leftovers), R22 (`ObjectContainer.clear()` cross-cast), R23 (closure-factory convergence).
