-- syntax_error.lua
-- 故意包含语法错误的Lua脚本，用于测试错误恢复机制

function process(pts, boxes)
    -- 故意的语法错误：缺少end关键字
    for i, box in ipairs(boxes) do
        if box.score > 0.5 then
            local r, g, b = CalculateColor(box.score)
            DrawBox(box.x0, box.y0, box.x1, box.y1, r, g, b, box.label)
        end
    -- 注意：这里故意缺少end关键字来制造语法错误
