dofile("lua/lib.lua")
dofile("lua/region.lua")

function DrawRectWith(detection, r, g, b, text)
    local x0, y0, x1, y1 = detection.x0, detection.y0, detection.x1, detection.y1
    DrawRect(x0, x1, y0, y1, r, g, b, text)
end

function Process(pts, detections)
    local persons = 0
    local helmets = 0
    for i, detection in ipairs(detections) do
        local cx, cy = Center(detections)
        if 0 < CountTriangles(cx, cy, Region) then
            detection.inside = true
            if detection.text == "person" and 0.5 < detection.score then
                persons = persons + 1
            end
            if detection.text == "helmet" and 0.5 < detection.score then
                helmets = helmets + 1
            end
        end
    end
    for i, detection in ipairs(detections) do
        if detection.inside then
            if detection.text == "person" and 0.5 < detection.score then
                if helmets < persons then
                    DrawRectWith(detection, 255, 0, 0, "人员")
                else
                    DrawRectWith(detection, 0, 255, 0, "人员")
                end
            end
            if detection.text == "helmet" and 0.5 < detection.score then
                DrawRectWith(detection, 0, 0, 255, "安全帽")
            end
        end
    end
end
