-- GameNativeDuo screen split for Balatro.
--
-- When the window is clearly taller than the top screen's shape (GameNative Duo's "Screen split" with a
-- tall container screen size, e.g. 1280x1835), Balatro lays out its play area in the upper part as usual
-- and the HUD (round score, chips x mult, hands, discards, money, ante, round) moves to the lower part,
-- which GameNative Duo shows on the bottom screen. With a normal window nothing changes.
--
-- GNDUO_TOP_ASPECT (env var, default 16/9) is the top screen's width / height.
GNDuo = GNDuo or {}
do
    local top_aspect = tonumber(os.getenv("GNDUO_TOP_ASPECT") or "") or 16 / 9

    GNDuo.active = false

    -- First thing in love.resize: remember the real window size and return the height Balatro should lay
    -- its room out in (the upper part when split).
    function GNDuo.layout_height(w, h)
        local top_h = math.floor(w / top_aspect + 0.5)
        GNDuo.active = h > top_h * 1.2
        GNDuo.full_w, GNDuo.full_h, GNDuo.top_h = w, h, top_h
        local mode_w, mode_h, flags = love.window.getMode()
        print(string.format("GNDuo: resize %dx%d (window %dx%d, %s), split %s, top %d",
            w, h, mode_w, mode_h, flags and (flags.fullscreen and "fullscreen" or (flags.borderless and "borderless" or "windowed")) or "?",
            tostring(GNDuo.active), top_h))
        return GNDuo.active and top_h or h
    end

    -- The canvas still covers the whole window, so both parts get drawn (and the background fills both).
    function GNDuo.canvas_height(h)
        return GNDuo.active and GNDuo.full_h or h
    end

    -- An invisible anchor covering the lower part, in room coordinates. Recreated whenever Balatro
    -- rebuilds its room (new stage).
    function GNDuo.update_bottom()
        if not (GNDuo.active and G and G.ROOM) then return end
        local ts = G.TILESIZE * G.TILESCALE
        local x, y = -G.ROOM.T.x, GNDuo.top_h / ts - G.ROOM.T.y
        local w, h = GNDuo.full_w / ts, (GNDuo.full_h - GNDuo.top_h) / ts
        if not GNDuo.bottom or GNDuo.bottom_room ~= G.ROOM then
            GNDuo.bottom = Moveable{T = {x = x, y = y, w = w, h = h}}
            GNDuo.bottom.states.drag.can = false
            GNDuo.bottom:set_container(G.ROOM)
            GNDuo.bottom_room = G.ROOM
        else
            GNDuo.bottom:hard_set_T(x, y, w, h)
        end
    end

    -- Balatro's background sprite only reaches a little below the room; stretch it over the lower part too.
    -- Called just before it is drawn, because Balatro recreates it on every stage change.
    function GNDuo.cover_bottom(sprite)
        if not (GNDuo.active and sprite and G.ROOM) then return end
        local ts = G.TILESIZE * G.TILESCALE
        local needed = GNDuo.full_h / ts - G.ROOM.T.y + 2 - sprite.T.y
        if sprite.T.h < needed then
            sprite.T.h = needed
            sprite.VT.h = needed
        end
    end

    -- Right after Balatro creates the HUD for a run: centre it in the lower part instead of the room's left edge.
    function GNDuo.move_hud(hud)
        if not (GNDuo.active and hud) then return end
        GNDuo.update_bottom()
        hud:set_alignment({major = GNDuo.bottom, type = 'cm', offset = {x = 0, y = 0}, bond = 'Strong'})
    end
end
