package pw.binom.lua

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class CommonLuaEngineTest {
    class MyObject(var value: Int)

    @Test
    fun callTest() {
        val e = LuaEngine()
        val o = ObjectContainer()
        var called = false
        val closure = o.makeClosure {
            called = true
            emptyList()
        }
        e.call(closure)
        assertTrue(called)
    }

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
    fun getFromTableRef() {
        val e = LuaEngine()
        val o = ObjectContainer()
        e["test"] = o.makeClosure {
            val table = it[0].checkedTable()
            assertEquals("test", table["a".lua].checkedString())
            emptyList()
        }
        e.eval("test({a='test'})")
    }

    @Test
    fun lightUserDataTest() {
        val N1 = 5
        val N2 = 10
        val N3 = 15

        val o = ObjectContainer()
        val obj = MyObject(N1)
        val lightUserData = o.add(obj)

        assertEquals(obj, lightUserData.value())
        assertEquals(N1, lightUserData.value<MyObject>().value)
        obj.value = N2
        assertEquals(N2, lightUserData.value<MyObject>().value)
        lightUserData.value<MyObject>().value = N3
        assertEquals(N3, obj.value)
    }

    @Test
    fun testAutoCleanUserData() {
        val e = LuaEngine()
        val o = ObjectContainer()
        e["create"] = o.makeClosure {
            listOf(
                e.createAC(MyObject(5))
            )
        }
        e.eval(
            """
           create()
           collectgarbage()
        """
        )
    }

    @Test
    fun autoCleanClosureTest() {
        val e = LuaEngine()
        var called = false
        val o = ObjectContainer()
        e["make_function"] = o.makeClosure {
            val func = e.createACClosure {
                println("Called success")
                called = true
                emptyList()
            }
            listOf(func)
        }
        e.eval(
            """
           make_function()()
           collectgarbage()
        """
        )
        assertTrue(called)
    }

    @Test
    fun userDataTest2() {
        val N1 = 5
        val N2 = 10
        val N3 = 15

        val e = LuaEngine()
        val o = ObjectContainer()
        val obj = MyObject(N1)
        val lightUserData = o.add(obj)
        val userData = e.createUserData(lightUserData)
        println("data=${lightUserData}")

        assertEquals(obj, userData.value())
        assertEquals(N1, userData.value<MyObject>().value)
        obj.value = N2
        assertEquals(N2, userData.value<MyObject>().value)
        userData.value<MyObject>().value = N3
        assertEquals(N3, obj.value)
    }

    @Test
    fun callPassedFunctionTest() {
        val e = LuaEngine()
        val o = ObjectContainer()
        var called = false
        e["for_call"] = o.makeClosure {
            called = true
            emptyList()
        }
        e["for_pass"] = o.makeClosure {
            println("for pass called. try call ${it[0]}")
            e.call(it[0])
            emptyList()
        }

        e.eval(
            """
           function my_function()
             for_call()
           end
           
           for_pass(my_function)
        """
        )
        assertTrue(called)
    }

    @Test
    fun refFuncCall() {
        val e = LuaEngine()
        val o = ObjectContainer()
        var called = false
        val ref = e.makeRef(o.makeClosure {
            called = true
            emptyList()
        })

        ref.call()
        assertTrue(called)
    }

    @Test
    fun throwException() {
        val e = LuaEngine()
        val c = ObjectContainer()
        e["throw_exception"] = c.makeClosure {
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
    fun metatableTest() {
        val e = LuaEngine()
        val o = ObjectContainer()
        val metatable = e.makeRef(LuaValue.of(mapOf("key".lua to "value".lua)))
        val table = e.makeRef(LuaValue.of(mapOf("foo".lua to "bar".lua)))

        assertEquals(LuaValue.Nil, table.metatable)
        table.metatable = (metatable)
        println("-->${table.toValue().toMap()}")
        println("-->${table.metatable.checkedTable().toMap()}")
//        assertEquals("value", ref.getMetatable().checkedTable().rawGet("key".lua).checkedString())
    }

    @Test
    fun test() {
        val e = LuaEngine()
        val c = ObjectContainer()


        val meta = LuaValue.of(
            mapOf(
                "__tostring".lua to c.makeClosure {
                    listOf("to-string-called".lua)
                }
            )
        )

        val table = LuaValue.of(mapOf(), meta)

        e["my_data"] = table
        e.eval("print(my_data)")


        e["myfunc"] = c.makeClosure {
            println("args: $it")
            println("Getting table value...")
            val ref = it[0].checkedTable().checkedTableRef()
            println("\n\n\n---===GETTING VALUE===---")
            val value = ref.toValue()
            println("done! $value")

            val meta = value.metatable
            println("\n\n\n---===GETTING META VALUE===---")
            println("metatade-ptr:$meta")
            val metaRef = meta.checkedTable().checkedTableRef().toValue()
            println("ref: $metaRef")
            listOf(LuaValue.of("Hello from kotlin.  Got "))
        }
//        e.eval("print('Result: ' .. myfunc(createTable,'my_data_for_function'))")
        e.eval(
            """
--класс
Person = {}
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
print('metatable:',getmetatable(vasya))
myfunc(vasya)
--myfunc(getmetatable(vasya))
        """
        )
    }
}