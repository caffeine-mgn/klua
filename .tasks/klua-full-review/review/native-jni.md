# Native/JNI Safety Review — klua

Scope: every file under `src/commonMain/kotlin/pw/binom/lua/`,
`src/jvmMain/kotlin/pw/binom/lua/`, `src/posixMain/kotlin/pw/binom/lua/`,
plus `src/jvmMain/c/klua_jni.c` and `src/nativeInterop/lua.def`.
Focus: use-after-free, dangling references, leaks, GC-vs-native lifetime,
JNI local-frame correctness, stack balance.

Convention: side tag is **JVM** (JNI side), **POSIX** (Kotlin/Native
cinterops side) or **JNI-bridge** (Kotlin glue over JNI). "Lifetime
hazard" tags the class of bug: UAF, double-free / double-unref, leak,
stack-imbalance, detached-thread, false-cache.

---

## Findings

### 1. JVM-side callback "wrapper" LuaContext escapes and becomes a use-after-free after engine.close()

- **Title**: Wrapper LuaContext leaks out via returned TableRef/FunctionRef/UserData
- **Type**: Bug
- **Severity**: Error
- **Location**: `src/jvmMain/kotlin/pw/binom/lua/LuaContext.kt:18-31`
  (`companion object { internal fun wrap(statePtr: Long): LuaContext = LuaContext(statePtr) }`)
  and `src/jvmMain/kotlin/pw/binom/lua/LuaNative.kt:120-137`
  (`@JvmStatic fun invokeCallback(...) { val ctx = LuaContext.wrap(statePtr) ... }`)
- **Description**: Side: **JVM/JNI-bridge**. Lifetime hazard: **UAF**.
  `invokeCallback` builds a brand-new `LuaContext` per callback via the
  private secondary constructor (`statePtr = engineState`). Every
  `TableRef` / `FunctionRef` / `UserData` produced during the callback
  (via `ctx.readValue(idx, ref=true)`, `LuaEngine.createUserData`,
  `ObjectContainer.makeClosure`, etc.) captures this wrapper as `ll`.
  When the callback returns Kotlin-visible `LuaValue`s to the caller
  (e.g. `LuaEngine.eval` returns the result list, `pcallCall` returns
  the value list, `callToString` returns a string), those values are
  retained by JVM user code far past the callback scope.
  The wrapper `LuaContext` is owned by nobody — it is a stack value
  that "must not escape the callback scope" (file comment line 28).
  Crucially, `LuaContext.close()` (the engine's own `ll.close()` at
  `LuaContext.kt:108-115`) sets **only the engine's `ll.statePtr` to
  0L**. The wrapper's `statePtr` (a different `LuaContext` instance
  with a different field slot) is never nulled. After `engine.close()`
  runs `lua_close`, the wrapper still carries the now-freed pointer.
  Once the JVM eventually GC's the wrapper-bound `TableRef` /
  `FunctionRef` / `UserData`, their `RefAction.run()` (LuaValue.kt:335-345)
  reads `ll.state` — still the freed pointer — and calls
  `LuaNative.unref(<freed ptr>, REGISTRY, refId)` and
  `LuaNative.rawGetI(<freed ptr>, REGISTRY, ...)` on a dangling
  `lua_State*`. Symptom: SEGV / heap corruption in
  `klua_jni.c` unmarshal; intermittent crashes that survive only if
  the OS hasn't reused the freed page.
