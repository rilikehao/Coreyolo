function Sign(p1x, p1y, p2x, p2y, p3x, p3y)
    return (p1x - p3x) * (p2y - p3y) - (p2x - p3x) * (p1y - p3y)
end

function PointInTriangle(px, py, tx, ty, ux, uy, vx, vy)
    local d1 = Sign(px, py, tx, ty, ux, uy)
    local d2 = Sign(px, py, ux, uy, vx, vy)
    local d3 = Sign(px, py, vx, vy, tx, ty)
    
    return (d1 >= 0 and d2 >= 0 and d3 >= 0) or (d1 <= 0 and d2 <= 0 and d3 <= 0)
end

function CountTriangles(x, y, triangles)
    local count = 0
    for i = 1, #triangles do
        local tri = triangles[i]
        if PointInTriangle(x, y, tri[1], tri[2], tri[3], tri[4], tri[5], tri[6]) then
            count = count + 1
        end
    end
    return count
end
