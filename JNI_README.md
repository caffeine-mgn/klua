# Lua 5.4 via JNI on the JVM target

Единый источник правды по Lua под оба таргета — нативный и JVM. JVM собирается
нативно из тех же `src/nativeMain/lua/*.c` исходников через `pw.binom.kn-clang`
и подгружается через JNI в jar.

## Состав

| Слой | Файл | Назначение |
|---|---|---|
| C | `src/jvmMain/c/klua_jni.c` | Библиотека `libklua.so/.dylib/.dll` — JNI-обёртки над Lua C API (lua.h 5.4) |
| Kotlin | `src/jvmMain/kotlin/pw/binom/lua/LuaNative.kt` | `external` функции-мосты, trampoline-коллбэк обратно в Kotlin |
| Kotlin | `src/jvmMain/kotlin/pw/binom/lua/NativeLoader.kt` | Извлекает `native/<platform>/libklua.<ext>` из jar в OS tmp и `System.load` |
| Kotlin | `src/jvmMain/kotlin/pw/binom/lua/StaticRefs.kt` | JVM-сайд идентификация ref-ов (для userdata / closures / light-userdata) |
| Kotlin | `src/jvmMain/kotlin/pw/binom/lua/LuaContext.kt`, `LuaValue.kt`, `LuaEngine.kt`, `ObjectContainer.kt` | Копия логики posixMain, без `org.luaj.vm2` |

## Сборка

В `build.gradle.kts` для каждой JVM-платформы (Linux x64/arm64, Windows x64,
macOS — `linuxX64`, `linuxArm64`, `mingwX64`, `macosX64`/`macosArm64` если хост
— mac) добавляется `clangBuildDynamic("klua", target)`, который:

1. Берёт исходники: `src/nativeMain/lua/*.c` + `src/jvmMain/c/klua_jni.c`
2. Подключает `jni.h` из активного `JAVA_HOME/include`
3. Линкуется в shared-library (`-shared -fPIC`)
4. Копируется в `jvmMain resources` по пути `linux_x64/libklua.so` / и т.п.
5. Упаковывается в `klua-jvm.jar` под тем же путём — ресурс-jar подход, без postinstall-скриптов.

Native-loader сам выбирает нужный файл по `os.name`/`os.arch` при `Class.forName`.

## JVM-коллбэк

Поскольку у JVM нет стабильных C function pointer-ов, каждая "cfunction" в Lua
реализована как **одна и та же C-trampoline**, у которой единственный upvalue —
целочисленный `callback_id`. В Kotlin — глобальный реестр
`LuaNative.callbacks: Map<Int, LuaCallbackBridge>`. C-trampoline дёргает
`LuaNative.invokeCallback(int)` — статический метод, чей `jmethodID` кешируется в
`JNI_OnLoad` один раз при загрузке `.so`.

Возвращаемые значения и возникающие Kotlin-исключения маппятся в
`lua_push[integer/number/string/boolean/nil/...]` или `luaL_error` соответственно
по соглашению `(high-bit = err`, остальное = number-of-results).

## Совместимость

* `lua_tonumberx` используется и для integer и для float значений, чтобы соответствовать
  поведению posixNative, который любой `LUA_TNUMBER` возвращает как `Number`
  (а не `LuaInt`). Это сохраняет `assertEquals(LuaValue.of(11.0), res[1])` в
  `arrayTest`.
* `makeRef(popValue=false)` симулируется через `pushValue(idx); ref();` — иначе
  обход таблицы через `next` затирал исходные индексы и ронял VM.
* `pcall` обрабатывает 5 кодов ошибок Lua 5.4 (`LUA_OK..LUA_ERRERR`).

## Тесты

```
> ./gradlew jvmTest --offline
21/21 passed (17 CommonLuaEngineTest + 1 CommonLuaValueTest + 3 TestClosureDebug)

> ./gradlew linuxX64Test --offline
21/21 passed
```

Оба таргета дают идентичные результаты — общая кодовая база в `commonTest/`
гарантирует семантический паритет JVM и native. JVM раннер может
использовать любой OpenJDK 21.x.

## Кросс-таргеты

Кросс-таргеты (`linuxArm64`, `mingwX64`) регистрируются в build-графе
всегда, но `onlyIf`-условие на каждом `buildDynamicKlua*`-таске проверяет,
есть ли на хосте соответствующие JDK headers (`<jdk>/include/<platform>/jni_md.h`).
Без них таск корректно скипается — `jvmJar` собирает jar из того, что есть.

macOS-таргеты добавляются только когда хост — mac (`KonanTarget.host == MACOS_*`):
кросс-компиляция с Linux/Windows на Apple-таргеты через kn-clang недоступна.

## TODO

1. **`metatableTest`**: содержит `LuaValue.setmetatable` функционал, который
   не реализован (в posixNative он тоже не используется — только в тесте).
