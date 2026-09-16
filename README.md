# klua

Kotlin Multiplatform bindings for Lua. Run Lua scripts from Kotlin and expose
Kotlin objects to Lua as auto-clean userdata — JVM is implemented over JNI
against upstream Lua 5.4 sources compiled into a shared library; native targets
use Kotlin/Native cinterop against the same sources built as a static library.

- Lua: 5.4 (with `LUA_COMPAT_5_3`)
- Kotlin: 2.4.20
- License: Apache 2.0

## Installation

```kotlin
// build.gradle.kts
plugins {
    kotlin("multiplatform") version "2.4.20"
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("pw.binom:klua:1.0.0-SNAPSHOT")
            }
        }
    }
}
```

The artifact is published to Maven Central. Native targets depend on the host
being able to build the bundled Lua sources (a C toolchain is required); JVM
extracts and loads the bundled shared library at runtime, no native toolchain
needed at consumer build time.

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
