-- GameNativeDuo for Balatro: drawing for two screens in one window.
--
-- Three vanilla effects assume the window is one screen:
--   * the CRT pass (the final composite) curves, darkens and scanlines the whole window: drawn as two quads, each with
--     its own screen's rectangle, so each screen gets vanilla's look on its own;
--   * the background swirl and the intro splash and flash centre on the window (on the hinge): they get the top
--     screen's coordinates, and the bottom screen continues the same picture below it at its own pixel density;
--   * nothing in vanilla knows the bottom screen's background choice (ui.json "background"): the bottom part of the
--     backdrop is drawn as the swirl, a solid colour or an image, optionally dimmed.
-- The shaders are vanilla's own sources with a remap prepended, compiled once at start-up; if a patched shader does
-- not compile, vanilla's is kept and the effect is drawn as vanilla does.
do
    local D = GNDuo

    D.background = D.background or { mode = "game", dim = 0, color = { 0.063, 0.075, 0.133 } }

    local crt_split = false
    local screen_shaders = {}       -- names of shaders with duo_map / duo_size

    -- The uniforms go right before effect(), after the file's precision #defines.
    local function patch_screen_shader(src)
        local param
        src = src:gsub("love_ScreenSize", "vec4(duo_size, 1.0, 0.0)")
        src = src:gsub("(vec4%s+effect%s*%(([^)]*)%)%s*{)", function(sig, params)
            param = params:match("vec2%s+([%w_]+)%s*$")
            if not param then return sig end
            return "extern vec4 duo_map;\nextern vec2 duo_size;\n\n" ..
                sig .. "\n    " .. param .. " = duo_map.xy + " .. param .. " * duo_map.z;"
        end, 1)
        return param and src or nil
    end

    local function patch_crt(src)
        local n
        src, n = src:gsub("Texel%(%s*tex%s*,", "duo_texel(tex,")
        if n == 0 then return nil end
        local helper = "extern MY_HIGHP_OR_MEDIUMP vec4 duo_rect;\n" ..
            "vec4 duo_texel(Image t, MY_HIGHP_OR_MEDIUMP vec2 c) { return Texel(t, duo_rect.xy + c * duo_rect.zw); }\n\n"
        local found = false
        src = src:gsub("(vec4%s+effect%s*%(([^)]*)%)%s*{)", function(sig, params)
            local tc = params:match("vec2%s+([%w_]+)%s*,")
            if not tc then return sig end
            found = true
            return helper .. sig .. "\n    " .. tc .. " = (" .. tc .. " - duo_rect.xy) / duo_rect.zw;"
        end, 1)
        return found and src or nil
    end

    local function compile(name, src)
        local ok, shader = pcall(love.graphics.newShader, src)
        if ok then return shader end
        D.log("patched %s shader did not compile, keeping vanilla's: %s", name, tostring(shader))
    end

    -- Right after vanilla loads its shaders (Game:start_up).
    function D.patch_shaders(shaders)
        for _, name in ipairs({ "background", "splash", "flash" }) do
            local src = love.filesystem.read("resources/shaders/" .. name .. ".fs")
            local patched = src and patch_screen_shader(src)
            local shader = patched and compile(name, patched)
            if shader then
                shaders[name] = shader
                screen_shaders[#screen_shaders + 1] = name
            end
        end
        local src = love.filesystem.read("resources/shaders/CRT.fs")
        local patched = src and patch_crt(src)
        local shader = patched and compile("CRT", patched)
        if shader then
            shader:send("duo_rect", { 0, 0, 1, 1 })
            shaders.CRT = shader
            crt_split = true
        end
        D.log("shaders: %d screen shaders patched, CRT split %s", #screen_shaders, tostring(crt_split))
        D.map_screen(nil)
    end

    local function send(shader, name, v)
        if shader:hasUniform(name) then shader:send(name, v) end
    end

    -- Points the screen shaders at one screen: nil (the whole window, as vanilla), "top" or "bottom".
    function D.map_screen(which)
        local map, size
        local T, B = D.top, D.bottom
        if not D.active or not which then
            local w, h = love.graphics.getDimensions()
            map, size = { 0, 0, 1, 0 }, { w, h }
        elseif which == "top" then
            map, size = { -T.x, -T.y, 1, 0 }, { T.w, T.h }
        else
            -- Bottom pixels continue the top screen's picture below it, centred, at the bottom screen's density.
            local s = D.bottom_scale > 0 and D.bottom_scale or 1
            map = { (T.w - B.w / s) / 2 - B.x / s, T.h - B.y / s, 1 / s, 0 }
            size = { T.w, T.h }
        end
        for _, name in ipairs(screen_shaders) do
            local shader = G.SHADERS[name]
            if shader then
                send(shader, "duo_map", map)
                send(shader, "duo_size", size)
            end
        end
    end

    local image, image_key
    local function background_image()
        local bg = D.background
        if bg.mode ~= "image" or not D.dir then return nil end
        local key = bg.image_stamp or "?"
        if image_key ~= key then
            image_key = key
            if image then image:release() end
            image = nil
            local bytes = D.read_file(D.dir .. (bg.image or "background.jpg"))
            if bytes then
                local ok, img = pcall(function()
                    return love.graphics.newImage(love.filesystem.newFileData(bytes, "background.jpg"))
                end)
                if ok then image = img else D.log("background image: %s", tostring(img)) end
            end
        end
        return image
    end

    local function fill(r, c, a)
        love.graphics.setColor(c[1], c[2], c[3], a or 1)
        love.graphics.rectangle("fill", r.x, r.y, r.w, r.h)
    end

    -- Replaces `G.SPLASH_BACK:draw()` and `G.SPLASH_FRONT:draw()` in Game:draw.
    function D.draw_backdrop(sprite, back)
        if not D.active then
            sprite:draw()
            return
        end
        local T, B = D.top, D.bottom
        local sx, sy, sw, sh = love.graphics.getScissor()
        love.graphics.setScissor(T.x, T.y, T.w, T.h)
        D.map_screen("top")
        sprite:draw()
        love.graphics.setScissor(B.x, B.y, B.w, B.h)
        local bg = D.background
        local mode = back and bg.mode or "game"
        local img = mode == "image" and background_image()
        if mode == "color" or (mode == "image" and not img) then
            love.graphics.push()
            love.graphics.origin()
            fill(B, bg.color)
            love.graphics.pop()
        elseif img then
            love.graphics.push()
            love.graphics.origin()
            local iw, ih = img:getDimensions()
            local s = math.max(B.w / iw, B.h / ih)
            love.graphics.setColor(1, 1, 1, 1)
            love.graphics.draw(img, B.x + (B.w - iw * s) / 2, B.y + (B.h - ih * s) / 2, 0, s, s)
            love.graphics.pop()
        else
            D.map_screen("bottom")
            sprite:draw()
        end
        if back and mode ~= "color" and (bg.dim or 0) > 0 then
            love.graphics.push()
            love.graphics.origin()
            fill(B, { 0, 0, 0 }, bg.dim)
            love.graphics.pop()
        end
        if sx then love.graphics.setScissor(sx, sy, sw, sh) else love.graphics.setScissor() end
        love.graphics.setColor(1, 1, 1, 1)
        D.map_screen("top")
    end

    -- Before the backdrop is placed each frame: vanilla sizes it to reach a little below the room; make it reach the
    -- bottom of the window (Balatro recreates it on every stage change, so this is checked every frame: one compare).
    function D.cover_bottom(sprite)
        if not (D.active and sprite and sprite.T and G.ROOM) then return end
        local ts = D.ts()
        local needed = D.full_h / ts - (G.ROOM_ORIG and G.ROOM_ORIG.y or G.ROOM.T.y) + 2 - sprite.T.y
        if sprite.T.h < needed then
            sprite.T.h = needed
            sprite.VT.h = needed
        end
    end

    -- Replaces `love.graphics.draw(self.CANVAS, 0, 0)` (the final composite, CRT shader set by vanilla).
    local quads = {}
    local function quad(r, cw, ch)
        local key = r.x .. "," .. r.y .. "," .. r.w .. "," .. r.h .. "," .. cw .. "," .. ch
        local q = quads[key]
        if not q then
            q = love.graphics.newQuad(r.x, r.y, r.w, r.h, cw, ch)
            quads[key] = q
        end
        return q
    end

    -- The HUD's dark panel runs on far below the room (vanilla's window ends there); keep it on the top screen.
    if UIBox and UIBox.draw then
        local orig_draw = UIBox.draw
        function UIBox:draw(...)
            if not (D.active and self == G.HUD) then return orig_draw(self, ...) end
            local T = D.top
            love.graphics.setScissor(T.x, T.y, T.w, T.h)
            local ok, err = pcall(orig_draw, self, ...)
            love.graphics.setScissor()
            if not ok then error(err, 0) end
        end
    end

    function D.composite(canvas)
        if not D.active or (G.CANV_SCALE or 1) ~= 1 then
            love.graphics.draw(canvas, 0, 0)
            return
        end
        local shader = love.graphics.getShader()
        local crt = crt_split and shader ~= nil and shader == G.SHADERS.CRT
        local cw, ch = canvas:getDimensions()
        for _, r in ipairs({ D.top, D.bottom }) do
            if crt then
                shader:send("duo_rect", { r.x / cw, r.y / ch, r.w / cw, r.h / ch })
                send(shader, "scanlines", r.h * 0.75)
            end
            love.graphics.draw(canvas, quad(r, cw, ch), r.x, r.y)
        end
        if crt then shader:send("duo_rect", { 0, 0, 1, 1 }) end
    end
end
