# klua

Kotlin Multiplatform bindings for Lua. Run Lua scripts from Kotlin and expose
Kotlin objects to Lua as auto-clean userdata — JVM is implemented over JNI
against upstream Lua 5.4 sources compiled into a shared library; native targets
use Kotlin/Native cinterop against the same sources built as a static library.

- Lua: 5.4 (with `LUA_COMPAT_5_3`)
- Kotlin: 2.4.20
- License: Apache 2.0

## Installation

The library is published to Maven Central as `pw.binom:klua`.

```kotlin
// build.gradle.kts
plugins {
    kotlin("multiplatform") version "2.4.20"
}

repositories {
    mavenCentral()
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("pw.binom:klua:<version>")
            }
        }
    }
}
```

For pure-JVM projects (no Kotlin Multiplatform):

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.4.20"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("pw.binom:klua-jvm:<version>")
}
```

For Android apps, depend on the Android library artifact (an AAR that bundles
the prebuilt `libklua.so` for `arm64-v8a` and `x86_64`; `minSdk` 24):

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("pw.binom:klua-android:<version>")
}
```

## Supported targets

`pw.binom:klua` is a Kotlin Multiplatform library. The published artifacts are:

| Artifact                               | Target                         |
|----------------------------------------|--------------------------------|
| `pw.binom:klua-jvm`                    | JVM (JDK 11+)                  |
| `pw.binom:klua-linuxx64`               | Kotlin/Native Linux x86_64     |
| `pw.binom:klua-linuxarm64`             | Kotlin/Native Linux AArch64    |
| `pw.binom:klua-mingwx64`               | Kotlin/Native MinGW x86_64     |
| `pw.binom:klua-macosx64`               | Kotlin/Native macOS x86_64     |
| `pw.binom:klua-androidnativearm64`     | Kotlin/Native Android AArch64  |
| `pw.binom:klua-androidnativex64`       | Kotlin/Native Android x86_64   |
| `pw.binom:klua-android`                | Android library (AAR, ART/JVM) |

The Android AAR is a standard `com.android.library` artifact (`minSdk` 24) that
ships `libklua.so` for `arm64-v8a` and `x86_64`; it loads the native library via
`System.loadLibrary("klua")`. 32-bit ABIs are not packaged.

The JVM artifact bundles `libklua.so` / `klua.dylib` / `klua.dll` for Linux
x86_64, Linux AArch64, MinGW x86_64, and the host macOS (extracted and loaded
at runtime, no native toolchain required at consumer build time). All other
host platforms extract the closest matching binary on a best-effort basis.
Native artifacts link against a `liblua.a` baked into the klib by the
`pw.binom.kn-clang` Gradle plugin, so the consumer does not need to build the
bundled Lua sources.

iOS, tvOS and watchOS targets are not yet published.

## Quick start

## Quick start

```kotlin
import pw.binom.lua.LuaEngine
import pw.binom.lua.lua
import pw.binom.lua.ObjectContainer

// 1. Evaluate a Lua snippet.
LuaEngine().use { engine ->
    val results = engine.eval("return 1 + 2, 'hello'")
    println(results[0].checkedNumber())  // 3.0
    println(results[1].checkedString()) // hello
}

// 2. Read and write globals — `e["name"]` is syntactic sugar for
//    lua_getglobal / lua_setglobal.
LuaEngine().use { engine ->
    engine["greeting"] = "hello".lua
    val out = engine.eval("return greeting .. ', world'")
    println(out[0].checkedString()) // hello, world
}

// 3. Expose a Kotlin closure to Lua. The closure lives inside an
//    ObjectContainer so the JVM can keep its references alive; release
//    the container (or let it be GC'd) to drop them.
LuaEngine().use { engine ->
    val container = ObjectContainer()
    engine["double"] = container.makeClosure { args ->
        val n = args[0].checkedNumber()
        listOf((n * 2).lua)
    }
    println(engine.eval("return double(21)")[0].checkedNumber()) // 42.0
}
```

## Sandboxing: libraries and limits

