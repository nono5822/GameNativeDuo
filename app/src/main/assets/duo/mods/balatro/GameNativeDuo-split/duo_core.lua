-- GameNativeDuo for Balatro: where the two screens are.
--
-- GameNative Duo gives the game one tall window: the top screen's part above, the bottom screen's part below
-- (`layout.json`, written by the app: window size, both rectangles, the screens' physical calibration). Balatro lays
-- its room out for the top part only (love.resize is told the top part's height), its canvas still covers the whole
-- window, and duo_layout.lua moves what the player touches (hand, shop, packs, menus) into the bottom part, in the
-- same room coordinates. One frame, one render pass, no copies: the app shows each part on its screen.
--
-- With a normal window (Dual screen off, other devices) GNDuo.active stays false and every hook is a no-op.
do
    local D = GNDuo

    D.active = false
    D.top, D.bottom = nil, nil      -- window pixel rectangles {x, y, w, h}
    D.full_w, D.full_h = 0, 0       -- the real window
    D.bottom_scale = 1              -- bottom-screen pixels per top-screen pixel of the same length
    D.port = nil                    -- the app's bridge port (layout.json), nil without one

    local layout, layout_text

    local function rect(r)
        if type(r) ~= "table" or not tonumber(r.w) or not tonumber(r.h) then return nil end
        return { x = tonumber(r.x) or 0, y = tonumber(r.y) or 0, w = tonumber(r.w), h = tonumber(r.h) }
    end

    -- Re-reads layout.json; returns true when its content changed. Cheap (a small file), called every 2 s.
    function D.read_layout()
        local text = D.dir and D.read_file(D.dir .. "layout.json")
        if text == layout_text then return false end
        layout_text = text
        layout = nil
        if text then
            local ok, data = pcall(D.json_decode, text)
            if ok and type(data) == "table" then
                layout = data
            else
                D.log("layout.json unreadable: %s", tostring(data))
            end
        end
        local port = layout and type(layout.bridge) == "table" and tonumber(layout.bridge.port)
        D.port = port and port > 0 and port or nil
        D.bottom_scale = layout and tonumber(layout.bottomScale) or 1
        return true
    end

    -- The two parts of a w x h window, or nil when it is not split.
    local function split_for(w, h)
        if layout and type(layout.screen) == "table"
            and math.abs((tonumber(layout.screen.w) or 0) - w) <= 2
            and math.abs((tonumber(layout.screen.h) or 0) - h) <= 2 then
            local t, b = rect(layout.top), rect(layout.bottom)
            if t and b and t.w > 0 and t.h > 0 and b.w > 0 and b.h > 0 then return t, b end
        end
        -- No layout.json for this window (an older container, or the file is late): a window clearly taller than
        -- 16:9 is cut like GameNative Duo's "fill" layout, top part 16:9 and the rest below.
        local th = math.floor(w * 9 / 16 + 0.5)
        if h > th * 1.2 then
            return { x = 0, y = 0, w = w, h = th }, { x = 0, y = th, w = w, h = h - th }
        end
    end

    -- First line of love.resize: remembers the real window and returns the height Balatro lays its room out in.
    function D.layout_height(w, h)
        D.full_w, D.full_h = w, h
        if not layout_text then D.read_layout() end
        local t, b = split_for(w, h)
        local was = D.active
        D.active = t ~= nil and t.y == 0
        D.top, D.bottom = t, b
        if D.active then
            D.log("window %dx%d: top %d,%d %dx%d, bottom %d,%d %dx%d", w, h, t.x, t.y, t.w, t.h, b.x, b.y, b.w, b.h)
        elseif was then
            D.log("window %dx%d: not split", w, h)
        end
        if D.on_split_change and was ~= D.active then D.safe("split change", D.on_split_change) end
        return D.active and t.h or h
    end

    -- Just before love.resize creates the canvas: it covers the whole window, so both parts are drawn.
    function D.canvas_height(h)
        return D.active and D.full_h or h
    end

    -- Tile size in pixels.
    function D.ts() return G.TILESIZE * G.TILESCALE end

    local function room_xy()
        local o = G.ROOM_ORIG or (G.ROOM and G.ROOM.T) or { x = 0, y = 0 }
        return o.x, o.y
    end

    -- A window pixel rectangle in room tiles (the coordinates every game object uses).
    function D.room_rect(r)
        local ts, rx, ry = D.ts(), room_xy()
        return r.x / ts - rx, r.y / ts - ry, r.w / ts, r.h / ts
    end

    function D.bottom_room() return D.room_rect(D.bottom) end
    function D.top_room() return D.room_rect(D.top) end

    -- Vertical distance in tiles from the top screen's centre to the bottom screen's: what vanilla places at height
    -- y on its own screen sits at y + dy on the bottom screen, at the same height on that screen.
    function D.dy()
        return ((D.bottom.y + D.bottom.h / 2) - (D.top.y + D.top.h / 2)) / D.ts()
    end

    -- An invisible Moveable covering the bottom screen, for things aligned to it. Balatro deletes every Moveable on
    -- a stage change, so it is recreated on demand.
    local anchor, anchor_room
    function D.anchor()
        if not (D.active and G.ROOM) then return G.ROOM_ATTACH end
        local x, y, w, h = D.bottom_room()
        if not anchor or anchor.REMOVED or anchor_room ~= G.ROOM then
            anchor = Moveable { T = { x = x, y = y, w = w, h = h } }
            anchor.states.drag.can = false
            anchor.states.collide.can = false
            anchor:set_container(G.ROOM)
            anchor_room = G.ROOM
        elseif math.abs(anchor.T.x - x) > 0.001 or math.abs(anchor.T.y - y) > 0.001
            or math.abs(anchor.T.w - w) > 0.001 or math.abs(anchor.T.h - h) > 0.001 then
            anchor:hard_set_T(x, y, w, h)
        end
        return anchor
    end

    -- In love.resize, after the room is placed: keep the anchor in step.
    function D.update_bottom()
        if D.active and G.ROOM then D.anchor() end
    end

    -- Puts a UIBox in the bottom screen (aligned to the anchor), keeping its own offset unless one is given.
    -- Centring ('c'/'m') measures the box's Mid element, whose transform lags the box for a few frames after it is
    -- created or moved, so the alignment is computed again every frame until it stops changing (at most 30 frames).
    function D.to_bottom(box, align, offset)
        if not (D.active and box and not box.REMOVED) then return end
        box:set_alignment({ major = D.anchor(), type = align or box.alignment.type,
            offset = offset or { x = box.alignment.offset.x, y = box.alignment.offset.y }, bond = 'Weak' })
        box:align_to_major()
        local frames, last_x, last_y, same = 0, nil, nil, 0
        G.E_MANAGER:add_event(Event({ blockable = false, blocking = false, func = function()
            frames = frames + 1
            if box.REMOVED or not box.role or box.role.major ~= anchor or frames > 30 then return true end
            box.alignment.prev_type = nil
            box:align_to_major()
            local x, y = box.role.offset.x, box.role.offset.y
            same = (last_x and math.abs(x - last_x) < 0.001 and math.abs(y - last_y) < 0.001) and same + 1 or 0
            last_x, last_y = x, y
            return same >= 3
        end }))
    end

    -- What a UIBox shows: menus are a screen-sized dimmer (100 x 57 tiles) around their panel, so measure the panel.
    function D.content_of(box)
        local root = box.UIRoot
        local panel = root and root.T and root.T.w > 30 and root.children and root.children[1]
        return panel and panel.T or box.T
    end

    -- True when a UIBox's content fits the bottom screen with a small margin.
    function D.fits_bottom(box)
        if not (D.active and box and box.T) then return false end
        local _, _, w, h = D.bottom_room()
        local c = D.content_of(box)
        return c.w <= w - 0.3 and c.h <= h - 0.3
    end

    -- Vanilla treats anything below the room as off screen: no cursor collision, no gamepad focus, no button
    -- shortcuts, no text sounds (lovely.toml swaps G.ROOM.T.h for this in those checks). Split, the window goes on
    -- down to the bottom of the bottom screen.
    function D.room_h()
        if not (D.active and G.ROOM) then return G.ROOM.T.h end
        local _, by, _, bh = D.bottom_room()
        return math.max(G.ROOM.T.h, by + bh)
    end

    -- Vanilla also waits for a few panels to slide in by comparing their height with fixed values (the pack's cards
    -- are dealt once its area is above the room's bottom, the blind choices come alive once the blind select is above
    -- y 10). Those panels hang from the hand, so on the bottom screen they sit dy lower: compare as vanilla would.
    function D.vanilla_y(y)
        if D.hand_on_bottom and D.hand_on_bottom() and G.ROOM and y > G.ROOM.T.h then return y - D.dy() end
        return y
    end

    -- Which screen a room point is on (for tooltips and the element list).
    function D.on_bottom(y)
        if not D.active then return false end
        local _, by = D.bottom_room()
        return y >= by
    end
end
