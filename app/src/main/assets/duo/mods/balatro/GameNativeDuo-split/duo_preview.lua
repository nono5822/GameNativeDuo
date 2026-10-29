-- GameNativeDuo for Balatro: "Hand info" on the bottom screen, the hand you are selecting (name, level, chips x mult)
-- next to your cards, so you need not look up while you choose. Our own small box reading the same live values as the
-- HUD (G.GAME.current_round.current_hand); the HUD on the top screen is left exactly as it is.
-- Rule "Hand info": screen "bottom" (default) or "hidden"; x/y shift it.
do
    local D = GNDuo
    local box, box_game

    -- States where a hand is being chosen or scored; elsewhere (shop, blind select, packs) the box would sit under
    -- or over those panels with nothing to say.
    local function wanted_state()
        local S = G.STATES
        return G.STATE == S.SELECTING_HAND or G.STATE == S.HAND_PLAYED or G.STATE == S.DRAW_TO_HAND
    end

    local function definition()
        local ch = G.GAME.current_round.current_hand
        local scale = 0.4
        local font = G.LANGUAGES['en-us'].font
        return { n = G.UIT.ROOT, config = { align = "cm", colour = G.C.CLEAR }, nodes = {
            { n = G.UIT.R, config = { align = "cm", colour = darken(G.C.BLACK, 0.1), r = 0.1, emboss = 0.05, padding = 0.08, minw = 4.4 }, nodes = {
                { n = G.UIT.C, config = { align = "cm" }, nodes = {
                    { n = G.UIT.R, config = { align = "cm", minh = 0.7 }, nodes = {
                        { n = G.UIT.O, config = { object = DynaText({ string = { { ref_table = ch, ref_value = "handname_text" } },
                            colours = { G.C.UI.TEXT_LIGHT }, shadow = true, float = true, scale = scale * 1.3, maxw = 3.1 }) } },
                        { n = G.UIT.B, config = { w = 0.12, h = 0.1 } },
                        { n = G.UIT.T, config = { ref_table = ch, ref_value = "hand_level", scale = scale, colour = G.C.UI.TEXT_LIGHT, shadow = true } },
                    } },
                    { n = G.UIT.R, config = { align = "cm", padding = 0.08 }, nodes = {
                        { n = G.UIT.C, config = { align = "cr", minw = 1.8, minh = 0.8, r = 0.1, colour = G.C.UI_CHIPS, emboss = 0.05 }, nodes = {
                            { n = G.UIT.O, config = { object = DynaText({ string = { { ref_table = ch, ref_value = "chip_text" } },
                                colours = { G.C.UI.TEXT_LIGHT }, font = font, shadow = true, float = true, scale = scale * 2, maxw = 1.6 }) } },
                            { n = G.UIT.B, config = { w = 0.1, h = 0.1 } },
                        } },
                        { n = G.UIT.C, config = { align = "cm" }, nodes = {
                            { n = G.UIT.T, config = { text = "X", lang = G.LANGUAGES['en-us'], scale = scale * 1.8, colour = G.C.UI_MULT, shadow = true } },
                        } },
                        { n = G.UIT.C, config = { align = "cl", minw = 1.8, minh = 0.8, r = 0.1, colour = G.C.UI_MULT, emboss = 0.05 }, nodes = {
                            { n = G.UIT.B, config = { w = 0.1, h = 0.1 } },
                            { n = G.UIT.O, config = { object = DynaText({ string = { { ref_table = ch, ref_value = "mult_text" } },
                                colours = { G.C.UI.TEXT_LIGHT }, font = font, shadow = true, float = true, scale = scale * 2, maxw = 1.6 }) } },
                        } },
                    } },
                } },
            } },
        } }
    end

    local function offset()
        local r = D.rule("Hand info")
        local _, _, bw, bh = D.bottom_room()
        return { x = 0.35 + (tonumber(r.x) or 0) * bw, y = 0.35 + (tonumber(r.y) or 0) * bh }
    end

    local function remove()
        if box and not box.REMOVED then pcall(box.remove, box) end
        box, box_game = nil, nil
    end

    function D.preview_box() return box end

    function D.preview_rules_changed()
        if box and not box.REMOVED then D.to_bottom(box, 'tli', offset()) end
    end

    -- Every frame while split: create it for a run, show it while a hand is chosen, drop it otherwise.
    function D.preview_update()
        local want = D.active and D.hand_on_bottom() and D.screen_of("Hand info", "bottom") == "bottom"
            and G.STAGE == G.STAGES.RUN and G.GAME and G.GAME.current_round and G.GAME.current_round.current_hand
            and G.HUD and not G.HUD.REMOVED
        if not want then
            if box then remove() end
            return
        end
        if box and (box.REMOVED or box_game ~= G.GAME) then remove() end
        if not box then
            box = UIBox { definition = definition(), config = { align = 'tli', offset = offset(), major = D.anchor(), bond = 'Weak' } }
            box_game = G.GAME
        elseif box.role.major ~= D.anchor() then
            D.to_bottom(box, 'tli', offset())
        end
        local visible = wanted_state()
        if box.states.visible ~= visible then box.states.visible = visible end
    end
end
