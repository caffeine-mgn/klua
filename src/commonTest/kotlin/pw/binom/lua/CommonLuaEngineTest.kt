package pw.binom.lua

import kotlin.test.*

class CommonLuaEngineTest : AbstractTest() {
    class MyObject(var value: Int)

    @Test
    fun callTest() = start {
        val e = LuaEngine()
        val o = ObjectContainer()
        var called = false
        val closure = o.makeClosure {
            called = true
            emptyList()
        }
        StdOut.info("---===CALL===---")
        e.call(closure, "123".lua)
        assertTrue(called)
    }

    @Test
    fun globalTest() = start {
        val e = LuaEngine()
        e["test"] = LuaValue.of("Kotlin")
        assertEquals("Kotlin", e["test"].checkedString())
        assertTrue(e["test2"].isNil)
    }

    @Test
    fun evalTest() = start {
        val e = LuaEngine()
        val result = e.eval("return 123,456")
        assertEquals(2, result.size)
        assertEquals(123.0, result[0].checkedNumber())
        assertEquals(456.0, result[1].checkedNumber())
    }

    @Test
    fun getFromTableRef() = start {
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
    fun lightUserDataTest() = start {
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
    fun testAutoCleanUserData() = start {
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
    fun autoCleanClosureTest1() = start {
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
    fun autoCleanClosureTest2() = start {
        val e = LuaEngine()
        var argCount = 0
        e["test"] = e.createACClosure { it ->
            argCount = it.size
            emptyList()
        }
        e.eval("test(1,2,3)")
        assertEquals(3, argCount)
    }

    @Test
    fun toStringTest() = start {
        val e = LuaEngine()
        val o = ObjectContainer()
        val TEST_DATA = "Test Data"
        val toStringFunc = o.makeClosure {
            println("__tostring called")
            listOf(TEST_DATA.lua)
        }

        e["creator"] = o.makeClosure {
            val u = e.createAC(o.add(null))
            u.metatable.checkedTable()["__tostring".lua] = toStringFunc

            val table = LuaValue.TableValue()
            table.metatable = LuaValue.TableValue("__tostring".lua to toStringFunc)
            val vv = e.makeRef(table)
            listOf(u, table)
        }
        val res = e.eval(
            """
           userdata, table = creator()
           return userdata, table, tostring(userdata), tostring(table)
        """
        )
        assertEquals(TEST_DATA, res[0].checkedUserdata().callToString(), "userdata.__tostring()")
        assertEquals(TEST_DATA, res[1].checkedTableRef().callToString(), "table.__tostring()")
        assertEquals(TEST_DATA, res[2].checkedString(), "tostring(func)")
        assertEquals(TEST_DATA, res[3].checkedString(), "tostring(tab)")
    }

    @Test
    fun userDataTest2() = start {
        val N1 = 5
        val N2 = 10
        val N3 = 15

        val e = LuaEngine()
        val o = ObjectContainer()
        val obj = MyObject(N1)
        val lightUserData = o.add(obj)
        val userData = e.createUserData(lightUserData)
        println("data=$lightUserData")

        assertEquals(obj, userData.value())
        assertEquals(N1, userData.value<MyObject>().value)
        obj.value = N2
        assertEquals(N2, userData.value<MyObject>().value)
        userData.value<MyObject>().value = N3
        assertEquals(N3, obj.value)
    }

    @Test
    fun callPassedFunctionTest() = start {
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
    fun refFuncCall() = start {
        val e = LuaEngine()
        val o = ObjectContainer()
        var called = false
        val ref = e.makeRef(
            o.makeClosure {
                called = true
                emptyList()
            }
        )

        ref.call()
        assertTrue(called)
    }

    @Test
    fun throwException() = start {
        val e = LuaEngine()
        val c = ObjectContainer()
        e["throw_exception"] = c.makeClosure {
            throw RuntimeException("My message")
        }
        try {
            e.eval("throw_exception()")
            fail("Lua should throw exception")
        } catch (e: LuaException) {
            // Do nothing
        }
    }

    @Test
    fun errorCatching() = start {
        try {
            val e = LuaEngine()
            e.eval("fff()")
            fail("Lua should throw exception")
        } catch (e: LuaException) {
            // Do nothing
        }
    }

    @Test
    fun metatableTest() = start {
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
    fun arrayTest() = start {
        val e = LuaEngine()
        val original = listOf(
            LuaValue.of(11.0),
            LuaValue.of(1.0),
            LuaValue.of(2.0),
            LuaValue.of(44.0),
            LuaValue.of(3.0),
        )
        val res = e.eval(
            """
            return {11,1,2,44,3}
        """
        )[0] as LuaValue.Table
        val l = res.toList()
        assertEquals(5, res.rawSize)

        (1..5).forEach {
            val a = original[it - 1]
            val b = res[LuaValue.of((it).toLong())]
            assertEquals(a, b)
        }
        original.forEachIndexed { index, number ->
            assertEquals(number, l[index])
        }
    }

    @Test
    fun test() = start {

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
