#ifdef __EMSCRIPTEN__
#include "../lua/lapi.h"
#include <emscripten.h>
#include <stdio.h>
#include <stdlib.h>

extern "C" {
//extern int klua_js_call_closure(lua_State*L);
EMSCRIPTEN_KEEPALIVE int klua_closureGc(lua_State*L){
    printf("klua_closureGc called\n");
    return 0;
}
/**/
EMSCRIPTEN_KEEPALIVE int klua_userdataGc(lua_State*L){
    printf("klua_userdataGc called\n");
    return 0;
}

EMSCRIPTEN_KEEPALIVE lua_CFunction klua_get_closureGc_func(lua_State*L){
//    klua_js_call_closure(L);
    return &klua_closureGc;
}

EMSCRIPTEN_KEEPALIVE lua_CFunction klua_get_userdataGc_func(){
    printf("klua_get_userdataGc_func called\n");
    return &klua_userdataGc;
}

EMSCRIPTEN_KEEPALIVE int klua_get_LUAI_MAXSTACK(){
    printf("klua_get_LUAI_MAXSTACK called\n");
    return LUAI_MAXSTACK;
}

EMSCRIPTEN_KEEPALIVE void* klua_malloc(int size){
    printf("klua_malloc called\n");
    return malloc(size);
}

EMSCRIPTEN_KEEPALIVE void klua_free(void* ptr){
    printf("klua_free called\n");
    free(ptr);
}
}
#endif