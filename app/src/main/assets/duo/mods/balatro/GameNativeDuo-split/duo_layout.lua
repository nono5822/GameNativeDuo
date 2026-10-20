-- GameNativeDuo for Balatro: what goes on which screen.
--
-- Bottom screen: the hand and its buttons, and everything Balatro places relative to the hand (shop, blind select,
-- booster packs, cash out), the consumables, a hand preview (duo_preview.lua) and the menus that fit.
-- Top screen: the table (HUD, jokers, played hand, deck), recentred into the room the hand left.
--
-- Wrap, do not replace: vanilla positions everything first and we move the result, so vanilla behaviour (including
-- its animations) is kept. Every write is behind GNDuo.active.
do
    local D = GNDuo

    -- User choices (ui.json, see duo_bridge.lua). Defaults: everything the player touches on the bottom screen.
    D.rules = D.rules or {}
    function D.rule(id)
        return D.rules[id] or {}
    end
    function D.screen_of(id, default)
        local s = D.rule(id).screen
        return (s == "top" or s == "bottom" or s == "hidden") and s or default
    end

    local PLAY_DROP = 1.0          -- the played hand sits this much lower on the top screen (the hand's band is free)
    local EDGE = 0.35              -- margin from the bottom screen's edges, in tiles

    -- Extra lift of the (empty) hand per state, so what hangs from it is centred on the bottom screen. Measured on the
    -- device (vanilla's layouts leave the top of its screen to the joker row, which the bottom screen does not have).
    D.hand_lift = D.hand_lift or {}
    if G and G.STATES then
        -- The cash out list grows with every source of money; start it right under the consumables.
        D.hand_lift[G.STATES.ROUND_EVAL] = 1.1
    end

    -- The played hand's area (G.play) is also where vanilla opens what you buy (booster packs, vouchers) and where a
    -- tag's free pack appears: in the shop, the blind select and while a pack is open it waits on the bottom screen,
    -- so a pack bursts open where its cards are dealt. It only moves while empty.
    local function play_on_bottom_state()
        local S, st = G.STATES, G.STATE
        return st == S.SHOP or st == S.BLIND_SELECT or st == S.TAROT_PACK or st == S.PLANET_PACK
            or st == S.SPECTRAL_PACK or st == S.STANDARD_PACK or st == S.BUFFOON_PACK
    end

    local function hand_on_bottom() return D.active and D.screen_of("Hand", "bottom") == "bottom" end
    D.hand_on_bottom = hand_on_bottom

    local function shift(id)
        local r = D.rule(id)
        local _, _, bw, bh = D.bottom_room()
        return (tonumber(r.x) or 0) * bw, (tonumber(r.y) or 0) * bh
    end

    -- The hand's resting height (CardArea:move eases G.hand.T.y towards it every frame); see lovely.toml.
    function D.hand_y(v)
        if not hand_on_bottom() then return v end
        local _, sy = shift("Hand")
        local lift = G.STATE and D.hand_lift[G.STATE] or 0
        return v + D.dy() + sy - lift
    end

    -- The CardArea backing (the translucent band with "0/2") is built once at the area's size; drop it after a resize
    -- so the next draw rebuilds it (it holds only its own labels).
    local function drop_backing(area)
        local ab = area.children and area.children.area_uibox
        if ab then
            pcall(ab.remove, ab)
            area.children.area_uibox = nil
        end
    end

    local function place(area, x, y)
        if not area or not area.T then return end
        if math.abs(area.T.x - x) < 0.001 and math.abs(area.T.y - y) < 0.001 then return end
        area.T.x, area.T.y = x, y
        area:hard_set_VT()
        drop_backing(area)
    end

    -- Vanilla joker row width, captured per run (G.jokers is new every run).
    local joker_w = setmetatable({}, { __mode = "k" })

    -- After vanilla's set_screen_positions in a run.
    function D.place_run()
        if not (G.hand and G.play and G.jokers and G.consumeables and G.deck and G.discard) then return end
        joker_w[G.jokers] = joker_w[G.jokers] or G.jokers.T.w
        if not hand_on_bottom() then
            D.play_top = nil
            -- Vanilla, apart from the consumables if they were asked to move.
            if G.jokers.T.w ~= joker_w[G.jokers] then
                G.jokers.T.w = joker_w[G.jokers]
                G.jokers:hard_set_VT()
                drop_backing(G.jokers)
            end
            return
        end
        local bx, by, bw, bh = D.bottom_room()
        local hx, hy = shift("Hand")

        -- Bottom screen: the hand, centred (vanilla keeps it right of the HUD, which is not down here).
        G.hand.T.x = bx + (bw - G.hand.T.w) / 2 + hx
        G.hand.T.y = D.hand_y(G.hand.T.y)
        G.hand:hard_set_VT()

        -- Consumables: top right of the bottom screen (or vanilla's spot, beside the joker row).
        local cons_bottom = D.screen_of("Consumables", "bottom") == "bottom"

        -- Top screen: the room right of the HUD is free now; centre the joker row and the played hand in it, and give
        -- the jokers more width (fewer overlaps once a run has many).
        local left = (G.HUD and G.HUD.T and (G.HUD.T.x + G.HUD.T.w) or 4.6) + 0.15
        local right = G.TILE_W - 0.3
        local avail = right - left
        local base_w = joker_w[G.jokers]
        local jw = cons_bottom and math.max(base_w, math.min(avail - 0.6, base_w * 1.3)) or base_w
        if math.abs(G.jokers.T.w - jw) > 0.001 then
            G.jokers.T.w = jw
            drop_backing(G.jokers)
        end
        local mid = left + avail / 2
        if cons_bottom then
            place(G.jokers, mid - jw / 2, G.jokers.T.y)
            local cx, cy = shift("Consumables")
            place(G.consumeables, bx + bw - G.consumeables.T.w - EDGE + cx, by + EDGE + cy)
        else
            place(G.jokers, G.jokers.T.x, G.jokers.T.y)
            place(G.consumeables, G.jokers.T.x + G.jokers.T.w + 0.2, 0)
        end
        D.play_top = { x = mid - G.play.T.w / 2, y = G.play.T.y + PLAY_DROP, vanilla_y = G.play.T.y }
        place(G.play, D.play_top.x, D.play_top.y)
        -- Discards slide off the bottom screen's right edge (vanilla: off the top screen's).
        place(G.discard, bx + bw + 1.5, by + bh / 2)
        G.jokers:hard_set_VT()
        for _, a in ipairs({ G.jokers, G.consumeables, G.play, G.discard }) do
            if a.align_cards then pcall(a.align_cards, a) end
        end
    end

    -- The hand on the bottom screen is drawn larger (rule "Hand" scale, default 1.15: about the top screen's card size
    -- in physical size, and the bottom screen's width). The area and every card in it are resized; a card leaving the
    -- hand (played, discarded, sold back to the deck) gets its own size back. Sizes ease (vanilla's move_wh), so cards
    -- grow as they are dealt. Converged every frame; a quiet frame costs one comparison per card.
    local HAND_SCALE = 1.15
    local card_size = setmetatable({}, { __mode = "k" })   -- card -> its own {w, h}, while in the hand
    local hand_base                                          -- the hand area's own size, per run

    function D.hand_scale()
        if not hand_on_bottom() then return 1 end
        local s = tonumber(D.rule("Hand").scale) or HAND_SCALE
        local _, _, bw = D.bottom_room()
        local fit = hand_base and (bw - 0.4) / hand_base.w or 1.2
        return math.max(0.8, math.min(s, fit, 1.4))
    end

    function D.layout_update()
        local hand = G.hand
        if not (G.STAGE == G.STAGES.RUN and hand and hand.T and hand.cards) then return end
        if not hand_base or hand_base.area ~= hand then
            hand_base = { area = hand, w = hand.T.w, h = hand.T.h, card_w = hand.card_w or G.CARD_W }
        end
        local s = D.hand_scale()
        local w, h = hand_base.w * s, hand_base.h * s
        if math.abs(hand.T.w - w) > 0.001 or math.abs(hand.T.h - h) > 0.001 then
            local cx = hand.T.x + hand.T.w / 2
            hand.T.w, hand.T.h, hand.card_w = w, h, hand_base.card_w * s
            hand.T.x = cx - w / 2
            drop_backing(hand)
        end
        if hand_on_bottom() then
            local bx, _, bw = D.bottom_room()
            local x = bx + (bw - hand.T.w) / 2 + shift("Hand")
            if math.abs(hand.T.x - x) > 0.001 then hand.T.x = x end
        end
        local play, top = G.play, D.play_top
        if top and play and play.T and play.cards and #play.cards == 0 then
            local x, y = top.x, top.y
            if hand_on_bottom() and play_on_bottom_state() then
                local bx, _, bw = D.bottom_room()
                x, y = bx + (bw - play.T.w) / 2, top.vanilla_y + D.dy()
            end
            if math.abs(play.T.x - x) > 0.001 or math.abs(play.T.y - y) > 0.001 then
                play.T.x, play.T.y = x, y
                play:hard_set_VT()
            end
        end
        for _, card in ipairs(hand.cards) do
            local own = card_size[card]
            if not own then
                own = { w = card.T.w, h = card.T.h }
                card_size[card] = own
            end
            local tw, th = own.w * s, own.h * s
            if math.abs(card.T.w - tw) > 0.001 or math.abs(card.T.h - th) > 0.001 then card.T.w, card.T.h = tw, th end
        end
        for card, own in pairs(card_size) do
            if card.area ~= hand then
                if not card.REMOVED then card.T.w, card.T.h = own.w, own.h end
                card_size[card] = nil
            end
        end
    end

    -- Re-runs the layout now (rules changed, window changed). Only where vanilla's own function is safe to call.
    function D.relayout()
        if not (G and G.STAGES and type(set_screen_positions) == "function") then return end
        if G.STAGE == G.STAGES.RUN and G.hand and G.play and G.jokers and G.consumeables and G.deck and G.discard then
            set_screen_positions()
        elseif G.STAGE == G.STAGES.MAIN_MENU and G.title_top then
            set_screen_positions()
            D.to_bottom(G.MAIN_MENU_UI, 'cm', { x = 0, y = 0 })
            D.to_bottom(G.PROFILE_BUTTON, 'bli', { x = EDGE, y = -EDGE })
        end
    end
    D.on_split_change = function()
        -- love.resize runs before the room's objects exist on start-up; later (a window change) relayout.
        if G and G.ROOM then D.relayout() end
    end

    local orig_ssp = set_screen_positions
    if type(orig_ssp) == "function" then
        function set_screen_positions(...)
            orig_ssp(...)
            if not D.active then return end
            if G.STAGE == G.STAGES.RUN then
                D.safe("place_run", D.place_run)
            elseif G.STAGE == G.STAGES.MAIN_MENU and G.title_top then
                -- The menu buttons are on the bottom screen: centre the title (vanilla lifts it above them).
                G.title_top.T.y = G.TILE_H / 2 - G.title_top.T.h / 2 - 0.5
                G.title_top:hard_set_VT()
            end
        end
    else
        D.log("set_screen_positions not found: layout disabled")
    end

    -- Main menu buttons: vanilla puts the Discord / X / language buttons in a column right of the buttons (16.8 tiles
    -- wide in all, wider than the bottom screen). Split, they go in a row under the buttons instead.
    if type(create_UIBox_main_menu_buttons) == "function" then
        local orig_menu = create_UIBox_main_menu_buttons
        function create_UIBox_main_menu_buttons(...)
            local t = orig_menu(...)
            if D.active then
                D.safe("main menu", function()
                    local left, right = t.nodes and t.nodes[1], t.nodes and t.nodes[2]
                    if not (left and right and right.nodes) then return end
                    left.n = G.UIT.R
                    right.n = G.UIT.R
                    right.config.align, right.config.minw = "cm", nil
                    for i = 1, 4 do
                        local r = right.nodes[i]
                        if r and r.n == G.UIT.R then r.n = G.UIT.C end
                    end
                    t.nodes = { left, right }
                end)
            end
            return t
        end
    end

    -- Blind select: vanilla places it below the joker row (its offset is measured from the jokers, not the hand).
    -- Called right after vanilla sets that offset; centres it on the bottom screen instead.
    -- The box is far taller than its screen (the blinds' columns run on below the window), so it is placed by its top.
    D.BLIND_SELECT_TOP = 3.75
    function D.place_blind_select()
        local box = G.blind_select
        if not (hand_on_bottom() and box and G.hand) then return end
        local _, by = D.bottom_room()
        -- 'bmi': box bottom = hand bottom + offset.y
        box.alignment.offset.y = by + D.BLIND_SELECT_TOP - G.hand.T.y - G.hand.T.h + box.T.h
    end

    -- Booster pack particles fill G.ROOM_ATTACH (the top screen) in vanilla; the pack opens on the bottom screen.
    function D.pack_attach()
        if hand_on_bottom() then return D.anchor() end
        return G.ROOM_ATTACH
    end

    -- Menus (options, run info, deck, game over...) open on the bottom screen when they fit; bigger ones (the
    -- collection) stay on top. Only menus aligned to the room by default; ones with their own anchor are left alone.
    if G and G.FUNCS and G.FUNCS.overlay_menu then
        local orig_overlay = G.FUNCS.overlay_menu
        G.FUNCS.overlay_menu = function(args)
            local default_anchor = not (args and args.config and args.config.major)
            orig_overlay(args)
            if D.active and default_anchor and D.screen_of("Menus", "bottom") == "bottom" then
                D.safe("overlay", function()
                    local box = G.OVERLAY_MENU
                    if box and type(box) == "table" and D.fits_bottom(box) then
                        D.to_bottom(box, box.alignment.type, { x = box.alignment.offset.x, y = box.alignment.offset.y })
                    end
                end)
            end
        end
    end

    -- Tooltips: vanilla opens a card's tooltip below it only near the top of the room. A card near the top of the
    -- bottom screen (the consumables) would get its tooltip above it, on the other screen.
    if Card and Card.align_h_popup then
        local orig_popup = Card.align_h_popup
        function Card:align_h_popup(...)
            local conf = orig_popup(self, ...)
            if D.active and conf and conf.type == 'tm' and self.T and D.on_bottom(self.T.y + self.T.h) then
                local _, by = D.bottom_room()
                if self.T.y - by < G.CARD_H * 0.8 then
                    conf.type = 'bm'
                    conf.offset.y = self.children and self.children.focused_ui and 0.12 or 0.1
                end
            end
            return conf
        end
    end

    -- Vanilla crash guard: G.pack_cards is never reset to nil, so a Skip button that outlives its pack by a frame
    -- indexes a removed area (button_callbacks.lua:2133). Only acts in that state; otherwise vanilla decides.
    if G and G.FUNCS and G.FUNCS.can_skip_booster then
        local orig_skip = G.FUNCS.can_skip_booster
        G.FUNCS.can_skip_booster = function(e)
            if G.pack_cards and not G.pack_cards.cards then
                if e and e.config then
                    e.config.colour = G.C.UI.BACKGROUND_INACTIVE
                    e.config.button = nil
                end
                return
            end
            return orig_skip(e)
        end
    end
end
