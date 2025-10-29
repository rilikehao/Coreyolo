-- 通用的alpha混色算法
-- 参数：r1,g1,b1 - 起始颜色 (0-255)
--       r2,g2,b2 - 结束颜色 (0-255)
--       alpha - 混合因子 (0.0-1.0)，0.0完全使用起始颜色，1.0完全使用结束颜色
-- 返回：混合后的r,g,b值 (0-255)
function AlphaBlend(r1, g1, b1, r2, g2, b2, alpha)
    -- 确保alpha在0-1范围内
    alpha = math.max(0.0, math.min(1.0, alpha))

    -- 对每个颜色通道进行alpha混合
    -- 公式：result = color1 * (1 - alpha) + color2 * alpha
    local r = math.floor(r1 * (1 - alpha) + r2 * alpha)
    local g = math.floor(g1 * (1 - alpha) + g2 * alpha)
    local b = math.floor(b1 * (1 - alpha) + b2 * alpha)

    return r, g, b
end

-- 颜色计算函数：基于score实现颜色渐变
-- score=1.0时显示为红色(255,0,0)
-- score=0.0时显示为黑色(0,0,0)
-- 中间score值进行线性插值计算
function CalculateColor(score)
    -- 确保score在0-1范围内
    score = math.max(0.0, math.min(1.0, score))

    -- 使用通用的alpha混色算法
    -- 起始颜色：黑色(0,0,0)
    -- 结束颜色：红色(255,0,0)
    -- alpha值：score
    return AlphaBlend(0, 0, 0, 255, 0, 0, score)
end

-- 绘制检测框函数：使用颜色渐变
function DrawRectWithScore(detection)
    local x0, y0, x1, y1 = detection.x0, detection.y0, detection.x1, detection.y1
    local score = detection.score
    local text = detection.text or ""

    -- 计算基于score的颜色
    local r, g, b = CalculateColor(score)

    -- 绘制矩形框
    DrawRect(x0, x1, y0, y1, r, g, b, text)
end

-- 全局报警时间记录
local last_alert_time = nil

-- 检查是否可以发送报警（距离上次报警超过一小时）
function CanSendAlert(current_time)
    -- 如果从未报过警，或者距离上次报警超过3600秒（1小时）
    if last_alert_time == nil or (current_time - last_alert_time) >= 3600 then
        return true, current_time
    end

    return false, last_alert_time
end

-- 记录报警时间
function RecordAlertTime(alert_time)
    last_alert_time = alert_time
end

function Format(pts)
    local seconds = math.floor(pts)
    local micro = math.floor((pts - seconds) * 1000)
    local str = os.date("%Y-%m-%d %H:%M:%S", seconds)
    return string.format("%s.%03d", str, micro)
end

-- HTTP报警函数：基于检测条件发送报警，带一小时内不再报警限制
function SendAlertIfNeeded(pts, detection)
    -- 示例：当检测到person且score>0.8时发送报警
    if detection.text == "人物" and detection.score > 0.8 then
        local can_send, alert_time = CanSendAlert(pts)

        if can_send then
            local url = string.format("http://localhost:8080/alert?object=%s&score=%.2f",
                                    detection.text, detection.score)
            HttpGet(url)
            RecordAlertTime(pts)
            print("报警: " .. Format(pts))
        end
    end
end

