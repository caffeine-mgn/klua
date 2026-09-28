#include <stdlib.h>
#include <time.h>

#include "lua.h"
#include "lauxlib.h"
#include "klua_limits.h"

/* How often the limit hook runs, in Lua VM instructions. */
#define KLUA_HOOK_PERIOD 10000

static long long klua_now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000000LL + (long long)ts.tv_nsec / 1000LL;
}

static void *klua_alloc(void *ud, void *ptr, size_t osize, size_t nsize) {
    klua_control *c = (klua_control *)ud;
    if (nsize == 0) {
        free(ptr);
        if (ptr != NULL) c->used_memory -= (long long)osize;
        return NULL;
    }
    long long delta = (long long)nsize - (ptr != NULL ? (long long)osize : 0LL);
    if (c->max_memory > 0 && c->used_memory + delta > c->max_memory) {
        c->reason = KLUA_REASON_MEMORY;
        return NULL;
    }
    void *np = realloc(ptr, nsize);
    if (np != NULL) c->used_memory += delta;
    return np;
}

static void klua_hook(lua_State *L, lua_Debug *ar) {
    (void)ar;
    klua_control *c = *(klua_control **)lua_getextraspace(L);
    if (c == NULL) return;
    if (c->cancel) {
        c->reason = KLUA_REASON_CANCEL;
        luaL_error(L, "klua: execution cancelled");
        return;
    }
    if (c->max_instructions > 0) {
        c->instructions += KLUA_HOOK_PERIOD;
        if (c->instructions > c->max_instructions) {
            c->reason = KLUA_REASON_INSTRUCTIONS;
            luaL_error(L, "klua: instruction limit exceeded");
            return;
        }
    }
    if (c->deadline_us > 0 && klua_now_us() > c->deadline_us) {
        c->reason = KLUA_REASON_TIMEOUT;
        luaL_error(L, "klua: execution timeout");
        return;
    }
}

klua_control *klua_control_new(long long max_memory, long long max_instructions, long long timeout_us) {
    klua_control *c = (klua_control *)calloc(1, sizeof(klua_control));
    if (c == NULL) return NULL;
    c->max_memory = max_memory;
    c->max_instructions = max_instructions;
    if (timeout_us > 0) c->deadline_us = klua_now_us() + timeout_us;
    return c;
}

void klua_control_free(klua_control *c) {
    free(c);
}

void klua_control_set_timeout(klua_control *c, long long timeout_us) {
    if (c == NULL) return;
    c->deadline_us = timeout_us > 0 ? klua_now_us() + timeout_us : 0;
}

void klua_control_set_cancel(klua_control *c, int cancel) {
    if (c == NULL) return;
    c->cancel = cancel;
}

void klua_control_reset(klua_control *c) {
    if (c == NULL) return;
    c->instructions = 0;
    c->cancel = 0;
    c->reason = KLUA_REASON_NONE;
}

int klua_control_reason(klua_control *c) {
    return c != NULL ? c->reason : KLUA_REASON_NONE;
}

long long klua_control_used_memory(klua_control *c) {
    return c != NULL ? c->used_memory : 0;
}

lua_State *klua_newstate(klua_control *c) {
    lua_State *L = lua_newstate(klua_alloc, c);
    if (L == NULL) return NULL;
    *(klua_control **)lua_getextraspace(L) = c;
    lua_sethook(L, klua_hook, LUA_MASKCOUNT, KLUA_HOOK_PERIOD);
    return L;
}
