-- duo.lua: the GameNative Duo SDK for LÖVE / LuaJIT games (MIT). One file, no dependencies besides luasocket (bundled
-- with LÖVE). Same API as GameNativeDuo.Sdk for C# (duo/docs/devkit-platform.md, "SDKs"); the wire format is
-- duo/docs/engine-adapter-contract.md.
--
--   local duo = require("duo")
--   duo.start({ engine = "love-mygame" })
--   duo.elements.register("Score", "Score", function() return score_x, score_y, score_w, score_h end)
--   duo.on_touch(function(action, pointer, x, y) ... end)
--   -- in love.update: duo.update(love.graphics.getDimensions())
--
-- Everything runs on the game thread inside duo.update (one non-blocking receive per packet, nothing when idle).
-- Low-level pieces (duo.packet, duo.parse, duo.json_*) are public for adapters that keep their own loop.
local duo = { VERSION = "1.0" }

local ok_socket, socket = pcall(require, "socket")
local ok_ffi, ffi = pcall(require, "ffi")

-- Message types and capabilities.
duo.TOUCH, duo.RULES_CHANGED, duo.DUMP_ELEMENTS, duo.SUBSCRIBE, duo.COMMAND = 0x01, 0x02, 0x03, 0x04, 0x05
duo.HELLO, duo.ELEMENTS_WRITTEN, duo.STATE, duo.COMMAND_RESULT = 0x81, 0x83, 0x84, 0x85
duo.ELEMENTS_CHANGED, duo.LOG, duo.HAPTIC = 0x86, 0x87, 0x88
duo.CAP_TOUCH, duo.CAP_UI_RULES, duo.CAP_ELEMENTS, duo.CAP_BG_BASIC = 1, 2, 4, 8
duo.CAP_BG_BLUR, duo.CAP_BG_GAME, duo.CAP_STATE, duo.CAP_COMMANDS, duo.CAP_ELEMENTS_CHANGED = 16, 32, 64, 128, 256
duo.DOWN, duo.MOVE, duo.UP, duo.CANCEL = 0, 1, 2, 3
duo.OK, duo.NOT_NOW, duo.UNKNOWN, duo.FAILED = 0, 1, 2, 3
duo.DEBUG, duo.INFO, duo.WARN, duo.ERROR = 0, 1, 2, 3
duo.MAX_STATE_BYTES = 8192

---------------------------------------------------------------------------------------------------------------------
-- JSON: objects, arrays, strings, numbers, booleans; null decodes to nil. duo.ARRAY marks an empty table as [].

duo.ARRAY = {}

