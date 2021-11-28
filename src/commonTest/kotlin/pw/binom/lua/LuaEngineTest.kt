package pw.binom.lua

import kotlin.test.Test

class LuaEngineTest {

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
        e.setGlobal("createTable", createTable)
        val myFunc = c.add { req, resp ->
            val value = req[0]
            println("first arg: $value")
            resp += LuaValue.of("Hello from kotlin.  Got $value")
        }
        e.setGlobal("myfunc", myFunc)
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