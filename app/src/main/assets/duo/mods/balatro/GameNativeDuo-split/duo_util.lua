-- GameNativeDuo for Balatro: shared helpers (log, files, JSON). Appended to main.lua by lovely.toml, before the other
-- duo_*.lua files. Everything lives in the global GNDuo table; nothing here touches the game.
GNDuo = GNDuo or {}
do
    local D = GNDuo
    D.VERSION = "0.2.0"

    -- %AppData%\GameNativeDuo\ in the prefix: layout.json, ui.json and elements.json (the app's contract).
    local appdata = os.getenv("APPDATA")
    D.dir = appdata and (appdata .. "\\GameNativeDuo\\") or nil

    local logfile
    function D.log(fmt, ...)
        local ok, msg = pcall(string.format, fmt, ...)
        msg = "GNDuo: " .. (ok and msg or tostring(fmt))
        print(msg)
        if logfile == nil and D.dir then logfile = io.open(D.dir .. "balatro.log", "w") or false end
        if logfile then
            logfile:write(string.format("%8.2f ", love.timer and love.timer.getTime() or 0), msg, "\n")
            logfile:flush()
        end
    end

    -- Runs fn, logging (once per tag) instead of raising: nothing of ours may take the game down.
    local failed = {}
    function D.safe(tag, fn, ...)
        local ok, res = pcall(fn, ...)
        if not ok and not failed[tag] then
            failed[tag] = true
            D.log("%s failed: %s", tag, tostring(res))
        end
        return ok and res or nil
    end

    function D.read_file(path)
        local f = io.open(path, "rb")
        if not f then return nil end
        local s = f:read("*a")
        f:close()
        return s
    end

    -- Replaces the file in one step (readers never see half a file).
    function D.write_file(path, text)
        local tmp = path .. ".tmp"
        local f = io.open(tmp, "wb")
        if not f then return false end
        f:write(text)
        f:close()
        os.remove(path)
        return os.rename(tmp, path) and true or false
    end

    -- JSON: just what layout.json and ui.json need (objects, arrays, strings, numbers, booleans, null as nil).
    function D.json_decode(s)
        local pos = 1
        local function skip() pos = s:find("[^ \t\r\n]", pos) or #s + 1 end
        local escapes = { b = "\b", f = "\f", n = "\n", r = "\r", t = "\t" }
        local function str()
            local out, i = {}, pos + 1
            while true do
                local j = s:find('["\\]', i)
                if not j then error("unterminated string") end
                out[#out + 1] = s:sub(i, j - 1)
                if s:sub(j, j) == '"' then
                    pos = j + 1
                    return table.concat(out)
                end
                local e = s:sub(j + 1, j + 1)
                if e == "u" then
                    local code = tonumber(s:sub(j + 2, j + 5), 16) or 63
                    out[#out + 1] = code < 128 and string.char(code) or "?"
                    i = j + 6
                else
                    out[#out + 1] = escapes[e] or e
                    i = j + 2
                end
            end
        end
        local value
        local function list(close, item)
            pos = pos + 1
            skip()
            if s:sub(pos, pos) == close then
                pos = pos + 1
                return
            end
            while true do
                item()
                skip()
                local c = s:sub(pos, pos)
                pos = pos + 1
                if c == close then return end
                if c ~= "," then error("',' expected at " .. pos) end
            end
        end
        function value()
            skip()
            local c = s:sub(pos, pos)
            if c == "{" then
                local t = {}
                list("}", function()
                    skip()
                    if s:sub(pos, pos) ~= '"' then error("key expected at " .. pos) end
                    local k = str()
                    skip()
                    if s:sub(pos, pos) ~= ":" then error("':' expected at " .. pos) end
                    pos = pos + 1
                    t[k] = value()
                end)
                return t
            elseif c == "[" then
                local t = {}
                list("]", function() t[#t + 1] = value() end)
                return t
            elseif c == '"' then
                return str()
            elseif s:sub(pos, pos + 3) == "true" then
                pos = pos + 4
                return true
            elseif s:sub(pos, pos + 4) == "false" then
                pos = pos + 5
                return false
            elseif s:sub(pos, pos + 3) == "null" then
                pos = pos + 4
                return nil
            end
            local num = s:match("^-?%d+%.?%d*[eE]?[-+]?%d*", pos)
            if not num or num == "" then error("unexpected '" .. c .. "' at " .. pos) end
            pos = pos + #num
            return tonumber(num)
        end
        return value()
    end

    local function encode(v, out)
        local t = type(v)
        if t == "table" then
            if v[1] ~= nil or next(v) == nil and getmetatable(v) == D.ARRAY then
                out[#out + 1] = "["
                for i = 1, #v do
                    if i > 1 then out[#out + 1] = "," end
                    encode(v[i], out)
                end
                out[#out + 1] = "]"
            else
                out[#out + 1] = "{"
                local first = true
                for k, x in pairs(v) do
                    if not first then out[#out + 1] = "," end
                    first = false
                    encode(tostring(k), out)
                    out[#out + 1] = ":"
                    encode(x, out)
                end
                out[#out + 1] = "}"
            end
        elseif t == "string" then
            out[#out + 1] = '"' .. v:gsub('[%c"\\]', function(c)
                return c == '"' and '\\"' or c == "\\" and "\\\\" or string.format("\\u%04x", c:byte())
            end) .. '"'
        elseif t == "number" then
            out[#out + 1] = (v ~= v or v == math.huge or v == -math.huge) and "0" or
                (math.floor(v) == v and string.format("%d", v) or string.format("%.4f", v))
        elseif t == "boolean" then
            out[#out + 1] = tostring(v)
        else
            out[#out + 1] = "null"
        end
    end
    -- Marks an empty table that must encode as [] rather than {}.
    D.ARRAY = {}
    function D.json_encode(v)
        local out = {}
        encode(v, out)
        return table.concat(out)
    end
end
