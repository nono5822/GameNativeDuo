-- A whole LÖVE game with a dual-screen element: the score sits on the bottom screen, the field on the top screen.
-- Copy duo/sdk/lua/duo.lua next to this file. Run it windowed at layout.json's screen size (duo sim does that).
local duo = require("duo")

local score, dots = 0, {}
local score_box = { x = 0, y = 0, w = 300, h = 80 }

function love.load()
    duo.start({ engine = "love-example", log = print })
    -- The editors list "Score": the user can move it, put it on the top screen or hide it; it does not resize.
    duo.elements.register("Score", "Score", function()
        return score_box.x, score_box.y, score_box.w, score_box.h
    end, { features = { scale = false } })
    -- Bottom-screen fingers arrive in window pixels, on the game thread.
    duo.on_touch(function(action, pointer, x, y)
        if action == duo.DOWN then
            dots[#dots + 1] = { x, y }
            score = score + 1
            duo.haptic(15)
        end
    end)
    duo.on_command("example.reset", function()
        score, dots = 0, {}
        return duo.OK
    end)
end

-- Where the score goes: its screen from the user's rule, shifted by the rule's x/y (fractions of that screen).
local function place_score()
    local l = duo.layout
    if not (l and duo.is_split()) then score_box.x, score_box.y = 10, 10 return end
    local screen = duo.elements.screen_of("Score")
    local r = screen == "top" and l.top or l.bottom
    local rule = duo.rule("Score") or {}
    score_box.x = r.x + 20 + (rule.x or 0) * r.w
    score_box.y = r.y + 20 + (rule.y or 0) * r.h
    score_box.hidden = screen == "hidden"
end

function love.update()
    duo.update(love.graphics.getDimensions())
    place_score()
    duo.publish("example.score", score)  -- shown by a companion card, sent only while one is visible
end

function love.draw()
    for _, d in ipairs(dots) do love.graphics.circle("fill", d[1], d[2], 12) end
    if not score_box.hidden then
        love.graphics.rectangle("line", score_box.x, score_box.y, score_box.w, score_box.h)
        love.graphics.print("Score: " .. score, score_box.x + 16, score_box.y + 24, 0, 2)
    end
end
