/*
 * JNI bindings for klua: thin C layer over Lua 5.4 C API.
 * All Lua C functions used by the existing posixMain Kotlin code are wrapped here as
 * Java_pw_binom_lua_LuaNative_* JNI functions. lua_State* is passed as jlong.
 *
 * JVM callbacks (Kotlin lambdas pushed as Lua C functions) are dispatched through a
 * single C trampoline: the Kotlin side stores the callback in a global registry and
 * passes the registry id as the cclosure's only upvalue. The trampoline pulls the id,
 * fetches the callback, attaches the current thread to the JVM, and invokes it.
 */

#include <jni.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "lua.h"
#include "lualib.h"
#include "lauxlib.h"

#define KLUA_TNONE          (-1)
#define KLUA_TNIL           0
#define KLUA_TBOOLEAN       1
#define KLUA_TLIGHTUSERDATA 2
#define KLUA_TNUMBER        3
#define KLUA_TSTRING        4
#define KLUA_TTABLE         5
#define KLUA_TFUNCTION      6
#define KLUA_TUSERDATA      7
#define KLUA_TTHREAD        8

static JavaVM* klua_jvm = NULL;

static jclass stringClass = NULL;
static jclass luaNativeClass = NULL;
static jmethodID invokeCallbackMethod = NULL;
static jmethodID lastErrorMessageMethod = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    /* Cache class + method IDs at load time, well before any <clinit>.
     * The JVM is fully consistent here and GetStaticMethodID is safe. */
    (void)reserved;
    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    klua_jvm = vm;
    jclass cls = (*env)->FindClass(env, "pw/binom/lua/LuaNative");
    if (cls == NULL) return JNI_ERR;
    luaNativeClass = (jclass)(*env)->NewGlobalRef(env, cls);
    (*env)->DeleteLocalRef(env, cls);
    invokeCallbackMethod = (*env)->GetStaticMethodID(env, luaNativeClass, "invokeCallback", "(IJ)J");
    if (invokeCallbackMethod == NULL) return JNI_ERR;
    lastErrorMessageMethod = (*env)->GetStaticMethodID(env, luaNativeClass, "lastErrorMessage", "()Ljava/lang/String;");
    if (lastErrorMessageMethod == NULL) return JNI_ERR;
    jclass localString = (*env)->FindClass(env, "java/lang/String");
    stringClass = (jclass)(*env)->NewGlobalRef(env, localString);
    (*env)->DeleteLocalRef(env, localString);
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_init(JNIEnv* env, jclass cls) {
    /* Method IDs are already cached by JNI_OnLoad. init() exists only as a
     * trigger to ensure the .so is loaded; nothing to do here. */
    (void)env; (void)cls;
}

static int klua_attach(JNIEnv** env) {
    int detach = 0;
    jint res = (*klua_jvm)->GetEnv(klua_jvm, (void**)env, JNI_VERSION_1_2);
    if (res == JNI_EDETACHED) {
        res = (*klua_jvm)->AttachCurrentThread(klua_jvm, (void**)env, NULL);
        if (res == JNI_OK) detach = 1;
    }
    return detach;
}

static void klua_detach(int detach) {
    if (detach) (*klua_jvm)->DetachCurrentThread(klua_jvm);
}

static jstring str_from_lua(JNIEnv* env, lua_State* L, int idx) {
    size_t len = 0;
    const char* s = lua_tolstring(L, idx, &len);
    if (s == NULL) return NULL;
    return (*env)->NewStringUTF(env, s);
}

static int abs_stack(lua_State* L, int idx) {
    /* Lua 5.4 indices below -256 are pseudo-indices (e.g. LUA_REGISTRYINDEX); convert absolute. */
    if (idx > 0) return idx;
    if (idx <= -256) return idx;  /* pseudo-index, leave as-is */
    return lua_gettop(L) + idx + 1;
}

static jlong lua_to_jlong(lua_State* L, int idx) {
    return (jlong)(intptr_t)lua_touserdata(L, idx);
}

static void* jlong_to_ptr(jlong v) {
    return (void*)(intptr_t)v;
}