-- 英文到中文的标签翻译表
local translations = {
    ["person"] = "人物",
    ["bicycle"] = "自行车",
    ["car"] = "汽车",
    ["motorcycle"] = "摩托车",
    ["airplane"] = "飞机",
    ["bus"] = "公交车",
    ["train"] = "火车",
    ["truck"] = "卡车",
    ["boat"] = "船只",
    ["traffic light"] = "交通灯",
    ["fire hydrant"] = "消防栓",
    ["stop sign"] = "停车标志",
    ["parking meter"] = "计时器",
    ["bench"] = "长椅",
    ["bird"] = "飞鸟",
    ["cat"] = "猫咪",
    ["dog"] = "狗狗",
    ["horse"] = "马匹",
    ["sheep"] = "绵羊",
    ["cow"] = "奶牛",
    ["elephant"] = "大象",
    ["bear"] = "熊类",
    ["zebra"] = "斑马",
    ["giraffe"] = "长颈鹿",
    ["backpack"] = "背包",
    ["umbrella"] = "雨伞",
    ["handbag"] = "手提包",
    ["tie"] = "领带",
    ["suitcase"] = "行李箱",
    ["frisbee"] = "飞盘",
    ["skis"] = "滑雪板",
    ["snowboard"] = "滑雪板",
    ["sports ball"] = "运动球",
    ["kite"] = "风筝",
    ["baseball bat"] = "棒球棒",
    ["baseball glove"] = "棒球套",
    ["skateboard"] = "滑板",
    ["surfboard"] = "冲浪板",
    ["tennis racket"] = "网球拍",
    ["bottle"] = "瓶子",
    ["wine glass"] = "酒杯",
    ["cup"] = "杯子",
    ["fork"] = "叉子",
    ["knife"] = "刀具",
    ["spoon"] = "勺子",
    ["bowl"] = "碗具",
    ["banana"] = "香蕉",
    ["apple"] = "苹果",
    ["sandwich"] = "三明治",
    ["orange"] = "橙子",
    ["broccoli"] = "西兰花",
    ["carrot"] = "胡萝卜",
    ["hot dog"] = "热狗",
    ["pizza"] = "披萨",
    ["donut"] = "甜甜圈",
    ["cake"] = "蛋糕",
    ["chair"] = "椅子",
    ["couch"] = "沙发",
    ["potted plant"] = "盆栽",
    ["bed"] = "床铺",
    ["dining table"] = "餐桌",
    ["toilet"] = "马桶",
    ["tv"] = "电视",
    ["laptop"] = "笔记本",
    ["mouse"] = "鼠标",
    ["remote"] = "遥控器",
    ["keyboard"] = "键盘",
    ["cell phone"] = "手机",
    ["microwave"] = "微波炉",
    ["oven"] = "烤箱",
    ["toaster"] = "烤面包",
    ["sink"] = "水槽",
    ["refrigerator"] = "冰箱",
    ["book"] = "书籍",
    ["clock"] = "时钟",
    ["vase"] = "花瓶",
    ["scissors"] = "剪刀",
    ["teddy bear"] = "泰迪熊",
    ["hair drier"] = "吹风机",
    ["toothbrush"] = "牙刷"
}

-- 标签翻译函数
function Translate(text)
    return translations[text] or text  -- 如果没有翻译，返回原标签
end

-- 全局 Process 函数：处理每一帧数据
-- 参数：pts - 当前帧的最初产生时间 Unix 时间戳, 实数
-- 参数：detections - 检测框数组，每个元素包含 x0, x1, y0, y1, score, text 字段
function Process(pts, detections)
    -- 限制最多画5个框
    local max_boxes = 5
    local count = 0

    -- 遍历所有检测框
    for i, detection in ipairs(detections) do
        if count >= max_boxes then
            break
        end

        -- 翻译标签为中文
        detection.text = Translate(detection.text)

        -- 绘制带颜色渐变的检测框
        DrawRectWithScore(detection)

        -- 根据条件发送HTTP报警
        SendAlertIfNeeded(pts, detection)

        count = count + 1
    end
end

-- 使用说明：
-- 1. 本脚本必须定义全局 Process 函数，接收 pts, detections 参数
-- 2. detections 是数组，每个元素是包含检测框信息的表
-- 3. 每个检测框包含：x0, x1, y0, y1, score, text 字段
-- 4. DrawRect(x0, x1, y0, y1, r, g, b, text = "") - 绘制矩形框
--    - r, g, b: 颜色值(0-255)
--    - text: 可选标签
-- 5. HttpGet(url) - 发送 HTTP GET 请求
-- 6. CalculateColor(score) - 基于置信度计算颜色渐变
--    - score=1.0: 红色(255, 0, 0)
--    - score=0.0: 黑色(0,0,0)
--    - 中间值线性插值

-- 常见使用场景：
-- 1. 根据检测对象类型使用不同颜色
-- 2. 根据置信度高低调整颜色深浅
-- 3. 特定条件下发送HTTP报警
-- 4. 过滤低置信度检测框