- **Fix sketch**: ensure that any `LuaValue` returned to JVM user code
  carries the *engine's* `LuaContext`, not a throwaway wrapper. Either
  (a) keep a `statePtr → LuaContext` map in `LuaNative` and have
  `wrap` return the existing engine context (preferred — matches the
  POSIX side's `LuaContextRegistry` idea), or (b) make `LuaContext`
  expose `statePtr` as a `var` and have the wrapper register itself
  with the engine so `engine.close()` nulls all wrappers in one shot.
  Whichever path, also wire the wrapper into the registry
  teardown path that `LuaContext.close()` walks so the same null-out
  reaches wrappers. Add a regression test that returns a `TableRef`
  from a callback, then closes the engine, then forces GC of the
  ref — must not crash.

---

### 2. JVM: `UserData.dispose()` does not cancel its Cleaner → double `luaL_unref` corrupts registry freelist

- **Title**: `UserData.dispose()` triggers a second registry unref from the Cleaner
- **Type**: Bug
- **Severity**: Error
- **Location**: `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:60-67`
  (the `dispose()` method on `actual class UserData`):
  ```kotlin
  fun dispose() {
      val p = ptr ?: return
      StaticRefs.dispose(p)
      LuaNative.unref(ll.state, LUA_REGISTRYINDEX, refId)
  }
  ```
  and the Cleaner registration at line 28-34 (`RefAction(ll, refId)`)
  which is never cancelled by `dispose()`.
- **Description**: Side: **JVM**. Lifetime hazard: **double-unref →
  registry freelist corruption**. `dispose()` is a public method; any
  caller (tests included) may invoke it. It calls
  `LuaNative.unref(statePtr, REGISTRY, refId)` once. When the JVM
  `UserData` wrapper later becomes phantom-reachable, the registered
  `RefAction.run()` (LuaValue.kt:336-344) runs and calls
  `LuaNative.unref(statePtr, REGISTRY, refId)` again with the same
  `refId`. `luaL_unref` on an already-unreferenced refId writes the
  current freelist head into `registry[refId]` and pushes `refId`
  itself into the freelist. With the registry already cleared, the
  freelist head IS `refId`, producing a self-loop. Subsequent
  `luaL_ref` returns the same `refId` repeatedly and `registry[refId]
  = refId` — the registry is now corrupted: every fresh `luaL_ref`
  aliases the same slot. Symptom: cross-tenant aliasing of tables,
  functions, or userdata; intermittent crashes in `luaH_getn` /
  `luaV_execute` depending on what was aliased. Trigger: any explicit
  `UserData.dispose()` call followed by GC of the wrapper.
- **Fix sketch**: in `UserData.dispose()`, call `cleanable.clean()`
  before the `unref` (the Cleaner action must run no further). Same
  pattern for any future "explicit dispose + implicit Cleaner"
  wrapper. Add a test that calls `dispose()`, drops the reference,
  forces GC, and asserts `LuaNative.registrySize` matches the
  pre-dispose count (i.e. no extra nil-slot was added by the second
  unref).

---

### 3. JVM: JNI `setIUservalue` ignores its `jlong v` parameter and consumes an unrelated stack top

- **Title**: `Java_pw_binom_lua_LuaNative_setIUservalue` discards its argument and pops whatever is on stack
- **Type**: Bug
- **Severity**: Error
- **Location**: `src/jvmMain/c/klua_jni.c:677-681`
  ```c
  JNIEXPORT void JNICALL Java_pw_binom_ua_LuaNative_setIUservalue(
      JNIEnv* env, jclass cls, jlong statePtr, jint idx, jlong v) {
      (void)env; (void)cls;
      lua_setiuservalue(jlong_to_lua_state(statePtr), (int)idx, 1);
      (void)v;
  }
  ```
- **Description**: Side: **JVM/JNI-bridge**. Lifetime hazard: **stack
  corruption + silent uservalue poisoning**. `lua_setiuservalue`
  expects the value to assign to be on the **stack top** and pops it;
  the `jlong v` argument is silently thrown away. The Kotlin signature
  is `external fun setIUservalue(state: Long, idx: Int, v: Long)` —
  a caller passing a pointer via `v` would assume it landed in the
  uservalue slot. Instead the C side pops whatever happens to be at
  the top of the Lua stack at that moment (often the userdata itself
  or a residual from a previous op). The Kotlin caller does not push
  anything first, so today the call path is dead — but the moment
  someone wires it up believing the `v` parameter is the value, the
  uservalue slot gets an unrelated Lua value and the `jlong v` is
  lost. Trivial trigger once the function is used: assign → wrong
  pointer stored; later `getIUservalue` returns garbage.
- **Fix sketch**: convert to a contract where the JNI side pushes
  the value onto the stack, then calls `lua_setiuservalue`. Two
  valid options: (a) take the value as a `jstring` (then push
  `lua_pushstring` from `GetStringUTFChars`); (b) keep it as `jlong`
  and `lua_pushlightuserdata(L, jlong_to_ptr(v))` before the
  setiuservalue. The current "ignore `v`, pop top" form must not
  ship. Add an integration test that calls the function and asserts
  the uservalue equals the argument passed.

---

### 4. POSIX: `LuaEngine.close()` is empty — engine never releases the Lua state

- **Title**: `LuaEngine.close()` does nothing on POSIX, leaving native state to GC
- **Type**: Bug
- **Severity**: Error
- **Location**: `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:22-24`
  ```kotlin
  actual override fun close() {
  }
  ```
- **Description**: Side: **POSIX**. Lifetime hazard: **resource leak
  + delayed UAF surface**. `close()` is a no-op. The Lua state,
  registry, and all live userdata are only freed once the
  `LuaContext` becomes phantom-reachable (see `LuaContext.kt:18-22`,
  `createCleaner(state) { lua_close(it) }`). For tests or libraries
  using `use { engine -> ... }`, the engine keeps every Lua-side
  allocation alive until the surrounding scope fully goes out of
  reach. Worse: there is no explicit null-out of `ll.state` after
  close, so any stray `LuaValue.RefObject` whose Cleaner fires after
  a *programmer* believes the engine was closed will still touch
  live state (today safe only because state is still alive). The
  next engineer who adds an explicit `close()` that nulls `ll.state`
  will silently turn this into the JVM-style UAF without realising
  that POSIX has no equivalent of `LuaContext.wrap`'s nulling.
- **Fix sketch**: implement `close()` to mirror the JVM side — call
  `lua_close(ll.state)` and null `ll.state` so Cleaners become
  no-ops. The existing `createCleaner` can stay as a backstop for
  forgotten-`close` cases (mirroring the JVM pattern of "explicit
  close + GC safety net").

---

### 5. POSIX: `LuaContextRegistry` is a single global slot — concurrent / multi-engine engine collapses

- **Title**: Single-slot global registry of `LuaState → LuaContext` is corrupted by any second engine
- **Type**: Bug
- **Severity**: Error
- **Location**: `src/posixMain/kotlin/pw/binom/lua/LuaContext.kt:37-55`
  (`LuaContextRegistry`, especially `register` and `lookup`)
- **Description**: Side: **POSIX**. Lifetime hazard: **silent
  callback failure → cross-engine memory leak / UAF**. When a second
  `LuaEngine` is constructed after a first, the registry's
  `register` overwrites `current` with the new engine's `(state,
  ctx)`. Any callback (`CLOSURE_FUNCTION`, `userdataGc`) still
  owned by the **first** engine but called after the second engine
  is created will `LuaContextRegistry.lookup(state) → null`
  (because `cur.first !== state`), so `CLOSURE_FUNCTION` returns 0
  results and `userdataGc` returns 0 without disposing the
  StableRef. Concrete consequences: (a) AC userdata allocated under
  the first engine never get their StableRef disposed — leak until
  process exit; (b) the first engine's `LuaContext` is no longer
  referenced by the registry, so when its `cleaner` finally fires,
  the registry unregister is a no-op but `lua_close` still runs —
  this is benign on its own but the symmetric case (second engine's
  callbacks firing against the first engine's state) is the UAF.
  Fix only kicks in if multi-engine is exercised; tests run in
  separate processes (per file comment), which is why this has
  survived.
- **Fix sketch**: replace the single-slot registry with a
  `CPointer<lua_State> → LuaContext` map (`kotlinx.cinterop`'s
  `mutableMapOf` keyed by `LuaState` is sufficient since
  `CPointer` equality is identity-based). `lookup` walks the map,
  `register`/`unregister` add/remove. Without a map, document loudly
  that creating two engines is unsupported and add an
  assertion/exception in the constructor.

---

### 6. POSIX: `CLOSURE_FUNCTION` heuristic strips the first userdata arg even for direct calls

- **Title**: The "__call self-detection" heuristic mis-strips a userdata that is a legitimate first argument
- **Type**: Bug
- **Severity**: Error
- **Location**: `src/posixMain/kotlin/pw/binom/lua/KotlinLuaFunction.kt:78-92`
  (the `args = if (count > 0 && lua_type(state, 1) == LUA_TUSERDATA) { ... } else { ... }`
  branch of `CLOSURE_FUNCTION`)
- **Description**: Side: **POSIX**. Lifetime hazard: **wrong arg
  count + dropped reference → silent memory leak in `ObjectContainer`
  closures**. The trampoline decides between a direct call `f(a,
  b, c)` and a userdata-with-`__call` invocation `obj(a, b, c)` by
  checking whether the first stack slot is `LUA_TUSERDATA` and, if
  so, treating it as the synthetic self and starting from index 2.
  The same cfunction serves both `ObjectContainer.makeClosure(func)`
  (a plain 1-upvalue closure) and `LuaEngine.createACClosure(func)`
  (a 1-upvalue closure installed as `__call`). For an AC closure,
  Lua always prepends the userdata; the heuristic is correct. But
  for a plain `ObjectContainer.makeClosure` callback, if the user
  code calls the closure with a userdata as its first argument
  (e.g. `container.makeClosure { ud -> ... }` then
  `someUd:method(...)` or `closure(someUd, x)`), the cfunction
  silently drops that userdata — it is neither passed to
  `LuaFunction.call(args)` nor re-pushed. Result: the user's
  closure sees `(args.size - 1)` items with the first one missing.
  Because the dropped userdata had no other reference on the Lua
  stack during the call, the closure dropped it from GC roots → the
  userdata's __gc fires after the closure returns → StableRef
  disposed → Kotlin object orphaned. This is a real leak when a
  common pattern (passing a userdata as the first arg) is used.
- **Fix sketch**: distinguish AC vs plain via the upvalue itself.
  Use two separate cfunctions (e.g. `CLOSURE_FUNCTION` and
  `AC_CLOSURE_FUNCTION`) — the AC one always strips index 1; the
  plain one never does. `ObjectContainer.makeClosure` registers
  `CLOSURE_FUNCTION`; `LuaEngine.createACClosure` registers
  `AC_CLOSURE_FUNCTION`. The previous "AC_CLOSURE_PTR sentinel"
  attempt (referenced in the file comment) was discarded because
  it conflated the two roles — restoring the split is the right
  fix.

---

### 7. JVM: `createACClosure` retains two registry entries per closure with no unref path on the Kotlin side

- **Title**: Per-closure `fnRef` and `gcFnRef` registry entries depend on Kotlin-side FunctionRef wrapper GC
- **Type**: Bug
- **Severity**: Warning
- **Location**: `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:170-189`
  (the `createACClosure` block; in particular the `fnRef =
  LuaValue.FunctionRef(...)` and `gcFnRef = LuaValue.FunctionRef(...)`
  constructions)
- **Description**: Side: **JVM**. Lifetime hazard: **transient
  registry leak**. Each call to `createACClosure` creates two
  `LuaValue.FunctionRef` instances (`fnRef` for `__call`, `gcFnRef`
  for `__gc`), each adding one entry to `LUA_REGISTRYINDEX`. Those
  `FunctionRef` wrappers are not returned to the caller and are only
  reachable through the userdata's metatable (`TableValue("__call"
  to fnRef, "__gc" to gcFnRef)`). The metatable itself is a Kotlin
  `HashMap`, not a Lua table — so once the `FunctionRef` wrappers
  are GC'd, their `RefAction.run()` unrefs the registry entries. The
  wrappers DO get GC'd because nothing in Kotlin keeps them alive
  (nothing references `fnRef` or `gcFnRef` outside the table literal),
  and `RefAction` does not capture `this`. So this is a transient
  leak of two registry slots until the next GC cycle, not a
  permanent leak. Trigger: a tight loop of `createACClosure` calls
  will balloon the registry by ~2*N entries until the next JVM GC.
  Commit 02de357 made GC stress visible; this is the same shape.
- **Fix sketch**: have the metatable be a Lua table (`LuaValue.Table`)
  rather than a `TableValue`, so the function refs are owned by a
  Lua root keyed off the userdata lifetime (and freed when the
  userdata's metatable is collected). Or — simpler — wrap the two
  registry entries into a single userdata payload so a single
  Cleaner unrefs both. Either way, the registry growth under
  `createACClosure` stress tests should drop to zero.

---

### 8. POSIX: Stack imbalance in `pcallProcessing` `LUA_ERRRUN` branch when error is not a single string

- **Title**: Multi-value Lua errors leak entries on the stack after `luaL_traceback`
- **Type**: Bug
- **Severity**: Warning
- **Location**: `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:194-216`
  (the `LUA_ERRRUN` branch of `pcallProcessing`):
  ```kotlin
  val message = if (lua_gettop(luaLib.state) == 1 && lua_isstring(luaLib.state, 1) != 0) {
      val str = lua_tostring(luaLib.state, 1)
      lua_pop(luaLib.state, 1)
      str
  } else {
      null
  }
  luaL_traceback(luaLib.state, luaLib.state, message, 1)
  val fullMessage = lua_tostring(luaLib.state, -1)
  lua_pop(luaLib.state, 1)
  ```
- **Description**: Side: **POSIX**. Lifetime hazard: **stack
  imbalance → unbounded growth under repeated errors**. `luaL_traceback`
  pushes a string and leaves the existing stack intact. The
  single-string branch pops the lone error first, so the net effect
  is balanced. The else branch passes `null` to `luaL_traceback`
  and pops only the traceback string at the end — leaving N original
  error values on the stack when N > 1. `LuaEngine.eval` is invoked
  inside `LuaContext` whose `checkState` runs a `check(newTop == top)`
  assertion in `finally`, so this would actually trigger a
  `check { "Invalid Stack Size. Expected: $top, Actual: $newTop" }`
  failure right after the throw, masking the original `LuaException`
  with a Kotlin `IllegalStateException`. Symptom: confusing errors
  under "error handler error" scenarios; stack growth otherwise.
- **Fix sketch**: in the else branch, pop the existing error values
  after extracting the message (or, better, convert to string first
  while the values are still on the stack). A simpler refactor is to
  always call `luaL_traceback` and then unconditionally `lua_pop(L, 1)`
  to consume the traceback, but also pop any pre-existing error
  values before the traceback call.

---

### 9. JVM: `LuaValue.UserDataAction` class is dead code (registered Cleaner is `RefAction`)

- **Title**: `UserDataAction` private class is never wired into the Cleaner
- **Type**: Maintainability
- **Severity**: WeakWarning
- **Location**: `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:70-94`
  (the `private class UserDataAction(...)` block)
- **Description**: Side: **JVM**. Lifetime hazard: **none directly,
  but masks intent**. The `UserData` class registers `RefAction(ll,
  refId)` (line 33) instead of its own bespoke
  `UserDataAction(ll, refId)` Cleanup class. The bespoke class —
  which additionally resolves the Lua userdata pointer and disposes
  the `StaticRefs` entry — is defined but never instantiated. The
  comments inside the class (e.g. "Resolve the userdata's memory
  address from the registry entry, then drop both the StaticRefs
  entry and the registry reference") describe behaviour the
  current `RefAction` does NOT have: `RefAction` only `luaL_unref`s.
  The static-refs cleanup happens via the C-side `userdataGc`
  trampoline instead. The dead class implies an intent to do that
  work JVM-side; if a future reader assumes the comment describes
  the live code path, they may remove the C-side trampoline and
  break `createUserData(Any)`.
- **Fix sketch**: either (a) delete `UserDataAction` if the
  C-trampoline path is the desired design, and update the comment
  in `RefAction` to note that StaticRefs is disposed by Lua __gc;
  or (b) actually wire `UserDataAction` into `UserData.cleanable`
  and remove the C-side `userdataGc` trampoline for the
  `createUserData` path. Whichever is chosen, the JNI side should
  match.

---

### 10. POSIX: `ObjectContainer.removeClosure(FunctionValue)` reports success without disposing non-LuaFunction upvalues

- **Title**: `removeClosure` claims success while leaking the upvalue StableRef
- **Type**: Bug
- **Severity**: Warning
- **Location**: `src/posixMain/kotlin/pw/binom/lua/ObjectContainer.kt:54-63`
  ```kotlin
  actual fun removeClosure(data: LuaValue.FunctionValue): Boolean {
      if (data.upValues.size != 1) {
          return false
      }
      val func = data.upValues[0]
      if (func is LuaValue.LightUserData && func.value is LuaFunction) {
          func.dispose()
          return true
      }
      return true
  }
  ```
- **Description**: Side: **POSIX**. Lifetime hazard: **StableRef leak
  + false success**. When `upValues.size == 1` but the single
  upvalue is not a `LuaFunction`-typed LightUserData, the function
  returns `true` (caller assumes cleanup happened) without calling
  `func.dispose()`. The `StableRef` underneath that LightUserData
  never gets disposed, so the wrapped Kotlin object remains rooted
  for the lifetime of the `ObjectContainer` (or forever, if the
  container is itself never cleared). This is reachable when a
  user pushes a `FunctionValue` with upvalues that are arbitrary
  lightuserdata not holding a LuaFunction — e.g. a custom userdata
  bridge. Today the API does not really support that, so this is a
  latent footgun, not a live leak.
- **Fix sketch**: in the else branch, also call
  `if (func is LuaValue.LightUserData) func.dispose()` before
  returning `true`, or — better — assert that the upvalue is in fact
  a `LightUserData` wrapping a `LuaFunction` and throw if not. The
  contract is then: `removeClosure(FunctionValue)` is only valid for
  closures produced by `makeClosure`.

---

### 11. POSIX: `ObjectContainer.clear()` casts every key's StableRef to `StableRef<LuaFunction>` even when the slot holds arbitrary `Any`

- **Title**: `clear()` disposes arbitrary StableRefs under a wrong type witness
- **Type**: Bug
- **Severity**: Warning
- **Location**: `src/posixMain/kotlin/pw/binom/lua/ObjectContainer.kt:69-74`
  ```kotlin
  actual fun clear() {
      ptrToObj.keys.forEach {
          it.asStableRef<LuaFunction>().dispose()
      }
      ptrToObj.clear()
      objToPtr.clear()
  }
  ```
- **Description**: Side: **POSIX**. Lifetime hazard: **wrong type
  cast (incidental) — currently safe but fragile**. `ptrToObj` is a
  `HashMap<COpaquePointer, Any>` populated by both
  `makeClosure(func)` (where the value IS a `LuaFunction`) and
  `add(data)` (where the value is arbitrary). `clear()` iterates
  ALL keys and calls `asStableRef<LuaFunction>().dispose()`. Today
  this is benign because `StableRef.dispose()` does not actually
  type-check the underlying slot — Kotlin/Native's `asStableRef` is
  a reinterpret cast that ignores the witness type at runtime. So
  this works by accident: any object stored via `StableRef.create`
  is freed regardless of the witness. But this is a Kotlin/Native
  implementation detail — the moment a version bumps the runtime to
  validate witness types, `clear()` will start crashing on
  non-LuaFunction values added via `add()`. Also, the surrounding
  Kotlin code implies a contract that closures are explicitly
  cleared but arbitrary `add()` data is not, contradicting the
  map-keyed cleanup this method does.
- **Fix sketch**: separate the two maps by intent (closures vs
  arbitrary data), or filter the keys by `objToPtr.values.any { it
  is LuaFunction }`. Document the actual behaviour: `clear()`
  disposes every StableRef registered through either `makeClosure`
  or `add`.

---

### 12. JVM: `StaticRefs.dispose(ptr)` races with native `disposeUserdata(mem)` on the same key

- **Title**: Both Kotlin-side Cleaners and the C-side userdata __gc trampoline drop the same StaticRefs entry
- **Type**: Concurrency
- **Severity**: Warning
- **Location**: `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:81-94`
  (`UserDataAction.run` calls `StaticRefs.dispose(p)`),
  `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:122-124`
  (`StaticRefs.store(mem, underlying)` followed by
  `StaticRefs.dispose(value.ptr)`),
  and `src/jvmMain/c/klua_jni.c:205-217` (`klua_userdata_gc_trampoline`
  → `disposeUserdata(mem)` → `StaticRefs.dispose(mem)`).
- **Description**: Side: **JVM**. Lifetime hazard: **idempotent
  removal (HashMap.remove) — currently safe but masked race**.
  Three sites drop the same `StaticRefs` entry: (a) the JVM-side
  `UserDataAction` Cleaner when the `UserData` wrapper is GC'd;
  (b) the C-side `klua_userdata_gc_trampoline` when Lua collects
  the userdata; (c) `createUserData(LightUserData)` which manually
  calls `StaticRefs.dispose(value.ptr)` to drop the temporary
  LightUserData slot. `StaticRefs.dispose` is a synchronized
  `HashMap.remove` and idempotent — fine in isolation. But the
  combination produces two distinct windows where the same Kotlin
  object appears to be alive in `StaticRefs` after the userdata has
  been disposed, allowing user code that calls `engine.getUserdata(mem).value`
  to observe a "resurrected" object whose Lua side is already torn
  down. Not a crash, but a use-after-finalize pattern that some
  tests rely on (the LightUserData branch).
- **Fix sketch**: make one of the two paths authoritative. Either
  (a) drop the JVM-side `StaticRefs.dispose(p)` from `UserDataAction`
  and rely solely on the C trampoline, or (b) drop the C-side
  `disposeUserdata(mem)` path and let the JVM Cleaner own the
  dispose. Whichever, document the ownership and add a test that
  asserts the StaticRefs entry is gone by the time the userdata is
  unreachable on both sides.

---

### 13. JVM: `LuaNative.invokeCallback` returns `(1L shl 31) or 0` sentinel but the C side parses `(result >> 31) & 1` as `errFlag` — works only because the count never exceeds 2^31

- **Title**: 32-bit truncation assumption in callback return value
- **Type**: Design
- **Severity**: Info
- **Location**: `src/jvmMain/kotlin/pw/binom/lua/LuaNative.kt:129-135`
  (`return nresults.toLong()` and `return (1L shl 31) or 0`)
  versus `src/jvmMain/c/klua_jni.c:149-155`
  (`int nresults = (int)(result & 0x7FFFFFFFL); int errFlag = (int)((result >> 31) & 1);`).
- **Description**: Side: **JVM/JNI-bridge**. Lifetime hazard: **none
  — informational**. The Kotlin return uses `nresults.toLong()` which
  may exceed `0x7FFFFFFF` for very long result lists. The C side
  masks with `0x7FFFFFFF` so high bits are silently dropped, and
  reads bit 31 as an `errFlag`. Today `nresults` is the size of the
  callback's `List<LuaValue>`, which is bounded by the JVM heap —
  but a malicious or buggy callback could return a list large enough
  to set bit 31 and have its result count silently clamped to a
  small number, with Lua interpreting the high bit as an error and
  throwing via the `lastErrorMessage` path. This is more a design
  smell than a lifetime bug but it lives in the JNI bridge.
- **Fix sketch**: widen the return to two separate jlong channels
  (e.g. `Java_pw_binom_lua_LuaNative_invokeCallback` returning
  `jlong` for `nresults | (errFlag << 63)` and use the full 63 bits
  for count), or split into two methods: one returning `jint
  nresults`, one returning a separate `jboolean ok`. The current
  bit-packing is brittle.

---

### 14. JVM: `JVM 21.0.11 GC bug trips TableRef stress tests` (referenced in 02de357) — the readValue type-5 ref branch duplicates via pushValue+ref which creates a stale relative index reference under concurrent ref

- **Title**: `TableRef.rawGet` regression risk if `readValue(type=5, ref=true)` ever runs on an already-removed stack slot
- **Type**: Concurrency
- **Severity**: Info
- **Location**: `src/jvmMain/kotlin/pw/binom/lua/LuaContext.kt:79-85`
  (the `LUA_TTABLE → if (ref)` branch of `readValueAt`):
  ```kotlin
  LuaNative.pushValue(statePtr, index)
  val refId = LuaNative.ref(statePtr, LUA_REGISTRYINDEX)
  val ptr = LuaNative.toPointer(statePtr, index)
  LuaValue.TableRef(refId, ptr, this)
  ```
- **Description**: Side: **JVM**. Lifetime hazard: **stale
  index under GC stress (history) + latent future regression**.
  The sequence `pushValue(index) → ref() → toPointer(index)` is
  correct *in a single-threaded read* — `luaL_ref` pops the
  duplicate, leaving the stack at the same depth as before, so
  `index` still refers to the same Lua table. Under concurrent
  reads from multiple JVM threads against the same `lua_State`,
  there is no synchronisation (Lua 5.4 is not thread-safe), so two
  concurrent `readValue` calls would interleave `pushValue`s and
  re-index everything. The codebase's `pcallProcessing` only
  drives one JVM thread at a time via the engine, but
  `LuaContext.wrap` from `invokeCallback` re-enters readValue from
  the C trampoline thread, which (if it differs from the calling
  thread — e.g. `lua_resume` in a yielded coroutine) could
  interleave. This was the original JNI GC stress trigger per
  commit 02de357.
- **Fix sketch**: the wrapper-fix in finding #1 already removes the
  cross-thread entry point. Beyond that, document explicitly that
  `lua_State*` is single-threaded and that no concurrent
  `readValue` from the JVM side is supported; add an assertion in
  `readValue` that the call originates from the "owner thread"
  (track via a `ThreadLocal<LuaState>` or compare against a stored
  `Thread` reference).

---

### 15. POSIX: `LuaValue.makeRef(FunctionValue)` uses `!!` on `lua_topointer` which may legitimately be NULL

- **Title**: `lua_topointer` is nullable but force-unwrapped in `makeRef`
- **Type**: Bug
- **Severity**: WeakWarning
- **Location**: `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:84-93`
  (both `makeRef(FunctionValue)` and `makeRef(TableValue)` use `lua_topointer(ll.state, -1)!!`)
- **Description**: Side: **POSIX**. Lifetime hazard: **NPE on
  legitimate Lua value (not lifetime, but in the JNI/native
  bridge)**. `lua_topointer` returns NULL for booleans, nil,
  numbers without internal pointers, lightuserdata of certain
  shapes, and any value Lua does not track via a GC object. Both
  `makeRef` overloads use `!!` — so wrapping a non-pointer Lua
  value as a `FunctionRef`/`TableRef` will NPE at construction. The
  function is called only from user code that already typed the
  value as `FunctionValue`/`TableValue`, so the immediate crash is
  reachable from legitimate input (e.g. `engine.makeRef(LuaValue.
  FunctionValue(ptr = null, upValues = ...))`).
- **Fix sketch**: replace `!!` with a fallback (e.g. `0L` or a
  synthetic id) and document that the `ptr` field of `FunctionRef`
  / `TableRef` is advisory. The C-side `LuaValue.FunctionRef.ptr`
  is used only for `equals`/`hashCode` and `toString` — making
  it nullable is a small breaking change but cleaner than NPE.

---

### 16. JVM: `ObjectContainer.add(data)` keys `objToPtr` by `Any.hashCode/equals` not identity

- **Title**: `ObjectContainer` deduplicates by `equals`, not by identity
- **Type**: Design
- **Severity**: WeakWarning
- **Location**: `src/commonMain/kotlin/pw/binom/lua/ObjectContainer.kt:11-14`
  (the API contract) and `src/posixMain/kotlin/pw/binom/lua/ObjectContainer.kt:13-24`
  (the POSIX impl with `if (exist != null) return LuaValue.LightUserData(exist)`)
- **Description**: Side: **POSIX** (no JVM equivalent — JVM uses
  `StaticRefs.intern` which is also equality-keyed but per-key is
  one-shot). Lifetime hazard: **aliasing two distinct objects into
  one StableRef, then disposing one invalidates the other**.
  `objToPtr` is a plain `HashMap` whose key is the data object
  itself, deduplicating by `equals`. Two distinct objects that
  compare equal will share one StableRef; calling `remove(one)` will
  dispose the StableRef and silently invalidate the other. Symptom:
  the "kept" object now has a stale CPointer inside its
  LightUserData; any subsequent `LightUserData.value` returns null
  (or worse, returns the wrong object if the StableRef slot was
  reused).
- **Fix sketch**: switch to `IdentityHashMap` (POSIX: `mutableMapOf`
  with an identity wrapper, since `kotlinx.cinterop` doesn't ship
  one). JVM equivalent: `StaticRefs.intern` could expose an
  identity variant. Add a comment in the API doc that the
  container does not deduplicate by identity.

---

### 17. JVM: `NativeLoader.extractFromJar` writes to a shared temp dir with no locking

- **Title**: Shared-temp-dir extract races and produces half-written shared libraries
- **Type**: Concurrency
- **Severity**: Warning
- **Location**: `src/jvmMain/kotlin/pw/binom/lua/NativeLoader.kt:14-23`
  (the `if (!cached.exists()) extractFromJar(...)` block)
- **Description**: Side: **JVM**. Lifetime hazard: **half-written
  `.so`/`dylib`/`.dll` can be `dlopen`-ed by the next JVM, causing
  native crashes during class init**. The check
  `if (!cached.exists())` is not atomic with the subsequent
  `FileOutputStream(...).copyTo(...)`. Two JVMs (or two classloaders)
  extracting concurrently to the same `jlua/1.2.0-debug/libklua.so`
  will see each other's half-written file. Even with `loaded.add(...)`
  guarding `System.load`, the `cached.exists()` check happens
  before the add. Also, no `setExecutable` after writing on POSIX
  — depends on umask preserving +x from `copyTo`. Not a lifetime
  bug per se but it lands in the native loader path.
- **Fix sketch**: write to a temp file in the same dir, then
  `Files.move(... REPLACE_EXISTING ATOMIC_MOVE)` into place. After
  writing, `File.setReadable(true, false)` /
  `setExecutable(true, false)` defensively. Add a `@Synchronized`
  on the extract path or use `Files.createDirectories(...)`
  followed by a per-file `Files.newByteChannel(... CREATE_NEW)`
  which fails atomically if the file is already present.

---

### 18. JVM: `JNI_OnLoad` caches `stringClass`, `luaNativeClass` as GlobalRefs but never releases them

- **Title**: Two GlobalRefs leak for the lifetime of the JVM
- **Type**: Bug
- **Severity**: WeakWarning
- **Location**: `src/jvmMain/c/klua_jni.c:48-66`
  (the `JNI_OnLoad` body)
- **Description**: Side: **JVM/JNI-bridge**. Lifetime hazard:
  **GlobalRef leak** (static, two refs per JVM). The cache of
  `stringClass` and `luaNativeClass` is correct (and required for
  cross-thread use), but there is no matching `JNI_OnUnload` to
  `DeleteGlobalRef` them. Two GlobalRefs at shutdown is harmless
  in practice, but if `JNI_OnLoad` is ever called more than once
  (e.g. a future classloader reload), each call leaks another two.
- **Fix sketch**: add `JNI_OnUnload` that does
  `(*env)->DeleteGlobalRef(env, stringClass);` and same for
  `luaNativeClass`, null the statics. Document the invariant.

---

### 19. JVM: `LuaNative.callbacks` map is unbounded across many short-lived `ObjectContainer`s if any container's BridgeCleaner is suppressed (e.g. by OOM during GC, or by a Cleaner race)

- **Title**: `LuaNative.callbacks` may grow unbounded across short-lived containers under Cleaner races
- **Type**: Concurrency
- **Severity**: WeakWarning
- **Location**: `src/jvmMain/kotlin/pw/binom/lua/ObjectContainer.kt:23-37`
  (the `BridgeCleaner` registration block) and `src/jvmMain/kotlin/pw/binom/lua/LuaNative.kt:99-114`
  (`setCallback` / `unregisterCallback`).
- **Description**: Side: **JVM**. Lifetime hazard: **unbounded
  growth**. The `BridgeCleaner` captures a `(bridgeId, unregister
  lambda)` and the lambda calls `LuaNative.unregisterCallback(id)`.
  Java's Cleaner is best-effort: under OOM or if the Cleaner
  thread is starved, an action can be skipped. If even one bridge
  per N containers is skipped, the `callbacks` map grows by 1 each
  time. The commit 8a7374d note explicitly mentions this race. A
  secondary concern: a `Container` that is GC'd while a cfunction
  the container registered is still alive in Lua (the cfunction's
  upvalue holds the id) cannot trigger Cleaner because the cfunction
  is Lua-side reachable, but the Kotlin `BridgeCleaner` action runs
  in parallel. If the action runs FIRST (cleaner thread wins the
  race), it removes the bridge; the cfunction is still in Lua,
  fires later, looks up `callbacks[id]` → null, returns 0 results.
  Silent failure path.
- **Fix sketch**: tie the bridge unregistration to the C-side
  `klua_gc_trampoline` (the same pattern already used for AC
  closures per d90a8f8). Each cfunction gets a dedicated `__gc`
  that calls `disposeCallback(id)`. Then the bridge lifetime is
  bounded by the cfunction's Lua lifetime, not by JVM Cleaner
  semantics. Keep `BridgeCleaner` as a backstop but stop relying on
  it as the primary release path.

---

### 20. JVM: `LuaValue.LightUserData(ptr)` secondary constructor interns on every construction — interns for `null` produce a non-null ptr that aliases every other "null" instance

- **Title**: `LightUserData(null)` interns to a real (non-null) key, breaking identity of null holders
- **Type**: Design
- **Severity**: WeakWarning
- **Location**: `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:104-109`
  (`actual constructor(value: Any?) : this(StaticRefs.intern(value))`)
- **Description**: Side: **JVM**. Lifetime hazard: **aliasing →
  cross-talk between distinct null holders**. `StaticRefs.intern(null)`
  stores `null` under a fresh counter key and returns that key.
  Every `LightUserData(null)` therefore points to a different
  counter key, so this is NOT actually an aliasing bug — but the
  comment "interns on every construction" hides the fact that the
  returned `ptr` is always non-null even when the original was null,
  which makes `ptr == null` checks (e.g. in `createACClosure`'s
  `if (value.ptr != null) StaticRefs.dispose(value.ptr)`) silently
  take the wrong branch. Specifically, the createACClosure flow
  uses `LightUserData(null)` via the **primary** constructor
  (`LightUserData(ptr: Long?)`) so `value.ptr` is indeed null and
  the dispose is skipped. But any user code calling the secondary
  constructor with `null` produces a non-null `ptr`, and the
  Cleaner (`LightUserDataAction(p)`) disposes a slot that was
  storing null — fine, just one extra dispose. The trap is the
  appearance of consistency: two `LightUserData(null)` instances
  with secondary ctor have different `ptr` fields, but the user's
  mental model is "null means null".
- **Fix sketch**: in the secondary constructor, special-case
  `value == null` to call `this(null: Long?)` (the primary
  constructor) instead of `intern(null)`. Add a unit test that
  asserts `LightUserData(null).ptr == null`.

---

## FINDINGS COUNT
20

## SUMMARY
- **3 Error-severity JVM/JNI lifetime bugs**: (1) `LuaContext.wrap`
  escapes and dangles after engine close — use-after-free of
  `lua_State*`; (2) `UserData.dispose()` doubles `luaL_unref` via
  the Cleaner → registry freelist corruption; (3) JNI
  `setIUservalue` silently discards its `v` argument and pops an
  unrelated stack top.
- **3 Error-severity POSIX lifetime/correctness bugs**: (4)
  `LuaEngine.close()` is empty — no explicit teardown; (5) global
  single-slot `LuaContextRegistry` collapses under multi-engine
  use; (6) `CLOSURE_FUNCTION` heuristic mis-strips a userdata
  passed as a legitimate first argument.
- **2 Warning-level stack/lifetime issues**: (8) `pcallProcessing`
  stack imbalance on multi-value errors; (7) per-closure registry
  transient leak in JVM `createACClosure`.
- **6 WeakWarning/Info**: dead `UserDataAction` class, JVM
  `bitmap-packed` callback return, POSIX `!!` on
  `lua_topointer`, POSIX `ObjectContainer.clear()` casting,
  POSIX `removeClosure(FunctionValue)` early `true`, JVM
  `LightUserData(null)` secondary constructor semantics.
- **2 Concurrency/Loader-side issues**: POSIX `ObjectContainer`
  keyed by `equals` not identity; `StaticRefs` dispose races
  between JVM Cleaner and C trampoline; `NativeLoader` non-atomic
  temp-file extraction.
- **2 Misc**: JVM GlobalRef leak across unloads; `LuaNative.callbacks`
  unbounded growth if Cleaner is starved.

Top-3 priorities for the coding agent: **#1 (wrap-escape UAF)**,
**#2 (double-unref)**, **#4 (POSIX close is empty)** — those are the
next "intermittent JVM crash in production" candidates, mirroring
the shape of the recently-fixed 8a7374d / d90a8f8 / 02de357 bugs.
