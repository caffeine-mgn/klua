package pw.binom.lua

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UTF-8 round-trip across the Kotlin/Lua boundary (AUDIT-2026-10-12.md #5).
 *
 * The JVM/JNI backend used NewStringUTF / GetStringUTFChars (modified UTF-8),
 * which mangled supplementary code points both ways and gave Lua the wrong byte
 * length for Java-originated strings. These checks run on both backends
 * (commonTest), since Lua strings are UTF-8 bytes everywhere.
 */
class CommonLuaStringTest : AbstractTest() {

    @Test
    fun supplementaryPlaneRoundTrip() = start {
        val e = makeEngine()
        val emoji = "\uD83D\uDE00" // 😀 U+1F600, 4 UTF-8 bytes
        e["s"] = emoji.lua
        assertEquals(
            4.0,
            e.eval("return #s")[0].checkedNumber(),
            "a supplementary code point must be 4 UTF-8 bytes in Lua, not 6",
        )
        assertEquals(emoji, e["s"].checkedString())
    }

    @Test
    fun luaProducedUtf8Decodes() = start {
        val e = makeEngine()
        // utf8.char produces a real 4-byte UTF-8 sequence in Lua; reading it
        // back through modified UTF-8 used to yield U+00F0 garbage.
        assertEquals("\uD83D\uDE00", e.eval("return utf8.char(0x1F600)")[0].checkedString())
    }

    @Test
    fun cyrillicRoundTrip() = start {
        val e = makeEngine()
        e["s"] = "Привет, Вася".lua
        assertEquals("Привет, Вася!", e.eval("return s .. '!'")[0].checkedString())
    }

    @Test
    fun mixedScriptRoundTrip() = start {
        val e = makeEngine()
        val text = "юникод 😀 日本語 ✓"
        e["s"] = text.lua
        assertEquals(text, e.eval("return s")[0].checkedString())
        assertEquals(
            text.encodeToByteArray().size.toDouble(),
            e.eval("return #s")[0].checkedNumber(),
            "Lua byte length must match the UTF-8 encoding of the Kotlin string",
        )
    }
}
