# Lua 5.4 via JNI on the JVM target

Единый источник правды по Lua под оба таргета — нативный и JVM. JVM собирается
нативно из тех же `src/nativeMain/lua/*.c` исходников через `pw.binom.kn-clang`
и подгружается через JNI в jar.

## Состав

| Слой | Файл | Назначение |
|---|---|---|
| C | `src/jvmMain/c/klua_jni.c` | Библиотека `libklua.so/.dylib/.dll` — JNI-обёртки над Lua C API (lua.h 5.4) |
| Kotlin | `src/jvmMain/kotlin/pw/binom/lua/LuaNative.kt` | `external` функции-мосты, trampoline-коллбэки обратно в Kotlin, JVM-сайд реестры (`callbacks`, `nextCallbackId`) |
| Kotlin | `src/jvmMain/kotlin/pw/binom/lua/NativeLoader.kt` | Извлекает `native/<platform>/libklua.<ext>` из jar в `/tmp/jlua/<VERSION>/` и `System.load` |
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

`NativeLoader.VERSION` — строковая константа (`"1.2.0-debug"` на текущий момент).
При смене версии или при изменении набора JNI-символов `libklua.so` бамп
инвалидирует кеш `/tmp/jlua/<VERSION>/libklua.so` и заставляет повторно
распаковать свежий бинарь. Без бампа JVM продолжит грузить протухший
бинарь из кеша → `UnsatisfiedLinkError` на новых символах.

## JVM-коллбэки и AC-механика

Поскольку у JVM нет стабильных C function pointer-ов, каждая "cfunction" в Lua
реализована как **одна из трёх общих C-trampoline'ов** в `klua_jni.c`:

| Trampoline | Upvalue | Действие |
|---|---|---|
| `klua_callback_trampoline` | `callback_id` (int) | диспатчит `__call` через `LuaNative.invokeCallback(id)` |
| `klua_gc_trampoline` | `callback_id` (int) | диспатчит `__gc` через `LuaNative.disposeCallback(id)` |
| `klua_userdata_gc_trampoline` | _нет_ | диспатчит `__gc` через payload userdata-адреса → `LuaNative.disposeUserdata(mem)` → `StaticRefs.dispose(mem)` |

В Kotlin:

* `LuaNative.callbacks: ConcurrentHashMap<Int, LuaCallbackBridge>` — реестр мостов для `__call`.
* `jmethodID` всех `LuaNative` static-методов кешируются один раз в `JNI_OnLoad`.

**AC (auto-clean) design**: пользователь передаёт `engine.createAC(value)` или
`engine.createACClosure(func)`, и Kotlin-объект становится userdata-ом с
мета-таблицей, содержащей `__gc`-cfunction. Когда Lua решает что userdata
больше не нужен, вызывается `__gc`, мост снимает Kotlin-ссылку. Цикл
"создал — забыл — Lua сама почистила" работает без явного dispose'а.

Возвращаемые значения и возникающие Kotlin-исключения маппятся в
`lua_push[integer/number/string/boolean/nil/...]` или `luaL_error` соответственно
по соглашению `(high-bit = err`, остальное = number-of-results).

## Lifecycle и отсутствие утечек

Все Kotlin-обёртки, держащие слот в JVM-глобальной структуре, вешают
`java.lang.ref.Cleaner`-action на phantom-reachability:

| Wrapper | Cleaner-action | Что снимается |
|---|---|---|
| `TableRef` / `FunctionRef` | shared `RefAction` → `LuaNative.unref(state, LUA_REGISTRYINDEX, refId)` | registry-entry |
| `UserData` | `UserDataAction(state, refId)` → `unref` + `StaticRefs.dispose(mem)` | registry-entry + `StaticRefs`-слот |
| `LightUserData` | `LightUserDataAction(ptr)` → `StaticRefs.dispose(ptr)` | intern'd `StaticRefs`-слот |
| `ObjectContainer` (per-bridge) | `BridgeCleaner(id)` → `LuaNative.unregisterCallback(id)` | bridge в `LuaNative.callbacks` |

Закрытые циклы сильных ссылок:
- `ObjectContainer` ↔ `LuaNative.callbacks`: до рефактора мост захватывал
  `ObjectContainer.this` через implicit-this в лямбде, мешая phantom-reachability.
  Решено вынесением `closures`-map в отдельный top-level `ClosureMap`.
- Cleaner-action не должен захватывать `this` (иначе цикл `register(obj, action)`
  держит `obj` живым навсегда). Все actions захватывают только `(statePtr,
  refId)` или `(bridgeId)`.

Закрытые баги (через коммиты этой сессии):
- AC userdata: `closureGc`/`userdataGc` читали upvalues у пустых cfunction
  (текло), `createACClosure` создавал userdata без `__gc` мета-таблицы (`595d8b4`).
- `makeRef`: `ref()` вызывался ДО `toPointer(-1)` и ронял счёт стека
  при `lua_next` (`e2a43af`).
- `LuaValueWriter:20`: инвертированный boolean (`lua_pushboolean(0/1)` swap) (`5dbac3c`).
- `createUserData(LightUserData)`: orphan-entry в `StaticRefs` (`5dbac3c`).

Дед вес снят:
- `src/commonNativeLikeMain/` и `src/commonNativeLikeTest/` — были зарегистрированы
  в git, но не в `build.gradle.kts`. Никогда не компилировались. Удалены.
- `src/jvmTest/AbstractTest.kt` — stale `actual` от старой `expect/actual` архитектуры,
  в комментариях. Удалён.

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
> JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew jvmTest --offline
25/25 passed (17 CommonLuaEngineTest + 1 CommonLuaValueTest + 7 JvmAcDisposeTest)

> ./gradlew linuxX64Test --offline
18/18 passed
```

Оба таргета дают идентичные результаты — общая кодовая база в `commonTest/`
гарантирует семантический паритет JVM и native. JVM раннер должен
использовать OpenJDK 21.x (Liberica 21.0.6 крашит на текущем JNI-коде).

`JvmAcDisposeTest` (JVM-специфичный) держит регрессионные guard'ы:
* `autoCleanUserdataGetsDisposedByLuaGc` — Lua-юзердата реально снимается
  `__gc`, не только Kotlin-wrapper.
* `tableAndFunctionRefsDoNotLeakIntoRegistry` — 1000×read+drop, `LUA_REGISTRYINDEX`
  возвращается к baseline.
* `objectContainerBridgesDoNotLeakIntoCallbacksMap` — 1000×`makeClosure`,
  `LuaNative.callbacks` возвращается к baseline.
* `lightUserDataDoesNotLeakIntoStaticRefs` — `ObjectContainer.add` не текёт.
* `createUserDataFromLightUserDataDoesNotOrphanEntries` — orphan-entry fix.
* `manualDisposeWorks` / `cleanerSmokeTest` — JVM-Cleaner держится честно.

## Кросс-таргеты

Кросс-таргеты (`linuxArm64`, `mingwX64`) регистрируются в build-графе
всегда, но `onlyIf`-условие на каждом `buildDynamicKlua*`-таске проверяет,
есть ли на хосте соответствующие JDK headers (`<jdk>/include/<platform>/jni_md.h`).
Без них таск корректно скипается — `jvmJar` собирает jar из того, что есть.

macOS-таргеты добавляются только когда хост — mac (`KonanTarget.host == MACOS_*`):
кросс-компиляция с Linux/Windows на Apple-таргеты через kn-clang недоступна.