static jlong lua_state_to_jlong(lua_State* L) {
    return (jlong)(intptr_t)L;
}

static lua_State* jlong_to_lua_state(jlong v) {
    return (lua_State*)(intptr_t)v;
}

/*
 * Trampoline: invoked by Lua when a Kotlin-pushed cclosure runs. Reads callback id
 * from upvalue, looks up JVM callback, invokes it. Returns the count of results
 * pushed by the JVM side.
 */
static int klua_callback_trampoline(lua_State* L) {
    int idx = (int)lua_tointeger(L, lua_upvalueindex(1));
    JNIEnv* env = NULL;
    int detach = klua_attach(&env);
    if (env == NULL) {
        return luaL_error(L, "klua: cannot attach JVM to current thread");
    }
    if (luaNativeClass == NULL || invokeCallbackMethod == NULL || lastErrorMessageMethod == NULL) {
        klua_detach(detach);
        return luaL_error(L, "klua: LuaNative not initialized (JNI_OnLoad failure)");
    }

    jlong statePtr = lua_state_to_jlong(L);
    jlong result = (*env)->CallStaticLongMethod(
        env, luaNativeClass,
        invokeCallbackMethod,
        (jint)idx, statePtr
    );

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        klua_detach(detach);
        return luaL_error(L, "klua: Kotlin callback threw exception");
    }

    int nresults = (int)(result & 0x7FFFFFFFL);
    int errFlag = (int)((result >> 31) & 1);

    if (errFlag) {
        jstring msg = (jstring)(*env)->CallStaticObjectMethod(
            env, luaNativeClass,
            lastErrorMessageMethod
        );
        const char* m = msg ? (*env)->GetStringUTFChars(env, msg, NULL) : "klua: error in callback";
        if (m) {
            luaL_error(L, "%s", m);
            if (msg) (*env)->ReleaseStringUTFChars(env, msg, m);
        }
        nresults = 0;
    }

    klua_detach(detach);
    return nresults;
}

/*
 * Lua's reference table is per-lua_State; we use a sentinel value as the table key
 * for the registry of cclosure callback ids. Implemented as a Lua table stored at
 * LUA_REGISTRYINDEX[LUA_RIDX_LAST+1].
 */
#define KLUA_REGISTRY_CCLOSURES_KEY (LUA_RIDX_LAST + 1)

static void ensure_cclosure_registry(lua_State* L) {
    lua_rawgeti(L, LUA_REGISTRYINDEX, KLUA_REGISTRY_CCLOSURES_KEY);
    if (lua_isnil(L, -1)) {
        lua_pop(L, 1);
        lua_createtable(L, 0, 16);
        lua_pushvalue(L, -1);
        lua_rawseti(L, LUA_REGISTRYINDEX, KLUA_REGISTRY_CCLOSURES_KEY);
    } else {
        lua_pop(L, 1);
    }
}

static int alloc_cclosure_id(JNIEnv* env, lua_State* L) {
    ensure_cclosure_registry(L);
    lua_getfield(L, KLUA_REGISTRY_CCLOSURES_KEY, "next_id");
    int next = (int)lua_tointeger(L, -1) + 1;
    lua_pop(L, 1);
    lua_pushinteger(L, next);
    lua_setfield(L, KLUA_REGISTRY_CCLOSURES_KEY, "next_id");
    return next;
}

/* ---- State management ---- */

