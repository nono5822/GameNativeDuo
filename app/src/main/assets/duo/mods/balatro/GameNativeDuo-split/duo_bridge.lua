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
    -- Packets, socket and parsing come from the Duo SDK (duo/sdk/lua/duo.lua); this file keeps Balatro's own parts.
    local ok_duo, duo = pcall(require, "duo")
    if not ok_duo then D.log("duo.lua unavailable, no bridge: %s", tostring(duo)) end
    if ok_duo and not duo.open() then D.log("luasocket unavailable, no bridge") end

    local CAPS = 1 + 2 + 4 + 8 + 32
    local ENGINE = "balatro-love"
    local HELLO_EVERY = 2

    local next_hello = 0

    local function send(packet)
        if not ok_duo then return end
        duo.port = D.port or 0
        duo.send(packet)
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
        if D.place_open_menu then D.safe("menus", D.place_open_menu) end
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

    -- Every element the editor can change, in every scene (contract "Element features"): what each one supports, and
    -- when it is on screen. Absent ones are listed with present = false so their screen can be chosen ahead.
    local ELEMENTS = {
        { id = "Hand", default = "bottom", when = "During a run",
          -- Scale multiplies the default hand size (1.15); the result stays within 0.8 to 1.4 (duo_layout.lua).
          features = { screens = { "bottom", "top" }, move = true, scale = { min = 0.7, max = 1.2 } } },
        { id = "Consumables", default = "bottom", when = "During a run",
          features = { screens = { "bottom", "top" }, move = true, scale = false } },
        { id = "Hand info", default = "bottom", when = "During a run",
          features = { screens = { "bottom", "hidden" }, move = true, scale = false } },
        { id = "Menus", default = "bottom", when = "While a menu is open",
          features = { screens = { "bottom", "top" }, move = false, scale = false } },
    }
    D.ELEMENTS = ELEMENTS

    local function element(list, def, node)
        local present = node and node.T and not node.REMOVED and true or false
        local x, y, w, h = 0, 0, 0, 0
        local screen = D.screen_of(def.id, def.default)
        if present then
            x, y, w, h = window_rect(node.T)
            if screen ~= "hidden" then screen = D.on_bottom(node.T.y + node.T.h / 2) and "bottom" or "top" end
        end
        list[#list + 1] = { id = def.id, name = def.id, parent = -1, screen = screen, x = x, y = y, w = w, h = h,
            graphic = true, present = present, when = def.when, features = def.features }
    end

    -- What this mod supports, for the app before the game runs and while it is paused (contract "features.json").
    local features_written = false
    local function write_features()
        local elements = {}
        for i, def in ipairs(ELEMENTS) do
            elements[i] = { id = def.id, name = def.id, when = def.when, features = def.features }
        end
        local doc = { format = "gamenative-duo/features", version = 1, engine = ENGINE, touch = true,
            background = { "game", "color", "image" }, elements = elements, dynamic = false }
        return D.write_file(D.dir .. "features.json", D.json_encode(doc))
    end

    local function write_elements(request)
        local list = setmetatable({}, D.ARRAY)
        local run = D.active and G.ROOM and G.STAGE == G.STAGES.RUN
        local nodes = {
            Hand = run and G.hand or nil,
            Consumables = run and G.consumeables or nil,
            ["Hand info"] = run and D.preview_box and D.preview_box() or nil,
            -- The menu's panel: the box itself spans the whole dimmed backdrop, far larger than the window.
            Menus = D.active and G.ROOM and type(G.OVERLAY_MENU) == "table" and { T = D.content_of(G.OVERLAY_MENU) } or nil,
        }
        for _, def in ipairs(ELEMENTS) do element(list, def, nodes[def.id]) end
        local doc = { engine = ENGINE, screen = { w = D.full_w, h = D.full_h }, elements = list }
        if not D.write_file(D.dir .. "elements.json", D.json_encode(doc)) then D.log("elements.json not written") end
        send(duo.packet(duo.ELEMENTS_WRITTEN, duo.le32(request)))
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
        if not ok_duo then return end
        duo.port = D.port or 0
        duo.poll(function(m)
            if m.type == duo.TOUCH then
                local action, pointer = m.action, m.pointer
                if action == duo.CANCEL then action = duo.UP end
                -- Coalesce moves of the same finger.
                local last = queue[#queue]
                if action == duo.MOVE and last and last[1] == duo.MOVE and last[2] == pointer then
                    last[3], last[4] = m.x, m.y
                else
                    queue[#queue + 1] = { action, pointer, m.x, m.y }
                end
            elseif m.type == duo.RULES_CHANGED then
                D.safe("ui.json", D.read_ui, true)
            elseif m.type == duo.DUMP_ELEMENTS then
                dump_request = m.request
            end
        end)
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
            if D.active then
                if not features_written then features_written = D.safe("features.json", write_features) ~= false end
                send(duo.hello_packet(CAPS, ENGINE .. "/sdk" .. duo.VERSION, D.token))
            end
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
