-- runtime_error.lua
-- 故意包含运行时错误的Lua脚本，用于测试错误恢复机制

function Process(boxes)
    NonExistentFunction(box.label)  -- 这个函数不存在，会导致运行时错误
end
