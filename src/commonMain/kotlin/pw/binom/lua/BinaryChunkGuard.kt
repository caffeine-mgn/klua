package pw.binom.lua

/**
 * Bootstrap run right after the `base` library is loaded when
 * [LuaEngine]`(..., allowBinaryChunks = false)`. It forces every dynamic
 * loader (`load`, `loadstring`, `loadfile`, `dofile`) to text-only chunks
 * (`mode = "t"`), so untrusted Lua cannot smuggle precompiled bytecode past
 * [LuaEngine.eval]'s text-only compilation. Kept identical on JVM and native.
 */
internal const val BINARY_CHUNK_GUARD_SCRIPT: String = """
local __klua_load = load
load = function(chunk, chunkname, mode, env)
    return __klua_load(chunk, chunkname, "t", env)
end
local __klua_loadstring = loadstring
if __klua_loadstring then
    loadstring = function(chunk, chunkname)
        return __klua_loadstring(chunk, chunkname, "t")
    end
end
local __klua_loadfile = loadfile
if __klua_loadfile then
    loadfile = function(filename, mode, env)
        return __klua_loadfile(filename, "t", env)
    end
    dofile = function(filename)
        local f, err = loadfile(filename)
        if not f then error(err, 2) end
        return f()
    end
end
"""