function duo.json_decode(s)
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
        if v[1] ~= nil or next(v) == nil and getmetatable(v) == duo.ARRAY then
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
        out[#out + 1] = (v ~= v or v == math.huge or v == -math.huge) and "null" or
            (math.floor(v) == v and math.abs(v) < 2^53 and string.format("%d", v) or string.format("%.6g", v))
    elseif t == "boolean" then
        out[#out + 1] = tostring(v)
    else
        out[#out + 1] = "null"
    end
end

function duo.json_encode(v)
    local out = {}
    encode(v, out)
    return table.concat(out)
end

---------------------------------------------------------------------------------------------------------------------
-- Packets

local function le32(n)
    return string.char(n % 256, math.floor(n / 256) % 256, math.floor(n / 65536) % 256, math.floor(n / 16777216) % 256)
end
local function u32(s, at)
    local a, b, c, d = s:byte(at, at + 3)
    return a + b * 256 + c * 65536 + d * 16777216
end
local function f32(s, at)
    if ok_ffi then
        local f = ffi.new("float[1]")
        ffi.copy(f, s:sub(at, at + 3), 4)
        return tonumber(f[0])
    end
    -- Pure Lua (no ffi): IEEE 754 single, little-endian.
    local b1, b2, b3, b4 = s:byte(at, at + 3)
    local sign = b4 >= 128 and -1 or 1
    local exp = (b4 % 128) * 2 + math.floor(b3 / 128)
    local mant = ((b3 % 128) * 256 + b2) * 256 + b1
    if exp == 0 then return sign * mant * 2 ^ -149 end
    if exp == 255 then return mant == 0 and sign * math.huge or 0 / 0 end
    return sign * (1 + mant / 8388608) * 2 ^ (exp - 127)
end

duo.le32 = le32

--- A packet of type t with an optional body string.
function duo.packet(t, body) return "GD\1" .. string.char(t) .. (body or "") end

function duo.hello_packet(caps, engine) return duo.packet(duo.HELLO, le32(caps) .. engine) end

--- Parses an app -> mod packet: a table {type=, action=, pointer=, x=, y=, request=, text=} or nil.
function duo.parse(data)
    if #data < 4 or data:sub(1, 3) ~= "GD\1" then return nil end
    local t = data:byte(4)
    if t == duo.TOUCH then
        if #data < 16 then return nil end
        return { type = t, action = data:byte(5), pointer = data:byte(6), x = f32(data, 9), y = f32(data, 13) }
    elseif t == duo.RULES_CHANGED then
        return { type = t }
    elseif t == duo.DUMP_ELEMENTS then
        if #data < 8 then return nil end
        return { type = t, request = u32(data, 5) }
    elseif t == duo.SUBSCRIBE then
        return { type = t, text = data:sub(5) }
    elseif t == duo.COMMAND then
        if #data < 8 then return nil end
        return { type = t, request = u32(data, 5), text = data:sub(9) }
    end
    return nil
end

---------------------------------------------------------------------------------------------------------------------
-- Socket

local udp

--- Opens the mod's socket (any free loopback port), or returns nil when luasocket is missing.
function duo.open()
    if udp or not ok_socket then return udp end
    local s = socket.udp()
    if not s then return nil end
    s:settimeout(0)
    if not s:setsockname("127.0.0.1", 0) then
        s:close()
        return nil
    end
    udp = s
    return udp
end

--- Sends to the app's port (duo.port); silent when there is no app.
function duo.send(packet)
    if duo.port and duo.port > 0 and duo.open() then udp:sendto(packet, "127.0.0.1", duo.port) end
end

--- Calls fn(msg) for each packet from the app received since the last call (at most 64 per call).
function duo.poll(fn)
    if not udp then return end
    for _ = 1, 64 do
        local data, ip, port = udp:receivefrom()
        if not data then return end
        if ip == "127.0.0.1" and port == duo.port then
            local m = duo.parse(data)
            if m then fn(m) end
        end
    end
end

---------------------------------------------------------------------------------------------------------------------
-- Files

local function read_file(path)
    local f = io.open(path, "rb")
    if not f then return nil end
    local s = f:read("*a")
    f:close()
    return s
end

local function write_file(path, text)
    local tmp = path .. ".tmp"
    local f = io.open(tmp, "wb")
    if not f then return false end
    f:write(text)
    f:close()
    -- Replace in one step: between a remove and a rename, a reader would find no file at all. POSIX rename replaces;
    -- Windows' does not, so use MoveFileExA there (LÖVE has ffi), and remove + rename only as a last resort.
    if ok_ffi and ffi.os == "Windows" then
        local ok, moved = pcall(function()
            pcall(ffi.cdef, "int MoveFileExA(const char *from, const char *to, unsigned long flags);")
            return ffi.C.MoveFileExA(tmp, path, 1) ~= 0
        end)
        if ok and moved then return true end
    elseif os.rename(tmp, path) then
        return true
    end
    os.remove(path)
    return os.rename(tmp, path) and true or false
end

duo.read_file, duo.write_file = read_file, write_file

--- The GameNativeDuo folder under APPDATA (with a trailing separator), or nil when APPDATA is not set.
--- Windows and Wine: %AppData%\GameNativeDuo\. A native Linux or macOS game (testing against `duo sim`) has no
--- APPDATA: start it with APPDATA set to the folder that holds the sim's GameNativeDuo folder, and "/" is used.
function duo.default_dir(appdata)
    appdata = appdata or os.getenv("APPDATA")
    if not appdata or appdata == "" then return nil end
    local sep = (appdata:find("/", 1, true) and not appdata:find("\\", 1, true)) and "/" or "\\"
    return appdata .. sep .. "GameNativeDuo" .. sep
end

local function parse_rect(o)
    return o and { x = tonumber(o.x) or 0, y = tonumber(o.y) or 0, w = tonumber(o.w) or 0, h = tonumber(o.h) or 0 }
end

--- layout.json as {screen={w,h}, top=rect, bottom=rect, gap, bottom_scale, bottom_shift, port} or nil.
function duo.parse_layout(text)
    local ok, j = pcall(duo.json_decode, text)
    if not ok or type(j) ~= "table" or type(j.screen) ~= "table" then return nil end
    return {
        screen = { w = tonumber(j.screen.w) or 0, h = tonumber(j.screen.h) or 0 },
        top = parse_rect(j.top), bottom = parse_rect(j.bottom),
        gap = tonumber(j.gap) or 0, bottom_scale = tonumber(j.bottomScale) or 1, bottom_shift = tonumber(j.bottomShift) or 0,
        port = type(j.bridge) == "table" and tonumber(j.bridge.port) or 0,
    }
end

---------------------------------------------------------------------------------------------------------------------
-- State (companion cards): sent only for subscribed keys, at most 30 times a second, in datagrams of at most 8 KB.

local values, dirty, subscription, snapshot_due, published = {}, {}, {}, false, false
local last_state = -1

function duo.matches(s, key)
    if key == s then return true end
    if #key <= #s or key:sub(1, #s) ~= s then return false end
    return key:sub(#s + 1, #s + 1) == "." or s:sub(-1) == "."
end

--- True when a visible card reads key: compute costly values only then.
function duo.wants(key)
    for _, s in ipairs(subscription) do
        if duo.matches(s, key) then return true end
    end
    return false
end

function duo.publish(key, value)
    published = true
    if values[key] == value and type(value) ~= "table" then return end
    values[key] = value
    dirty[key] = true
end

--- {"k": v, ...} chunks of at most max bytes (a value bigger than one datagram is dropped).
function duo.state_chunks(pairs_list, max)
    local chunks, parts, size = {}, {}, 2
    for _, p in ipairs(pairs_list) do
        local entry = duo.json_encode(p[1]) .. ":" .. duo.json_encode(p[2])
        if #entry + 2 <= max then
            if #parts > 0 and size + #entry + 1 > max then
                chunks[#chunks + 1] = "{" .. table.concat(parts, ",") .. "}"
                parts, size = {}, 2
            end
            parts[#parts + 1] = entry
            size = size + #entry + 1
        end
    end
    if #parts > 0 or #chunks == 0 then chunks[#chunks + 1] = "{" .. table.concat(parts, ",") .. "}" end
    return chunks
end

local function flush_state(now)
    if #subscription == 0 then
        dirty = {}
        return
    end
    if not snapshot_due and next(dirty) == nil then return end
    if now - last_state < 0.033 then return end
    last_state = now
    local list = {}
    if snapshot_due then
        for k, v in pairs(values) do
            if duo.wants(k) then list[#list + 1] = { k, v } end
        end
    else
        for k in pairs(dirty) do
            if duo.wants(k) then list[#list + 1] = { k, values[k] } end
        end
    end
    local full = snapshot_due
    dirty, snapshot_due = {}, false
    for i, chunk in ipairs(duo.state_chunks(list, duo.MAX_STATE_BYTES)) do
        duo.send(duo.packet(duo.STATE, string.char((full and i == 1) and 1 or 0, 0, 0, 0) .. chunk))
    end
end

---------------------------------------------------------------------------------------------------------------------
-- The facade

local config = { engine = "love", dir = nil, log = nil, extra_caps = 0, backgrounds = { "game" } }
local touch_handlers, commands = {}, {}
local layout_text, ui_text
local next_hello, next_files = 0, 0
local features_written = false
local window_w, window_h = 0, 0

duo.layout = nil   -- see duo.parse_layout
duo.rules = {}     -- element id -> rule {screen, scale, x, y, options}
duo.background = { mode = "game" }
duo.rules_version = 0
duo.port = 0
duo.on_rules_changed = nil   -- function() after ui.json was read again
duo.on_layout_changed = nil  -- function(layout)

local function clock()
    if love and love.timer then return love.timer.getTime() end
    return os.clock()
end

local function local_log(fmt, ...)
    if config.log then pcall(config.log, string.format(fmt, ...)) end
end

--- options: engine (short id), dir (defaults to %AppData%\GameNativeDuo\), log (function(text)), extra_caps,
--- backgrounds (modes the mod draws, for features.json).
function duo.start(options)
    for k, v in pairs(options or {}) do config[k] = v end
    config.dir = config.dir or duo.default_dir()
    duo.open()
end

function duo.on_touch(fn) touch_handlers[#touch_handlers + 1] = fn end

--- fn(args) returns duo.OK / duo.NOT_NOW / duo.FAILED and a message. Run it through the game's own UI paths.
function duo.on_command(name, fn) commands[name] = fn end

function duo.rule(id) return duo.rules[id] end

function duo.is_split()
    local l = duo.layout
    return l ~= nil and math.abs(window_w - l.screen.w) <= 2 and math.abs(window_h - l.screen.h) <= 2
end

function duo.log(level, text)
    if text == nil then level, text = duo.INFO, level end
    duo.send(duo.packet(duo.LOG, string.char(level) .. tostring(text):sub(1, 500)))
    local_log("%s", tostring(text))
end

function duo.haptic(ms, strength)
    ms = math.max(0, math.min(65535, math.floor(ms or 20)))
    duo.send(duo.packet(duo.HAPTIC, string.char(ms % 256, math.floor(ms / 256), math.max(1, math.min(255, strength or 128)))))
end

duo.elements = { list = {} }

--- rect_fn() returns x, y, w, h in window pixels (rules applied), or nil while the element is not on screen.
--- opts: features {screens=, move=, scale=}, default ("bottom"), when ("During a run"), options (plugin settings).
function duo.elements.register(id, name, rect_fn, opts)
    opts = opts or {}
    for i, e in ipairs(duo.elements.list) do
        if e.id == id then table.remove(duo.elements.list, i) break end
    end
    local list = duo.elements.list
    list[#list + 1] = { id = id, name = name or id, rect = rect_fn, features = opts.features,
        default = opts.default or "bottom", when = opts.when, options = opts.options }
    features_written = false
    duo.elements.changed()
end

function duo.elements.changed() duo.send(duo.packet(duo.ELEMENTS_CHANGED)) end

--- The screen the user chose for id, or its default.
function duo.elements.screen_of(id)
    local r = duo.rules[id]
    if r and (r.screen == "top" or r.screen == "bottom" or r.screen == "hidden") then return r.screen end
    for _, e in ipairs(duo.elements.list) do
        if e.id == id then return e.default end
    end
    return "bottom"
end

local function caps()
    local c = duo.CAP_UI_RULES + duo.CAP_ELEMENTS + duo.CAP_ELEMENTS_CHANGED + (config.extra_caps or 0)
    if #touch_handlers > 0 then c = c + duo.CAP_TOUCH end
    if published then c = c + duo.CAP_STATE end
    if next(commands) then c = c + duo.CAP_COMMANDS end
    return c
end

local function element_doc(e, with_rect)
    local ok, x, y, w, h = pcall(e.rect or function() end)
    local present = ok and x ~= nil
    local d = { id = e.id, name = e.name, parent = -1, screen = duo.elements.screen_of(e.id), graphic = true,
        features = e.features, options = e.options }
    if with_rect then
        d.x, d.y, d.w, d.h = present and math.floor(x + 0.5) or 0, present and math.floor(y + 0.5) or 0,
            present and math.floor(w + 0.5) or 0, present and math.floor(h + 0.5) or 0
        if not present then d.present = false end
    end
    if e.when then d.when = e.when end
    return d
end

local function write_elements(request)
    if config.dir then
        local list = setmetatable({}, duo.ARRAY)
        for _, e in ipairs(duo.elements.list) do list[#list + 1] = element_doc(e, true) end
        local l = duo.layout
        local doc = { engine = config.engine, screen = { w = l and l.screen.w or window_w, h = l and l.screen.h or window_h }, elements = list }
        if not write_file(config.dir .. "elements.json", duo.json_encode(doc)) then local_log("elements.json not written") end
    end
    duo.send(duo.packet(duo.ELEMENTS_WRITTEN, le32(request)))
end

local function write_features()
    features_written = true
    if not config.dir then return end
    local list = setmetatable({}, duo.ARRAY)
    for _, e in ipairs(duo.elements.list) do list[#list + 1] = element_doc(e, false) end
    local doc = { format = "gamenative-duo/features", version = 1, engine = config.engine, touch = #touch_handlers > 0,
        background = config.backgrounds, elements = list, dynamic = false }
    write_file(config.dir .. "features.json", duo.json_encode(doc))
end

local function read_ui(force)
    if not config.dir then return end
    local text = read_file(config.dir .. "ui.json")
    if text == ui_text and not force then return end
    ui_text = text
    local data = {}
    if text then
        local ok, res = pcall(duo.json_decode, text)
        if ok and type(res) == "table" then data = res else local_log("ui.json unreadable: %s", tostring(res)) end
    end
    duo.rules = type(data.elements) == "table" and data.elements or {}
    duo.background = type(data.background) == "table" and data.background or { mode = "game" }
    duo.rules_version = duo.rules_version + 1
    if duo.on_rules_changed then pcall(duo.on_rules_changed) end
end

local function read_layout()
    if not config.dir then return end
    local text = read_file(config.dir .. "layout.json")
    if text ~= layout_text then
        layout_text = text
        duo.layout = text and duo.parse_layout(text) or nil
        if duo.on_layout_changed then pcall(duo.on_layout_changed, duo.layout) end
    end
    local port = duo.layout and duo.layout.port or 0
    if port == 0 then
        local b = read_file(config.dir .. "bridge.json")
        local ok, j = pcall(duo.json_decode, b or "")
        port = ok and type(j) == "table" and tonumber(j.port) or 0
    end
    if port ~= duo.port then
        duo.port = port
        next_hello = 0
    end
end

local function run_command(m)
    local ok, j = pcall(duo.json_decode, m.text)
    local name = ok and type(j) == "table" and j.cmd or ""
    local fn = commands[name]
    local status, message
    if not fn then
        status, message = duo.UNKNOWN, "unknown command " .. tostring(name)
    else
        local ran, s, msg = pcall(fn, type(j.args) == "table" and j.args or {})
        if ran then status, message = s or duo.OK, msg or "" else status, message = duo.FAILED, tostring(s) end
    end
    duo.send(duo.packet(duo.COMMAND_RESULT, le32(m.request) .. string.char(status) .. tostring(message):sub(1, 500)))
end

local function handle(m)
    if m.type == duo.TOUCH then
        for _, fn in ipairs(touch_handlers) do pcall(fn, m.action, m.pointer, m.x, m.y) end
    elseif m.type == duo.RULES_CHANGED then
        read_ui(true)
    elseif m.type == duo.DUMP_ELEMENTS then
        write_elements(m.request)
    elseif m.type == duo.SUBSCRIBE then
        local ok, j = pcall(duo.json_decode, m.text)
        subscription = ok and type(j) == "table" and type(j.keys) == "table" and j.keys or {}
        snapshot_due = #subscription > 0
    elseif m.type == duo.COMMAND then
        run_command(m)
    end
end

--- Call once per frame on the game thread with the window's size.
function duo.update(w, h)
    window_w, window_h = w or window_w, h or window_h
    local now = clock()
    if now >= next_files then
        next_files = now + 0.5
        read_layout()
        read_ui(false)
    end
    if now >= next_hello and duo.port > 0 then
        next_hello = now + 2
        duo.send(duo.hello_packet(caps(), config.engine .. "/sdk" .. duo.VERSION))
        if not features_written then write_features() end
    end
    duo.poll(handle)
    flush_state(now)
end

return duo
