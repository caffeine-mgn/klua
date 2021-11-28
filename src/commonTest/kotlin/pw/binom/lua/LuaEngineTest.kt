package pw.binom.lua

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class LuaEngineTest {

    @Test
    fun globalTest() {
        val e = LuaEngine()
        e["test"] = LuaValue.of("Kotlin")
        assertEquals("Kotlin", e["test"].checkedString())
        assertTrue(e["test2"].isNil)
    }

    @Test
    fun evalTest() {
        val e = LuaEngine()
        val result = e.eval("return 123,456")
        assertEquals(2, result.size)
        assertEquals(123.0, result[0].checkedNumber())
        assertEquals(456.0, result[1].checkedNumber())
    }

    @Test
    fun throwException() {
        val e = LuaEngine()
        val c = UserFunctionContainer()
        e["throw_exception"] = c.add { i, o ->
            throw RuntimeException("My message")
        }
        try {
            e.eval("throw_exception()")
            fail("Lua should throw exception")
        } catch (e: LuaException) {
            //Do nothing
        }
    }

    @Test
    fun errorCatching() {
        try {
            val e = LuaEngine()
            e.eval("fff()")
            fail("Lua should throw exception")
        } catch (e: LuaException) {
            //Do nothing
        }
    }

    @Test
    fun test() {
        val e = LuaEngine()
        val c = UserFunctionContainer()

        val createTable = c.add { req, resp ->
            resp +=
                LuaValue.Table(
                    LuaValue.of("1") to LuaValue.of("2"),
                    LuaValue.of("2") to LuaValue.of("3")
                )
        }
        e["createTable"] = createTable
        val myFunc = c.add { req, resp ->
            val value = req[0]
            println("first arg: $value")
            resp += LuaValue.of("Hello from kotlin.  Got $value")
        }
        e["myfunc"] = myFunc
//        e.eval("print('Result: ' .. myfunc(createTable,'my_data_for_function'))")
        e.eval(
            """
--класс
Person= {}
--тело класса
function Person:new(fName, lName)

    -- свойства
    local obj= {}
        obj.firstName = fName
        obj.lastName = lName

    -- метод
    function obj:getName()
        return self.firstName 
    end

    --чистая магия!
    setmetatable(obj, self)
    self.__index = self; return obj
end

--создаем экземпляр класса
vasya = Person:new("Вася", "Пупкин")

--обращаемся к свойству
print(vasya.firstName)    --> результат: Вася
print(vasya.lastName)    --> результат: Пупкин

--обращаемся к методу
print(vasya:getName())  --> результат: Вася
myfunc(vasya)
        """
        )
    }
}