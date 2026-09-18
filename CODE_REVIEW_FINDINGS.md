# klua — Deferred issues (после рефакторинга)

Этот файл — список находок ревью кода, которые **ещё не исправлены** в коде. Исправленные issues (12 Error + 4 Warning + 2 Info = 18 закрытых issues) задокументированы в истории коммитов и в inline-комментариях в коде (`NativeLoader.kt`, `LuaValue.kt`, `LuaEngine.kt`, `LuaContext.kt`, `KotlinLuaFunction.kt`, `ObjectContainer.kt`, `StdOut.kt`, `klua_jni.c`).

Все находки валидированы через 8 параллельных валидаторов, которые прошли каждое по одной, последовательно. 109/109 валидаций прошли.

---

## Error (4) — критические, требуют дизайна или большого рефакторинга

### E2 — `luaL_openlibs` безусловно открывает весь stdlib → RCE для untrusted Lua
- **Файл**: `src/jvmMain/c/klua_jni.c:253`, `src/posixMain/kotlin/pw/binom/lua/LuaContext.kt:13-17`
- **Суть**: при создании любого `LuaEngine` открываются все стандартные библиотеки (`base`, `package`, `io`, `os`, `string`, `math`, `utf8`, `debug`, `coroutine`, `table`). `os.execute` / `io.open` / `package.loadlib` / `debug.*` доступны любому скрипту.
- **Что нужно сделать**: новый конструктор `LuaEngine(safeMode = true)` с белым списком `luaopen_base/string/table/math/utf8`. Дизайн-API decision: ломать обратную совместимость или нет. По умолчанию для существующего конструктора — оставить как есть и явно документировать.
- **Effort**: средний (дизайн + ~50 строк в JNI + K/N bindings).

### E8 — JVM wrapper `LuaContext` UAF после `engine.close()`
- **Файл**: `src/jvmMain/kotlin/pw/binom/lua/LuaContext.kt:111-119`, `src/jvmMain/kotlin/pw/binom/lua/LuaNative.kt:120-137`
- **Суть**: `invokeCallback` создаёт wrapper-контекст через `LuaContext.wrap(statePtr)` с собственным `statePtr`-полем. `engine.close()` зануляет только `engine.ll.statePtr`, не обнуляя wrapper-контексты. Возвращённые из callback TableRef/FunctionRef/UserData держат wrapper → после close их Cleaner вызывает `LuaNative.unref(<freed ptr>, ...)` → use-after-free SEGV.
- **Что нужно сделать**: держать `Map<Long, MutableList<LuaContext>>` в `LuaNative` (или WeakHashMap), `wrap` возвращает wrapper-обёртку, `engine.close()` обнуляет statePtr во всех wrappers. Регрессионный тест: возврат TableRef из callback, затем close, forced GC, не должно падать.
- **Effort**: средний.

### E10 — JVM `setAC` использует неправильный `__gc` handler (StaticRefs leak)
- **Файл**: `src/jvmMain/kotlin/pw/binom/lua/LuaEngine.kt:195-205` (откаченное место, см. inline-комментарий)
- **Суть**: исторически `setAC` ставит `__gc = closureAutoGcFunction`, который маппится в `disposeCallback(id)` на callback-id, который никогда не регистрировался в `LuaNative.callbacks` — это no-op. StaticRefs entry, поставленная `createUserData(LightUserData)`, никогда не disposes. Прямая замена на `userdataAutoGcFunction` (правильный `disposeUserdata(mem) → StaticRefs.dispose`) вызывает **double-dispose регрессию** в `createUserDataFromLightUserDataDoesNotOrphanEntries`.
- **Что нужно сделать**: ownership refactor: либо создать отдельный `RefAction` subclass, который disposes только StaticRefs (не ref), либо провести двухуровневую распаковку ownership на `createUserData` vs `createAC`. Нужен регрессионный тест для обоих путей.
- **Effort**: средний-высокий.

### E15 — `LuaInt` недостижим из любого read-пути
- **Файл**: `src/jvmMain/kotlin/pw/binom/lua/LuaContext.kt:55-56` (JVM `readValueAt`), `src/posixMain/kotlin/pw/binom/lua/LuaValueReader.kt:36-42` (POSIX `readValue`)
- **Суть**: `readValueAt` использует `lua_tonumberx` / `LuaNative.toNumber` для всех чисел. `lua_isinteger` / `LuaNative.isInteger` существуют, но не вызываются. Результат: `LuaValue.LuaInt` недостижим из read на обоих таргетах.
- **Что нужно сделать**: добавить ветку `if (isInteger) LuaInt(toInteger) else Number(toNumber)` — **breaking API change**: ломает 7+ существующих тестов (`integerRoundTripTest`, `arrayTest`, `readTable`, и др.), потому что они сейчас рассчитывают на `Number` для всех числовых Lua-значений. Решение требует обновления тестов одновременно (или введения explicit `getIntegerOrNull()` API вместо поломки текущего поведения).
- **Effort**: низкий (если готовы сломать тесты) — высокий (если сохранять совместимость).