JNIEXPORT jlong JNICALL Java_pw_binom_lua_LuaNative_newState(JNIEnv* env, jclass cls) {
    (void)env; (void)cls;
    lua_State* L = luaL_newstate();
    if (L == NULL) return 0;
    luaL_openlibs(L);
    return lua_state_to_jlong(L);
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_close(JNIEnv* env, jclass cls, jlong statePtr) {
    (void)env; (void)cls;
    lua_State* L = jlong_to_lua_state(statePtr);
    if (L != NULL) lua_close(L);
}

/* ---- Stack manipulation ---- */

JNIEXPORT jint JNICALL Java_pw_binom_lua_LuaNative_getTop(JNIEnv* env, jclass cls, jlong statePtr) {
    (void)env; (void)cls;
    return lua_gettop(jlong_to_lua_state(statePtr));
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_setTop(JNIEnv* env, jclass cls, jlong statePtr, jint top) {
    (void)env; (void)cls;
    lua_settop(jlong_to_lua_state(statePtr), (int)top);
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_pop(JNIEnv* env, jclass cls, jlong statePtr, jint n) {
    (void)env; (void)cls;
    lua_settop(jlong_to_lua_state(statePtr), -(int)n - 1);
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_pushValue(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    lua_State* L = jlong_to_lua_state(statePtr);
    lua_pushvalue(L, abs_stack(L, (int)idx));
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_remove(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    lua_State* L = jlong_to_lua_state(statePtr);
    int i = abs_stack(L, (int)idx);
    lua_rotate(L, i, -1);
    lua_pop(L, 1);
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_insert(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    lua_State* L = jlong_to_lua_state(statePtr);
    int i = abs_stack(L, (int)idx);
    lua_rotate(L, i, 1);
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_replace(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    lua_State* L = jlong_to_lua_state(statePtr);
    int i = abs_stack(L, (int)idx);
    lua_copy(L, -1, i);
    lua_pop(L, 1);
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_rotate(JNIEnv* env, jclass cls, jlong statePtr, jint idx, jint n) {
    (void)env; (void)cls;
    lua_State* L = jlong_to_lua_state(statePtr);
    lua_rotate(L, abs_stack(L, (int)idx), (int)n);
}

/* ---- Push operations ---- */

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_pushNil(JNIEnv* env, jclass cls, jlong statePtr) {
    (void)env; (void)cls;
    lua_pushnil(jlong_to_lua_state(statePtr));
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_pushBoolean(JNIEnv* env, jclass cls, jlong statePtr, jboolean v) {
    (void)env; (void)cls;
    lua_pushboolean(jlong_to_lua_state(statePtr), v == JNI_TRUE ? 1 : 0);
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_pushInteger(JNIEnv* env, jclass cls, jlong statePtr, jlong v) {
    (void)env; (void)cls;
    lua_pushinteger(jlong_to_lua_state(statePtr), (lua_Integer)v);
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_pushNumber(JNIEnv* env, jclass cls, jlong statePtr, jdouble v) {
    (void)env; (void)cls;
    lua_pushnumber(jlong_to_lua_state(statePtr), (lua_Number)v);
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_pushString(JNIEnv* env, jclass cls, jlong statePtr, jstring s) {
    lua_State* L = jlong_to_lua_state(statePtr);
    if (s == NULL) { lua_pushnil(L); return; }
    const char* c = (*env)->GetStringUTFChars(env, s, NULL);
    if (c == NULL) { lua_pushnil(L); return; }
    lua_pushstring(L, c);
    (*env)->ReleaseStringUTFChars(env, s, c);
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_pushLightUserdata(JNIEnv* env, jclass cls, jlong statePtr, jlong ptr) {
    (void)env; (void)cls;
    lua_pushlightuserdata(jlong_to_lua_state(statePtr), jlong_to_ptr(ptr));
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_pushCClosure(JNIEnv* env, jclass cls, jlong statePtr, jint callbackId, jint n) {
    (void)env; (void)cls;
    lua_State* L = jlong_to_lua_state(statePtr);
    /* Push callback id as upvalue #1, then create cclosure using the trampoline. */
    lua_pushinteger(L, (lua_Integer)callbackId);
    lua_pushcclosure(L, klua_callback_trampoline, 1);  /* always 1 upvalue (callback id) */
    /* extra upvalues are not used by klua, but we still consume n to satisfy caller contract. */
    (void)n;
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_pushCFunction(JNIEnv* env, jclass cls, jlong statePtr, jint callbackId) {
    Java_pw_binom_lua_LuaNative_pushCClosure(env, cls, statePtr, callbackId, 0);
}

JNIEXPORT jint JNICALL Java_pw_binom_lua_LuaNative_registerCallback(JNIEnv* env, jclass cls, jlong statePtr) {
    (void)env; (void)cls;
    return alloc_cclosure_id(env, jlong_to_lua_state(statePtr));
}

/* ---- To operations ---- */

JNIEXPORT jboolean JNICALL Java_pw_binom_lua_LuaNative_toBoolean(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    return lua_toboolean(jlong_to_lua_state(statePtr), (int)idx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_pw_binom_lua_LuaNative_toInteger(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    jlong out = 0;
    int isnum = 0;
    lua_Integer v = lua_tointegerx(jlong_to_lua_state(statePtr), (int)idx, &isnum);
    if (isnum) out = (jlong)v;
    return out;
}

JNIEXPORT jboolean JNICALL Java_pw_binom_lua_LuaNative_isInteger(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    int isnum = 0;
    lua_tointegerx(jlong_to_lua_state(statePtr), (int)idx, &isnum);
    return isnum ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jdouble JNICALL Java_pw_binom_lua_LuaNative_toNumber(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    int isnum = 0;
    lua_Number v = lua_tonumberx(jlong_to_lua_state(statePtr), (int)idx, &isnum);
    return isnum ? (jdouble)v : 0.0;
}

JNIEXPORT jboolean JNICALL Java_pw_binom_lua_LuaNative_isNumber(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    int isnum = 0;
    lua_tonumberx(jlong_to_lua_state(statePtr), (int)idx, &isnum);
    return isnum ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_pw_binom_lua_LuaNative_toString(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    return str_from_lua(env, jlong_to_lua_state(statePtr), (int)idx);
}

JNIEXPORT jstring JNICALL Java_pw_binom_lua_LuaNative_toLString(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    /* Same as toString, but guarantees conversion via luaL_tolstring. */
    lua_State* L = jlong_to_lua_state(statePtr);
    size_t len = 0;
    const char* s = luaL_tolstring(L, (int)idx, &len);
    if (s == NULL) return NULL;
    jstring out = (*env)->NewStringUTF(env, s);
    lua_pop(L, 1);
    return out;
}

JNIEXPORT jlong JNICALL Java_pw_binom_lua_LuaNative_toUserdata(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    return lua_to_jlong(jlong_to_lua_state(statePtr), (int)idx);
}

JNIEXPORT jlong JNICALL Java_pw_binom_lua_LuaNative_toPointer(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)lua_topointer(jlong_to_lua_state(statePtr), (int)idx);
}

/* ---- Type checks ---- */

JNIEXPORT jint JNICALL Java_pw_binom_lua_LuaNative_type(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    return lua_type(jlong_to_lua_state(statePtr), (int)idx);
}

JNIEXPORT jstring JNICALL Java_pw_binom_lua_LuaNative_typename(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    const char* t = lua_typename(jlong_to_lua_state(statePtr), (int)idx);
    return (*env)->NewStringUTF(env, t != NULL ? t : "unknown");
}

JNIEXPORT jboolean JNICALL Java_pw_binom_lua_LuaNative_isNil(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    return lua_isnil(jlong_to_lua_state(statePtr), (int)idx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_pw_binom_lua_LuaNative_isNone(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    return lua_isnone(jlong_to_lua_state(statePtr), (int)idx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_pw_binom_lua_LuaNative_isNoneOrNil(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    return lua_isnoneornil(jlong_to_lua_state(statePtr), (int)idx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_pw_binom_lua_LuaNative_isBoolean(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    return lua_isboolean(jlong_to_lua_state(statePtr), (int)idx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_pw_binom_lua_LuaNative_isString(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    return lua_isstring(jlong_to_lua_state(statePtr), (int)idx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_pw_binom_lua_LuaNative_isTable(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    return lua_istable(jlong_to_lua_state(statePtr), (int)idx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_pw_binom_lua_LuaNative_isFunction(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    return lua_isfunction(jlong_to_lua_state(statePtr), (int)idx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_pw_binom_lua_LuaNative_isCFunction(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    return lua_iscfunction(jlong_to_lua_state(statePtr), (int)idx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_pw_binom_lua_LuaNative_isUserdata(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    return lua_isuserdata(jlong_to_lua_state(statePtr), (int)idx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_pw_binom_lua_LuaNative_isLightUserdata(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    return lua_islightuserdata(jlong_to_lua_state(statePtr), (int)idx) ? JNI_TRUE : JNI_FALSE;
}

/* ---- Globals ---- */

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_getGlobal(JNIEnv* env, jclass cls, jlong statePtr, jstring name) {
    lua_State* L = jlong_to_lua_state(statePtr);
    const char* c = (*env)->GetStringUTFChars(env, name, NULL);
    lua_getglobal(L, c);
    (*env)->ReleaseStringUTFChars(env, name, c);
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_setGlobal(JNIEnv* env, jclass cls, jlong statePtr, jstring name) {
    lua_State* L = jlong_to_lua_state(statePtr);
    const char* c = (*env)->GetStringUTFChars(env, name, NULL);
    lua_setglobal(L, c);
    (*env)->ReleaseStringUTFChars(env, name, c);
}

/* ---- Load / call ---- */

JNIEXPORT jint JNICALL Java_pw_binom_lua_LuaNative_loadString(JNIEnv* env, jclass cls, jlong statePtr, jstring s) {
    lua_State* L = jlong_to_lua_state(statePtr);
    const char* c = (*env)->GetStringUTFChars(env, s, NULL);
    int r = luaL_loadstring(L, c);
    (*env)->ReleaseStringUTFChars(env, s, c);
    return r;
}

JNIEXPORT jint JNICALL Java_pw_binom_lua_LuaNative_pcall(JNIEnv* env, jclass cls, jlong statePtr, jint nargs, jint nresults, jint errfunc) {
    (void)env; (void)cls;
    return lua_pcallk(jlong_to_lua_state(statePtr), (int)nargs, (int)nresults, (int)errfunc, 0, NULL);
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_traceback(JNIEnv* env, jclass cls, jlong statePtr, jstring msg, jint level) {
    lua_State* L = jlong_to_lua_state(statePtr);
    const char* c = NULL;
    if (msg != NULL) c = (*env)->GetStringUTFChars(env, msg, NULL);
    luaL_traceback(L, L, c, (int)level);
    if (c != NULL) (*env)->ReleaseStringUTFChars(env, msg, c);
}

/* ---- Tables ---- */

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_createTable(JNIEnv* env, jclass cls, jlong statePtr, jint narr, jint nrec) {
    (void)env; (void)cls;
    lua_createtable(jlong_to_lua_state(statePtr), (int)narr, (int)nrec);
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_newTable(JNIEnv* env, jclass cls, jlong statePtr) {
    (void)env; (void)cls;
    lua_createtable(jlong_to_lua_state(statePtr), 0, 0);
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_setTable(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    lua_settable(jlong_to_lua_state(statePtr), (int)idx);
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_getTable(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    lua_gettable(jlong_to_lua_state(statePtr), (int)idx);
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_rawSet(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    lua_rawset(jlong_to_lua_state(statePtr), (int)idx);
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_rawGet(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    lua_rawget(jlong_to_lua_state(statePtr), (int)idx);
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_rawSetI(JNIEnv* env, jclass cls, jlong statePtr, jint idx, jlong n) {
    (void)env; (void)cls;
    lua_rawseti(jlong_to_lua_state(statePtr), (int)idx, (lua_Integer)n);
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_rawGetI(JNIEnv* env, jclass cls, jlong statePtr, jint idx, jlong n) {
    (void)env; (void)cls;
    lua_rawgeti(jlong_to_lua_state(statePtr), (int)idx, (lua_Integer)n);
}

JNIEXPORT jint JNICALL Java_pw_binom_lua_LuaNative_next(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    return lua_next(jlong_to_lua_state(statePtr), (int)idx);
}

JNIEXPORT jint JNICALL Java_pw_binom_lua_LuaNative_setMetatable(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    return lua_setmetatable(jlong_to_lua_state(statePtr), (int)idx);
}

JNIEXPORT jint JNICALL Java_pw_binom_lua_LuaNative_getMetatable(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    return lua_getmetatable(jlong_to_lua_state(statePtr), (int)idx);
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_len(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    lua_len(jlong_to_lua_state(statePtr), (int)idx);
}

JNIEXPORT jlong JNICALL Java_pw_binom_lua_LuaNative_rawLen(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    return (jlong)lua_rawlen(jlong_to_lua_state(statePtr), (int)idx);
}

/* ---- References (registry) ---- */

JNIEXPORT jint JNICALL Java_pw_binom_lua_LuaNative_ref(JNIEnv* env, jclass cls, jlong statePtr, jint t) {
    (void)env; (void)cls;
    return luaL_ref(jlong_to_lua_state(statePtr), (int)t);
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_unref(JNIEnv* env, jclass cls, jlong statePtr, jint t, jint ref) {
    (void)env; (void)cls;
    luaL_unref(jlong_to_lua_state(statePtr), (int)t, (int)ref);
}

/* ---- Userdata ---- */

JNIEXPORT jlong JNICALL Java_pw_binom_lua_LuaNative_newUserdata(JNIEnv* env, jclass cls, jlong statePtr, jint sz) {
    (void)env; (void)cls;
    void* p = lua_newuserdatauv(jlong_to_lua_state(statePtr), (size_t)sz, 1);
    return (jlong)(intptr_t)p;
}

JNIEXPORT jlong JNICALL Java_pw_binom_lua_LuaNative_userdataPtr(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    /* Lua 5.4 userdata layout: payload is at offset 0, uservalue at offset size. We want
     * the payload pointer (which is what `lua_touserdata` returns). */
    (void)env; (void)cls;
    return lua_to_jlong(jlong_to_lua_state(statePtr), (int)idx);
}

JNIEXPORT void JNICALL Java_pw_binom_lua_LuaNative_setIUservalue(JNIEnv* env, jclass cls, jlong statePtr, jint idx, jlong v) {
    (void)env; (void)cls;
    lua_setiuservalue(jlong_to_lua_state(statePtr), (int)idx, 1);
    (void)v;
}

JNIEXPORT jlong JNICALL Java_pw_binom_lua_LuaNative_getIUservalue(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    int ok = lua_getiuservalue(jlong_to_lua_state(statePtr), (int)idx, 1);
    if (!ok) return 0;
    return (jlong)(intptr_t)lua_topointer(jlong_to_lua_state(statePtr), -1);
}

/* ---- Lightuserdata helpers ---- */
/* Lightuserdata in Lua 5.4 stores a void* directly. We just round-trip it via jlong. */

JNIEXPORT jint JNICALL Java_pw_binom_lua_LuaNative_absIndex(JNIEnv* env, jclass cls, jlong statePtr, jint idx) {
    (void)env; (void)cls;
    return abs_stack(jlong_to_lua_state(statePtr), (int)idx);
}

/* ---- Upvalues ---- */

JNIEXPORT jstring JNICALL Java_pw_binom_lua_LuaNative_getUpvalue(JNIEnv* env, jclass cls, jlong statePtr, jint funcIdx, jint upvalueIdx) {
    (void)env; (void)cls;
    lua_State* L = jlong_to_lua_state(statePtr);
    int fi = abs_stack(L, (int)funcIdx);
    const char* name = lua_getupvalue(L, fi, (int)upvalueIdx);
    if (name == NULL) return NULL;
    jstring out = (*env)->NewStringUTF(env, name);
    return out;
}

/* ---- Error ---- */

JNIEXPORT jint JNICALL Java_pw_binom_lua_LuaNative_error(JNIEnv* env, jclass cls, jlong statePtr, jstring msg) {
    (void)env; (void)cls;
    lua_State* L = jlong_to_lua_state(statePtr);
    const char* c = msg ? (*env)->GetStringUTFChars(env, msg, NULL) : NULL;
    int r = luaL_error(L, "%s", c != NULL ? c : "error");
    if (c != NULL) (*env)->ReleaseStringUTFChars(env, msg, c);
    return r;
}
