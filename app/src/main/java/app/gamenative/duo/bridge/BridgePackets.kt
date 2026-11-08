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