---

## Warning (~7) — значимые, требуют небольшого-большого рефакторинга

### W2 — JVM `ObjectContainer.removeClosure(FunctionRef)` hardcoded `false`
- **Файл**: `src/jvmMain/kotlin/pw/binom/lua/ObjectContainer.kt:65`
- **Суть**: возвращает `false`, не пытаясь снять bridge. Bridge id остаётся в `LuaNative.callbacks` до container-GC.
- **Что нужно сделать**: добавить `refId → bridgeId` mapping в `ObjectContainer.makeClosure`, чтобы `removeClosure(FunctionRef)` мог разрешить bridge id и вызвать `LuaNative.unregisterCallback`.
- **Effort**: средний.

### W4 — POSIX `ObjectContainer.add` дедуплицирует по `equals`, не по identity
- **Файл**: `src/posixMain/kotlin/pw/binom/lua/ObjectContainer.kt:18-29`
- **Суть**: `objToPtr[data]` использует plain `HashMap` equality. Два `String("x")` делят один `StableRef`; `remove(one)` disposes для обоих.
- **Что нужно сделать**: использовать `IdentityHashMap` или обёртку `IdentityWrapper<T>(value)` с override `hashCode()`/`equals()` через `System.identityHashCode`. Для K/N нет built-in identity-map.
- **Effort**: средний.

### W5 — `StaticRefs` global monitor → `ConcurrentHashMap`
- **Файл**: `src/jvmMain/kotlin/pw/binom/lua/StaticRefs.kt:11-44`
- **Суть**: каждый `get/store/dispose/intern` берёт `synchronized(map)`. Это single-bottleneck для multi-thread Lua JVM-нагрузки.
- **Что нужно сделать**: заменить `HashMap` на `ConcurrentHashMap`. **ВАЖНО**: предыдущая попытка вызвала NPE — `ConcurrentHashMap.entries.iterator()` + `it.remove()` в одном thread не работает так, как в `HashMap`. Нужно либо использовать `ConcurrentHashMap.compute*` API, либо собирать keys в `synchronized(map)` snapshot перед удалением.
- **Effort**: средний.

### W6 — JVM `StaticRefs.dispose` race между Kotlin-Cleaner и C-trampoline
- **Файл**: `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:60-67` (UserData.dispose), `:125-129` (LightUserData.dispose), `src/jvmMain/c/klua_jni.c:201-225`
- **Суть**: три места дропают один и тот же StaticRefs entry. Идемпотентный `HashMap.remove` спасает от crash, но логически может быть resurrection pattern.
- **Что нужно сделать**: выбрать одного owner'a cleanup (либо Kotlin-side, либо C-side) и удалить другой.
- **Effort**: средний.

### W8 — POSIX `LightUserData(ptr)` public ctor + `value = lightPtr.toKotlinObject()` → type confusion
- **Файл**: `src/posixMain/kotlin/pw/binom/lua/LuaValue.kt:46-77`
- **Суть**: `LightUserData(somePtr).value` вызывает `asStableRef<Any>().get()` на произвольном указателе. Type confusion / arbitrary heap read.
- **Что нужно сделать**: сделать primary ctor `internal`; добавить guard, проверяющий, что pointer происходит от `StableRef.create`.
- **Effort**: средний (API-impacting).

### W10 — JVM `invokeCallback` catch `Throwable` маскирует VM Errors
- **Файл**: `src/jvmMain/kotlin/pw/binom/lua/LuaNative.kt:128-141`
- **Суть**: `catch (e: Throwable)` превращает OOM/StackOverflowError в Lua-pcall-able sentinel. Не фатально, но неправильно.
- **Что нужно сделать**: split — `catch (Exception)` (set `lastError` + sentinel) и не ловить `VirtualMachineError`/`ThreadDeath`. Также заменить `@Volatile var lastError` на `AtomicReference<Throwable>` (предыдущая попытка W10 + W5 комбинации дала NPE — нужно изолировать).
- **Effort**: низкий.

