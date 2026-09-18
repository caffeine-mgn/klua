# Validation: Security (1-7)

Direct-read verified. CVE-class findings weighted heavier; reviewers' Error severity preserved.

## F1 — Insecure native library extract-and-load in shared tmp dir (CWE-426, CWE-377)
- verdict: TP (CVE-class)
- severity: Error
- evidence: src/jvmMain/kotlin/pw/binom/lua/NativeLoader.kt:14-21 (`if (!cached.exists()) { extractFromJar(...) }`), :46-49 (`val dir = File(System.getProperty("java.io.tmpdir"), "jlua/$VERSION")`), :51-67 (no atomicity, no checksum, no ownership check). Direct read.
- trace: Pre-plant attack on shared `/tmp` → System.load runs attacker's `JNI_OnLoad` in victim JVM. Sticky bit doesn't stop fresh dir creation; no hash; races. Honest Error: severity 5 / consequence 5.

## F2 — Full standard library load (luaL_openlibs) — untrusted Lua = RCE
- verdict: TP
- severity: Error
- evidence: src/jvmMain/c/klua_jni.c:249-254 (`lua_State* L = luaL_newstate(); luaL_openlibs(L);`) + src/posixMain/.../LuaContext.kt:13-17 (`init { luaL_openlibs(state) }`).
- trace: No opt-in sandbox switch. `os.execute`/`io.open`/`package.loadlib`/`debug` are available to any Lua source. Embedding-level CVE candidate.

## F3 — pcallCall mis-maps status and discards runtime error message
- verdict: TP (cross-reviewer consensus)
- severity: Error
- evidence: src/jvmMain/.../LuaValue.kt:440-460 — direct-read confirmed; LUA_ERRRUN(2) lands in `else -> RuntimeException("Unknown pcall status: 2")`, error string left on stack.
- trace: Same bug as R5/C1/L5/Sec here. Real Impact: error masking.

## F4 — Public Kotlin API exposes raw native pointers
- verdict: TP
- severity: Warning
- evidence: src/jvmMain/.../LuaValue.kt:32-35 (UserData.ptr), :72 (LightUserData public ctor takes raw Long); src/posixMain/.../LuaValue.kt:35-37, 46-48 (LightUserData has var lightPtr). Direct read.
- trace: POSIX `LightUserData(somePtr).value` → `asStableRef<Any>().get()` dereferences arbitrary address as StableRef — type confusion.

## F5 — DebugTool.printStack leaks Lua stack values and native pointers
- verdict: TP
- severity: WeakWarning (real but Info-level for most embeddings)
- evidence: src/posixMain/.../DebugTool.kt:14-37 — `sb.append(" value:\"${lua_tostring(this, i)}\"")` + raw pointer emission.
- trace: Sensitive content (paths, tokens) can reach a redirect-able sink.

## F6 — LuaContextRegistry is a single global slot — multi-engine corruption
- verdict: TP
- severity: WeakWarning (under-rated by reviewer; combined with F11/L7 → Error)
- evidence: src/posixMain/.../LuaContext.kt:36-55 (`current: Pair<LuaState, LuaContext>?`). Direct read confirms.
- trace: Constructing engine B orphans engine A's `CLOSURE_FUNCTION`/`userdataGc` callbacks. Memory-corruption vector via callback returning wrong ctx.

## F7 — invokeCallback catches Throwable — VM errors masked
- verdict: TP
- severity: Info
- evidence: src/jvmMain/.../LuaNative.kt:131-142 — `catch (e: Throwable) { lastError = e; (1L shl 31) or 0 }`. Direct read.
- trace: OutOfMemoryError / StackOverflowError / ThreadDeath turn into pcallable Lua errors. Pattern is real; impact is conditional on the user's code path.

## VERDICT COUNTS
- TP: 7
- FP: 0
- NeedMoreData: 0
- By severity (TP only): Error 3, Warning 1, WeakWarning 1, Info 2

## TOP-RISK TP
- F1 (Error, CVE): `NativeLoader` pre-plant in `java.io.tmpdir/jlua/<VERSION>/` — CWE-426/CWE-377. Real local-RCE vector on multi-user/shared-tmp hosts. Direct-read verified NativeLoader.kt:14-21.
- F2 (Error, Embedding CVE): `luaL_openlibs` called unconditionally on both JVM and POSIX — untrusted Lua ⇒ RCE via `os.execute`/`io.open`/`package.loadlib`/`debug`.
- F3 (Error): pcallCall mask of Lua runtime errors — same finding as R5/C1/L5 with extra "audit-log masking" framing.
