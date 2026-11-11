package app.gamenative.duo.bridge

import org.json.JSONArray
import org.json.JSONObject

/** Packing and parsing of the bridge's JSON-carrying packets (pure, unit-tested). See [DuoBridge] for the wire format. */
object BridgePackets {
    /** One datagram: a STATE delta is at most 8 KB of JSON (larger states are split by the mod), plus the header. */
    const val MAX_DATAGRAM = 8192 + 8

    /** `SUBSCRIBE {"keys": [...]}`, or null when it would not fit one datagram. */
    fun subscribe(prefixes: List<String>): ByteArray? {
        val json = JSONObject().put("keys", JSONArray(prefixes)).toString().toByteArray(Charsets.UTF_8)
        if (json.size + 4 > MAX_DATAGRAM) return null
        return ByteArray(4 + json.size).also {
            header(it, DuoBridge.SUBSCRIBE)
            System.arraycopy(json, 0, it, 4, json.size)
        }
    }

    /** `STATE flags u8, 3 bytes padding, json object` -> flags and plain values (null deletes), or null when broken. */
    fun state(buffer: ByteArray, length: Int): Pair<Int, Map<String, Any?>>? {
        if (length < 9) return null
        return try {
            val o = JSONObject(String(buffer, 8, length - 8, Charsets.UTF_8))
            val changes = LinkedHashMap<String, Any?>()
            o.keys().forEach { k -> if (k.isNotEmpty() && k.length <= MAX_KEY) changes[k] = plain(o.opt(k)) }
            (buffer[4].toInt() and 0xFF) to changes
        } catch (e: Exception) {
            null
        }
    }

    /** The SDK version this app knows best; mods built for an older one may miss features. */
    const val SDK_VERSION = "1.1"

    /** HELLO's engine string is `engine` or `engine/sdkX.Y`: the engine part. */
    fun engineOf(hello: String): String = helloName(hello).substringBefore("/sdk")

    /** The SDK part of HELLO's engine string, or "" for a mod without the SDK. */
    fun sdkOf(hello: String): String = helloName(hello).let { if ("/sdk" in it) it.substringAfter("/sdk") else "" }

    /** HELLO's text is the engine string, then (SDK 1.1+) a NUL and the session token: the engine string. */
    fun helloName(text: String): String = text.substringBefore(HELLO_SEPARATOR)

    /** The session token a HELLO carries after the NUL, or null for a mod built before tokens (SDK 1.0). */
    fun helloToken(text: String): String? = if (HELLO_SEPARATOR in text) text.substringAfter(HELLO_SEPARATOR) else null

    /** A new session token: 128 random bits as 32 hex characters (ASCII, so every SDK can copy it as is). */
    fun newToken(random: java.security.SecureRandom = java.security.SecureRandom()): String =
        ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    enum class HelloVerdict {
        /** Carries this session's token: the mod, whatever port it speaks from (a restarted game has a new one). */
        ACCEPT,

        /** No token (a mod built on SDK 1.0), and nothing speaks against it. */
        ACCEPT_LEGACY,

        REFUSE,
    }

    /**
     * Whether a HELLO may become (or stay) the session's mod. Loopback is shared by every app on the device, so only
     * the token from `layout.json` (in the app's private prefix) proves a sender is the game. A HELLO without one is
     * still taken from mods built before tokens, but never once a mod with the token has spoken ([tokenSeen]) and
     * never from another port while the current mod is alive.
     */
    fun judgeHello(expected: String, given: String?, samePort: Boolean, modAlive: Boolean, tokenSeen: Boolean): HelloVerdict = when {
        given != null -> if (sameText(given, expected)) HelloVerdict.ACCEPT else HelloVerdict.REFUSE
        tokenSeen -> HelloVerdict.REFUSE
        modAlive && !samePort -> HelloVerdict.REFUSE
        else -> HelloVerdict.ACCEPT_LEGACY
    }

