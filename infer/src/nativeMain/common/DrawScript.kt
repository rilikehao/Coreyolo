package common

import cnames.structs.Image
import cnames.structs.InferTask
import kotlinx.cinterop.*
import platform.lua.*
import platform.native.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalForeignApi::class)
class DrawScript(path: String) : AutoCloseable {
    val state = luaL_newstate()!!.also { luaL_openlibs(it) }

    init {
        if (path.isNotEmpty()) {
            check(luaL_loadfilex(state, path, null))
            check(lua_pcallk(state, 0, 0, 0, 0, null))
        }
    }

    override fun close() = lua_close(state)

    @OptIn(ExperimentalTime::class)
    fun execute(pts: Instant, task: CPointer<InferTask>) {
        bindDrawFunction(GetImage(task)!!)
        bindHttpFunction()
        lua_getglobal(state, "Process")
        if (lua_type(state, -1) != LUA_TFUNCTION) {
            lua_settop(state, -2)
            return
        }
        lua_pushnumber(state, pts.toEpochMilliseconds().toDouble() / 1000.0)  // Push pts as first parameter
        lua_createtable(state, 0, 0)  // Create detections table as second parameter
        for (i in 0..<SizeDetections(task)) {
            lua_pushinteger(state, i.toLong() + 1)  // Key (index)
            lua_createtable(state, 0, 0)  // Create detection table
            lua_pushstring(state, "x0".cstr)
            lua_pushinteger(state, PtrDetections(task)!![i].bound_.x0_.toLong())
            lua_settable(state, -3)
            lua_pushstring(state, "x1".cstr)
            lua_pushinteger(state, PtrDetections(task)!![i].bound_.x1_.toLong())
            lua_settable(state, -3)
            lua_pushstring(state, "y0".cstr)
            lua_pushinteger(state, PtrDetections(task)!![i].bound_.y0_.toLong())
            lua_settable(state, -3)
            lua_pushstring(state, "y1".cstr)
            lua_pushinteger(state, PtrDetections(task)!![i].bound_.y1_.toLong())
            lua_settable(state, -3)
            lua_pushstring(state, "score".cstr)
            lua_pushnumber(state, PtrDetections(task)!![i].score_.toDouble())
            lua_settable(state, -3)
            lua_pushstring(state, "text".cstr)
            lua_pushstring(state, PtrDetections(task)!![i].name_)
            lua_settable(state, -3)

            lua_settable(state, -3)  // Add detection to detections table
        }
        check(lua_pcallk(state, 2, 0, 0, 0, null))
    }

    fun bindDrawFunction(image: CPointer<Image>) {
        lua_pushlightuserdata(state, image)
        lua_pushcclosure(state, staticCFunction { state ->
            val x0 = luaL_checkinteger(state, 1).toInt()
            val x1 = luaL_checkinteger(state, 2).toInt()
            val y0 = luaL_checkinteger(state, 3).toInt()
            val y1 = luaL_checkinteger(state, 4).toInt()
            val r = luaL_checkinteger(state, 5).toInt()
            val g = luaL_checkinteger(state, 6).toInt()
            val b = luaL_checkinteger(state, 7).toInt()
            val text = if (8 <= lua_gettop(state)) luaL_checklstring(state, 8, null) else null
            val image = lua_touserdata(state, LUA_REGISTRYINDEX - 1)!!.reinterpret<Image>()
            memScoped {
                val rect = alloc<Rect>()
                rect.x0_ = x0
                rect.x1_ = x1
                rect.y0_ = y0
                rect.y1_ = y1
                DrawRect(image, rect.ptr, r, g, b, text)
            }; 0
        }, 1)
        lua_setglobal(state, "DrawRect")
        lua_settop(state, -1)
    }

    fun bindHttpFunction() {
        lua_pushcclosure(state, staticCFunction { state ->
            HttpGet(luaL_checklstring(state, 1, null)); 0
        }, 0)
        lua_setglobal(state, "HttpGet")
        lua_settop(state, -1)
    }

    fun check(result: Int) {
        if (result != LUA_OK) {
            val text = lua_tolstring(state, -1, null)!!.toKString()
            lua_settop(state, -2)
            throw Error("Lua error: $text")
        }
    }
}
