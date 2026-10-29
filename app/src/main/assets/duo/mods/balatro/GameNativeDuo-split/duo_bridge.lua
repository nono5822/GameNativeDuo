-- GameNativeDuo for Balatro: the bridge to the app (duo/docs/engine-adapter-contract.md in GameNative Duo).
--
--   * HELLO every 2 s (UDP to 127.0.0.1:bridge.port from layout.json): touch, UI rules, element list, background
--     colour and image, game background.
--   * TOUCH: bottom-screen fingers become Balatro's own touch input (its touch mode: no cursor, hold to see a
--     tooltip), not mouse clicks through Wine.
--   * RULES_CHANGED / ui.json: which screen the hand, consumables, hand info and menus use, shifts, background.
--   * DUMP_ELEMENTS: writes elements.json (where those elements are now) for the app's layout editor.
-- The socket is non-blocking and polled once per frame (one syscall when nothing arrived); no thread, no allocation
-- per frame.
do
    local D = GNDuo
    local ok_socket, socket = pcall(require, "socket")
    local ok_ffi, ffi = pcall(require, "ffi")
    if not ok_socket then D.log("luasocket unavailable, no bridge: %s", tostring(socket)) end

    local CAPS = 1 + 2 + 4 + 8 + 32
    local ENGINE = "balatro-love"
    local HELLO_EVERY = 2

    local udp
    local next_hello = 0
    local floats = ok_ffi and ffi.new("float[2]")
    local u32 = ok_ffi and ffi.new("uint32_t[1]")

    local function le32(n)
        return string.char(n % 256, math.floor(n / 256) % 256, math.floor(n / 65536) % 256, math.floor(n / 16777216) % 256)
    end
    local function header(t) return "GD\1" .. string.char(t) end

    local function open()
        if udp or not ok_socket then return udp end
        local s = socket.udp()
        if not s then return nil end
        s:settimeout(0)
        local ok, err = s:setsockname("127.0.0.1", 0)
        if not ok then
            D.log("bridge socket: %s", tostring(err))
            s:close()
            return nil
        end
        udp = s
        return udp
    end

    local function send(packet)
        if D.port and open() then udp:sendto(packet, "127.0.0.1", D.port) end
    end

    ---------------------------------------------------------------------------------------------------------------
    -- ui.json

    local ui_text
    local function hex_color(s)
        if type(s) ~= "string" then return nil end
        local r, g, b = s:match("^#(%x%x)(%x%x)(%x%x)")
        if not r then return nil end
        return { tonumber(r, 16) / 255, tonumber(g, 16) / 255, tonumber(b, 16) / 255 }
    end

    function D.read_ui(force)
        if not D.dir then return end
        local text = D.read_file(D.dir .. "ui.json")
        if text == ui_text and not force then return end
        ui_text = text
        local data = {}
        if text then
            local ok, res = pcall(D.json_decode, text)
            if ok and type(res) == "table" then data = res else D.log("ui.json unreadable: %s", tostring(res)) end
        end
        D.rules = type(data.elements) == "table" and data.elements or {}
        local bg = type(data.background) == "table" and data.background or {}
        local mode = bg.mode == "color" and "color" or bg.mode == "image" and "image" or "game"
        D.background = {
            mode = mode,
            color = hex_color(bg.color) or { 0.063, 0.075, 0.133 },
            dim = math.max(0, math.min(0.9, tonumber(bg.dim) or 0)),
            image = type(bg.image) == "string" and bg.image:match("^[%w_.-]+$") or "background.jpg",
            image_stamp = tostring(love.timer.getTime()),
        }
        D.log("ui.json: %d rules, background %s", (function() local n = 0 for _ in pairs(D.rules) do n = n + 1 end return n end)(), mode)
        D.safe("relayout", D.relayout)
        if D.preview_rules_changed then D.safe("preview", D.preview_rules_changed) end
    end

    ---------------------------------------------------------------------------------------------------------------
    -- elements.json

    local function window_rect(t)
        local ts = D.ts()
        local o = G.ROOM_ORIG or G.ROOM.T
        return math.floor((t.x + o.x) * ts + 0.5), math.floor((t.y + o.y) * ts + 0.5),
            math.floor(t.w * ts + 0.5), math.floor(t.h * ts + 0.5)
    end

    local function element(list, id, name, node, default_screen)
        if not (node and node.T and not node.REMOVED) then return end
        local x, y, w, h = window_rect(node.T)
        local screen = D.screen_of(id, default_screen)
        if screen ~= "hidden" then screen = D.on_bottom(node.T.y + node.T.h / 2) and "bottom" or "top" end
        list[#list + 1] = { id = id, name = name, parent = -1, screen = screen, x = x, y = y, w = w, h = h, graphic = true }
    end

    local function write_elements(request)
        local list = setmetatable({}, D.ARRAY)
        if D.active and G.ROOM and G.STAGE == G.STAGES.RUN then
            element(list, "Hand", "Hand", G.hand, "bottom")
            element(list, "Consumables", "Consumables", G.consumeables, "bottom")
            if D.preview_box then element(list, "Hand info", "Hand info", D.preview_box(), "bottom") end
        end
        if D.active and G.ROOM and type(G.OVERLAY_MENU) == "table" then
            element(list, "Menus", "Menus", G.OVERLAY_MENU, "bottom")
        end
        local doc = { engine = ENGINE, screen = { w = D.full_w, h = D.full_h }, elements = list }
        if not D.write_file(D.dir .. "elements.json", D.json_encode(doc)) then D.log("elements.json not written") end
        send(header(0x83) .. le32(request))
    end

    ---------------------------------------------------------------------------------------------------------------
    -- Touch

    local touch = { active = false, x = 0, y = 0, pointer = nil, injecting = false }
    D.touch = touch

    if love.mouse and love.mouse.getPosition then
        local real = love.mouse.getPosition
        love.mouse.getPosition = function()
            if touch.active then return touch.x, touch.y end
            return real()
        end
    end

    -- A real mouse event (the top screen's touch through Wine, a mouse) takes the cursor back.
    for _, name in ipairs({ "mousemoved", "mousepressed" }) do
        local orig = love[name]
        love[name] = function(...)
            if not touch.injecting then touch.active = false end
            if orig then return orig(...) end
        end
    end

    local function inject(fn, ...)
        touch.injecting = true
        G.CONTROLLER.last_touch_time = G.TIMERS.UPTIME
        local ok, err = pcall(fn, ...)
        touch.injecting = false
        if not ok then D.log("touch: %s", tostring(err)) end
    end

    local queue = {}   -- pending {action, pointer, x, y}

    local function apply_touches()
        if #queue == 0 or not (G and G.CONTROLLER and G.TIMERS) then return end
        local i = 1
        while i <= #queue do
            local t = queue[i]
            local action, pointer, x, y = t[1], t[2], t[3], t[4]
            i = i + 1
            if action == 0 then
                if not touch.pointer then
                    touch.pointer, touch.active, touch.x, touch.y = pointer, true, x, y
                    inject(love.mousemoved, x, y, 0, 0, true)
                    inject(love.mousepressed, x, y, 1, true)
                    break  -- the press is handled in this frame's update; a release waits for the next one
                end
            elseif pointer == touch.pointer then
                local dx, dy = x - touch.x, y - touch.y
                touch.x, touch.y = x, y
                if action == 1 then
                    inject(love.mousemoved, x, y, dx, dy, true)
                else
                    inject(love.mousereleased, x, y, 1, true)
                    touch.pointer = nil
                    break
                end
            end
        end
        -- Drop what was handled; keep the rest (in order) for the next frame.
        local n = #queue
        for k = i, n do queue[k - i + 1] = queue[k] end
        for k = n - i + 2, n do queue[k] = nil end
    end

    ---------------------------------------------------------------------------------------------------------------
    -- Per frame

    local dump_request

    local function receive()
        if not udp then return end
        for _ = 1, 64 do
            local data, ip, port = udp:receivefrom()
            if not data then return end
            if ip == "127.0.0.1" and port == D.port and #data >= 4 and data:sub(1, 3) == "GD\1" then
                local t = data:byte(4)
                if t == 0x01 and #data >= 16 and ok_ffi then
                    ffi.copy(floats, data:sub(9, 16), 8)
                    local action, pointer = data:byte(5), data:byte(6)
                    if action == 3 then action = 2 end
                    -- Coalesce moves of the same finger.
                    local last = queue[#queue]
                    if action == 1 and last and last[1] == 1 and last[2] == pointer then
                        last[3], last[4] = floats[0], floats[1]
                    else
                        queue[#queue + 1] = { action, pointer, floats[0], floats[1] }
                    end
                elseif t == 0x02 then
                    D.safe("ui.json", D.read_ui, true)
                elseif t == 0x03 and #data >= 8 and ok_ffi then
                    ffi.copy(u32, data:sub(5, 8), 4)
                    dump_request = tonumber(u32[0])
                end
            end
        end
    end

    function D.bridge_update()
        local now = love.timer.getTime()
        if now >= next_hello then
            next_hello = now + HELLO_EVERY
            -- layout.json carries this session's port and the calibration; ui.json may have been written while the
            -- bridge was not listening (the app also signals changes, this is the fallback).
            if D.read_layout() and D.active and D.full_w > 0 then
                D.log("layout.json changed (port %s)", tostring(D.port))
            end
            D.safe("ui.json", D.read_ui)
            if D.active then send(header(0x81) .. le32(CAPS) .. ENGINE) end
        end
        receive()
        apply_touches()
        if dump_request then
            local r = dump_request
            dump_request = nil
            D.safe("elements.json", write_elements, r)
        end
    end

    local orig_update = love.update
    function love.update(dt)
        if D.active then D.safe("bridge", D.bridge_update) end
        D.safe("layout", D.layout_update)
        D.safe("preview", D.preview_update)
        return orig_update(dt)
    end
end