    /** Equality that takes the same time wherever the texts differ. */
    private fun sameText(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private const val HELLO_SEPARATOR = '\u0000'

    /** True when [sdk] (a mod's) is older than [SDK_VERSION]; false when unknown. */
    fun sdkIsOlder(sdk: String): Boolean {
        val mine = SDK_VERSION.split('.').map { it.toIntOrNull() ?: 0 }
        val theirs = sdk.split('.').map { it.toIntOrNull() ?: return false }
        if (sdk.isEmpty()) return false
        for (i in 0 until maxOf(mine.size, theirs.size)) {
            val a = theirs.getOrElse(i) { 0 }
            val b = mine.getOrElse(i) { 0 }
            if (a != b) return a < b
        }
        return false
    }

    /** One packet as a line for the Bridge tab: its type and what it carries. */
    fun describe(p: ByteArray, n: Int): String {
        if (n < 4) return "?"
        val b = java.nio.ByteBuffer.wrap(p, 0, n).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun text(from: Int) = if (n > from) String(p, from, n - from, Charsets.UTF_8).take(200) else ""
        return when (p[3]) {
            DuoBridge.TOUCH -> if (n >= 16) {
                val action = listOf("down", "move", "up", "cancel").getOrElse(p[4].toInt()) { "?" }
                "TOUCH $action #${p[5]} ${b.getFloat(8).toInt()},${b.getFloat(12).toInt()}"
            } else {
                "TOUCH"
            }
            DuoBridge.RULES_CHANGED -> "RULES_CHANGED"
            DuoBridge.DUMP_ELEMENTS -> "DUMP_ELEMENTS ${if (n >= 8) b.getInt(4) else ""}"
            DuoBridge.SUBSCRIBE -> "SUBSCRIBE ${text(4)}"
            DuoBridge.COMMAND -> "COMMAND #${if (n >= 8) b.getInt(4) else 0} ${text(8)}"
            // The token after the NUL stays out of the dev tools and logs.
            DuoBridge.HELLO -> "HELLO caps 0x${if (n >= 8) Integer.toHexString(b.getInt(4)) else "?"} ${helloName(text(8))}"
            DuoBridge.ELEMENTS_WRITTEN -> "ELEMENTS_WRITTEN ${if (n >= 8) b.getInt(4) else ""}"
            DuoBridge.STATE -> "STATE ${if (n > 4 && p[4].toInt() and 1 != 0) "full " else ""}${text(8)}"
            DuoBridge.COMMAND_RESULT -> "COMMAND_RESULT #${if (n >= 8) b.getInt(4) else 0} ${if (n >= 9) p[8] else ""} ${text(9)}"
            DuoBridge.ELEMENTS_CHANGED -> "ELEMENTS_CHANGED"
            DuoBridge.LOG -> "LOG ${text(5)}"
            DuoBridge.HAPTIC -> "HAPTIC ${if (n >= 7) b.getShort(4) else 0} ms"
            else -> "0x" + Integer.toHexString(p[3].toInt() and 0xFF)
        }
    }

    fun levelName(level: Int) = when (level) {
        DuoBridge.LOG_DEBUG -> "D"
        DuoBridge.LOG_WARN -> "W"
        DuoBridge.LOG_ERROR -> "E"
        else -> "I"
    }

    /** JSON to the plain values `CompanionData` holds: Long/Double/String/Boolean/List/Map, null for JSON null. */
    fun plain(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> LinkedHashMap<String, Any?>().also { m -> v.keys().forEach { m[it] = plain(v.opt(it)) } }
        is JSONArray -> (0 until v.length()).map { plain(v.opt(it)) }
        is Int -> v.toLong()
        is Float -> v.toDouble()
        is java.math.BigDecimal -> v.toDouble()
        is java.math.BigInteger -> v.toLong()
        else -> v
    }

    private const val MAX_KEY = 200

    private fun header(p: ByteArray, type: Byte) {
        p[0] = 'G'.code.toByte()
        p[1] = 'D'.code.toByte()
        p[2] = DuoBridge.VERSION
        p[3] = type
    }
}