### W13 — performance-fix блок (Efficiency F1-F13)
- **Файл**: разные
- **Суть**: F1 `StaticRefs` global monitor (covered by W5), F2 `absIndex` per read, F3 `UserData.ptr` 3-JNI per access, F4 POSIX `checkState` overhead, F6 Cleaner pressure per read, F7 `TableValue` allocations, F9 POSIX dual-HashMap, F10 ClosureMap synchronized, F11 single-slot registry (covered by E7), F12 TableValue push N×3 JNI, F13 LUA_TFUNCTION ref=false upvalue loop.
- **Что нужно сделать**: профилирование + per-fix изменение. Лучше отдельным PR с микро-benchmarks.
- **Effort**: высокий (6+ часов с профилированием).

---

## WeakWarning (7) — небольшие улучшения

### WW1 — POSIX `closureAutoGcFunction` и `userdataAutoGcFunction` ссылаются на один trampoline
- **Файл**: `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:12-15`
- **Суть**: оба `actual val` — `makeRef(FunctionValue(userdataGc))`. JVM имеет два разных cfunction'а; POSIX — один.
- **Что нужно сделать**: либо реализовать два разных trampoline на POSIX (как на JVM), либо удалить одно из имён из `expect`.

### WW2 — JVM `ObjectContainer.remove(data)` удаляет только первую запись
- **Файл**: `src/jvmMain/kotlin/pw/binom/lua/StaticRefs.kt:33-44` (`removeIfMatches`)
- **Суть**: после N `add(obj)` остаётся N-1 записей до GC.
- **Что нужно сделать**: либо удалять все matching entries, либо документировать контракт как "remove at most one".

### WW3 — magic Lua-статусы/типы на JVM как raw integers
- **Файл**: `src/jvmMain/kotlin/pw/binom/lua/LuaContext.kt:62-110`, `LuaEngine.kt:213-237`, `LuaValue.kt:432-460`
- **Суть**: raw `0/2/4/5/else`. POSIX использует cinterop-именованные константы. Magic-number style способствовал багу E3.
- **Что нужно сделать**: добавить `commonMain/LuaStatus.kt` и `LuaType.kt` с константами, использовать на JVM.

### WW4 — `pcallCall` читает `1..count` предполагая пустой стек ниже
- **Файл**: `src/jvmMain/kotlin/pw/binom/lua/LuaValue.kt:447-452`
- **Суть**: latent — все текущие callers имеют `topBefore == 1`, но `topBefore`-based read был бы надёжнее.
- **Что нужно сделать**: `readValueAt(topBefore + it, true)` для `it in 1..count`.

### WW5 — POSIX `makeRef` + `createAC(Any?)` используют `printStackTrace` + `!!` на nullable pointer
- **Файл**: `src/posixMain/kotlin/pw/binom/lua/LuaEngine.kt:88-96` (makeRef), `:194-216` (createAC catch — частично W11 fix)
- **Суть**: `lua_topointer(...)!!` NPE на booleans/numbers/lightuserdata. `printStackTrace` в production anti-pattern.
- **Что нужно сделать**: убрать `try/catch + printStackTrace`, заменить `!!` на null-check с meaningful exception. Catch W11 уже сделал для `createAC(Any?)`.

### WW6 — `NativeLoader.extractFromJar` race без синхронизации
- **Файл**: `src/jvmMain/kotlin/pw/binom/lua/NativeLoader.kt`
- **Суть**: race на многопоточной загрузке. Частично решено добавлением `extractLocks` в E1 fix, но `extractIfNeeded` всё ещё не делает atomic move последовательно (хотя первая итерация E1 добавила temp file + atomic move). Нужно проверить что final-state move действительно атомарен.
- **Что нужно сделать**: ревью.

### WW7 — `JNI_OnLoad` leaks GlobalRefs без `JNI_OnUnload`
- **Файл**: `src/jvmMain/c/klua_jni.c:48-66`
- **Суть**: `stringClass` и `luaNativeClass` — GlobalRefs, освобождаются только при process exit.
- **Что нужно сделать**: добавить `JNI_OnUnload` с `DeleteGlobalRef` для обоих.

---

## Info (10) — housekeeping, dead code, мелкие best-practices

### I1 — удалить `src/posixMain/kotlin/pw/binom/lua/LuaCtx.kt` и `LuaStateAndLib.kt`
- **Суть**: zero usage (verified grep). `LuaCtx` даже имеет Cleaner на `lua_close` для state, который не зарегистрирован в `LuaContextRegistry` — если кто-то когда-нибудь использует, callbacks будут silently no-op.
- **Что нужно сделать**: `git rm` обоих файлов.

### I2 — удалить `src/posixMain/kotlin/pw/binom/lua/KotlinLuaFunctions.kt`
- **Суть**: empty stub — только `@file:OptIn` и `import`, ни одной декларации.