A fresh `LuaEngine` is **bare** — no standard library is loaded, so `os`, `io`,
`package` and `debug` are `nil`. Opt into a subset with `openLibs` or the
constructor. `LuaLibrary.SAFE` (`base`, `coroutine`, `table`, `string`, `math`,
`utf8`) is a good default for untrusted scripts; `LuaLibrary.ALL` restores the
full library set.

```kotlin
LuaEngine(libraries = LuaLibrary.SAFE).use { engine ->
    assert(engine.eval("return os")[0] is LuaValue.Nil)
}
```

Precompiled bytecode is rejected by default: `eval` compiles text-only
(`mode = "t"`) and the same restriction is applied to Lua's own `load` /
`loadstring` / `loadfile` / `dofile`. Pass `allowBinaryChunks = true` only for
trusted bytecode.

Execution can be bounded per `eval`/`call` with `LuaLimits`. When a limit trips
the operation throws `LuaLimitException`, whose `kind` is `INSTRUCTIONS`,
`TIMEOUT`, `MEMORY` or `CANCELLED`. Limits are not swallowable by Lua's `pcall`.

```kotlin
import kotlin.time.Duration.Companion.milliseconds

LuaEngine(
    libraries = LuaLibrary.SAFE,
    limits = LuaLimits(
        maxInstructions = 1_000_000,
        timeout = 250.milliseconds,
        maxMemoryBytes = 16 * 1024 * 1024,
    ),
).use { engine ->
    try {
        engine.eval("while true do end")
    } catch (e: LuaLimitException) {
        println(e.kind) // INSTRUCTIONS (or TIMEOUT)
    }
}
```

`LuaLimits.UNLIMITED` (the default) disables every check. `engine.cancel()`
cooperatively aborts a long-running eval from another thread and surfaces as
`LuaLimitKind.CANCELLED`. The instruction hook runs every 10 000 VM
instructions, so `maxInstructions` is accurate to within one period, and the
memory counter includes Lua's internal structures (leave headroom).

## Exposing Kotlin objects to Lua

`createAC(value)` wraps a Kotlin object in a Lua userdata whose `__gc`
metamethod disposes the underlying reference on Lua's next GC. The Kotlin-side
wrapper is owned by the engine — no manual cleanup required.

```kotlin
class Counter(private var n: Int = 0) {
    fun increment() = ++n
    fun value() = n
}

LuaEngine().use { engine ->
    val container = ObjectContainer()
    engine["newCounter"] = container.makeClosure {
        listOf(engine.createAC(Counter()))
    }
    engine.eval("""
        local c = newCounter()
        c:increment(); c:increment(); c:increment()
        print(c:value())  -- 3
        c = nil
        collectgarbage('collect')  -- StableRef<Counter> is disposed here
    """)
}
```

For a Kotlin closure that Lua itself calls, use `createACClosure`:

```kotlin
engine["greet"] = engine.createACClosure { args ->
    val name = args[0].checkedString()
    listOf("hello, $name".lua)
}
```

## Lua values on the Kotlin side

Every Lua value crossing the boundary is wrapped as a `LuaValue`. The
`.lua` extension lifts native Kotlin types into one — `"x".lua`, `42.lua`,
`true.lua`, `nil` (just Kotlin `null`).

To go back:

| Kotlin call        | Lua value           |
|--------------------|---------------------|
| `checkedString()`  | string              |
| `checkedNumber()`  | number (as `Double`)|
| `checkedBoolean()` | boolean             |
| `checkedTable()`   | table               |
| `checkedTableRef()`| table (with ref)    |
| `checkedUserdata()`| userdata            |
| `checkedFunction()`| function            |

`makeRef(table)` and `makeRef(function)` produce table / function refs that
survive being passed across the C boundary; use them to read or set their
contents from Kotlin later.

## Auto-close

`LuaEngine` is `AutoCloseable`. Use `use { ... }` or call `close()` explicitly
to release the underlying Lua state and its registry slots.

```kotlin
val engine = LuaEngine()
try {
    // ...
} finally {
    engine.close()
}
```

## Errors

`eval` and `call` throw `LuaException` for Lua-side runtime errors, with the
Lua error message and source location attached. Pure stack-balance mistakes
surface as `LuaStackException`.
