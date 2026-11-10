-- luajit duo/sdk/lua/tests/test_duo.lua (from the repo root): packing, parsing, JSON and state chunks; no socket needed.
package.path = "duo/sdk/lua/?.lua;" .. package.path
local duo = require("duo")
local n = 0
local function check(cond, what)
    n = n + 1
    if not cond then error("FAILED: " .. what, 2) end
end

-- TOUCH: action 1, pointer 2, x 12.5, y 1300 (built like the app does).
local ffi = require("ffi")
local f = ffi.new("float[2]", 12.5, 1300)
local touch = "GD\1\1" .. string.char(1, 2, 0, 0) .. ffi.string(f, 8)
local m = duo.parse(touch)
check(m and m.action == 1 and m.pointer == 2 and m.x == 12.5 and m.y == 1300, "touch parses")
check(duo.parse(touch:sub(1, 12)) == nil, "short touch is dropped")

local cmd = duo.parse("GD\1\5" .. duo.le32(7) .. '{"cmd":"x"}')
check(cmd.request == 7 and cmd.text == '{"cmd":"x"}', "command parses")
check(duo.parse("GD\1\3" .. duo.le32(305419896)).request == 305419896, "dump request u32")

local j = duo.json_decode('{"a":[1,2.5,"x"],"b":{"c":true},"d":null}')
check(j.a[2] == 2.5 and j.b.c == true and j.d == nil, "json decodes")
check(duo.json_encode({ 1, 2 }) == "[1,2]" and duo.json_encode(setmetatable({}, duo.ARRAY)) == "[]", "json encodes")

check(duo.matches("hk.charms", "hk.charms.0") and duo.matches("hk.map.", "hk.map.zone"), "subscription covers children")
check(not duo.matches("hk.charm", "hk.charms"), "subscription is not a plain prefix")

local list = {}
for i = 1, 400 do list[#list + 1] = { "k.item" .. i, string.rep("x", 40) } end
local chunks = duo.state_chunks(list, duo.MAX_STATE_BYTES)
local keys = 0
for _, c in ipairs(chunks) do
    check(#c <= duo.MAX_STATE_BYTES, "chunk under 8 KB")
    for _ in pairs(duo.json_decode(c)) do keys = keys + 1 end
end
check(#chunks > 1 and keys == 400, "chunks keep every key")

local l = duo.parse_layout('{"screen":{"w":1920,"h":2160},"top":{"x":0,"y":0,"w":1920,"h":1080},"bottom":{"x":340,"y":1080,"w":1240,"h":1080},"bridge":{"port":4000,"token":"00112233445566778899aabbccddeeff"}}')
check(l.port == 4000 and l.bottom.x == 340 and l.bottom_scale == 1, "layout parses")
check(l.token == "00112233445566778899aabbccddeeff", "layout carries the session token")
check(duo.parse_layout('{"screen":{"w":1,"h":1},"bridge":{"port":4000}}').token == "", "no token from an older app")
check(duo.hello_packet(3, "love/sdk1.1", l.token) == "GD\1\129\3\0\0\0love/sdk1.1\0" .. l.token, "hello ends with a NUL and the token")
check(duo.hello_packet(3, "love/sdk1.1", "") == "GD\1\129\3\0\0\0love/sdk1.1", "hello without a token is the old one")

print("duo.lua: " .. n .. " checks passed")