### I3 — удалить `src/commonMain/kotlin/pw/binom/lua/InputVarargs.kt` и `OutputVarargs.kt`
- **Суть**: public interfaces без implementers.

### I4 — Deprecated `DebugTool.kt` (expect + actuals): JVM body stub, POSIX dump
- **Файл**: `src/commonMain/.../DebugTool.kt`, `src/jvmMain/.../DebugTool.kt`, `src/posixMain/.../DebugTool.kt`
- **Суть**: всё помечено `@Deprecated`. JVM версия просто `println("doesn't provide any Lua Stack")`. Либо удалить все три, либо реализовать реальный dump на JVM через `LuaNative.getTop` → `type` → `toString`.

### I6 — Dead `LuaNativeType` private object
- **Файл**: `src/jvmMain/kotlin/pw/binom/lua/LuaNative.kt:166-177`
- **Суть**: константы типов Lua объявлены, но `readValueAt` использует raw integers.
- **Что нужно сделать**: использовать (см. WW3).

### I8 — JNI `pushCClosure` игнорирует `n` (upvalue count)
- **Файл**: `src/jvmMain/c/klua_jni.c:376-386`, `src/jvmMain/kotlin/pw/binom/lua/LuaNative.kt:31`
- **Суть**: `(void)n;` — параметр не используется. POSIX прямо использует `lua_pushcclosure(state, value.ptr, value.upValues.size)`.
- **Что нужно сделать**: либо удалить `n` параметр, либо правильно пушить N upvalues.

### I9 — Verbose `TableValue("__gc".lua to ...)` factory
- **Файл**: 7+ call-sites в JVM `LuaEngine.kt`, 3+ в POSIX
- **Суть**: `LuaValue.TableValue("__gc".lua to closureAutoGcFunction)` повторяется с одним shape, разными keys.
- **Что нужно сделать**: фабрика `luaTableOf(vararg pairs: Pair<String, LuaValue>)`.

### I10 — Six near-identical `forEachIndexed` loops в `LuaValue.of(table)` overloads
- **Файл**: `src/jvmMain/.../LuaValue.kt:394,399,404`, `src/posixMain/.../LuaValue.kt:361,369,380`
- **Суть**: 6 копий одного цикла, отличающихся только receiver (List/Array/(List, metatable)) и вторым аргументом metatable.
- **Что нужно сделать**: один helper per target.

### I11 — Two parallel ID counters на JVM
- **Файл**: `src/jvmMain/kotlin/pw/binom/lua/LuaNative.kt:14` (`nextCallbackId`), `src/jvmMain/kotlin/pw/binom/lua/ObjectContainer.kt:101-104` (`nextClosureId`)
- **Суть**: оба AtomicInteger, делают то же самое.
- **Что нужно сделать**: консолидировать в один counter на `LuaNative`.

### I12 — `LightUserData.takeIfNotNil`, magic pop `2`, magic bit `(1L shl 31) or 0`
- **Файл**: `src/commonMain/.../LuaValueExtends.kt:95-96`, `src/posixMain/.../LuaValue.kt:415-422`, `src/jvmMain/kotlin/pw/binom/lua/LuaNative.kt:138-143`
- **Суть**: `takeIfNotNil` — это `takeUnless { isNil }`; magic pop 2 в `RefObject.callToString`; magic sentinel в `invokeCallback`.
- **Что нужно сделать**: задокументировать через named constants или inline-комментарии. Sentinel покрыт частично W10.

---

## Удалённые файлы вручную (не сделано через `rm`, чтобы не вызывать confirmations)

```
git rm src/posixMain/kotlin/pw/binom/lua/LuaCtx.kt
git rm src/posixMain/kotlin/pw/binom/lua/LuaStateAndLib.kt
git rm src/posixMain/kotlin/pw/binom/lua/KotlinLuaFunctions.kt
git rm src/commonMain/kotlin/pw/binom/lua/InputVarargs.kt
git rm src/commonMain/kotlin/pw/binom/lua/OutputVarargs.kt
```

Все 5 файлов zero-usage (verified grep). Удаление безопасно.

---

## Сводка

- **Применено**: 18 issues (12 Error + 4 Warning + 2 Info). Все комментарии и подробные описания — в inline-комментариях в коде.
- **Отложено (deferred)**: 28 issues (4 Error + 7 Warning + 7 WeakWarning + 10 Info).
- **Тесты после правок**: 44/44 JVM + 35/35 LinuxX64 проходят.
- **Артефакты ревью и валидации**: `.tasks/klua-full-review/review/*.md` (8 файлов), `.tasks/klua-full-review/validation/*.validated.md` (8 файлов) — итого 16 файлов с полным trace каждой находки и каждой валидации по одной.
