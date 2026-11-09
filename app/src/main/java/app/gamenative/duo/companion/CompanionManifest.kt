package app.gamenative.duo.companion

import org.json.JSONArray
import org.json.JSONObject

/**
 * A companion's `companion.json` (format `gamenative-duo/companion`, see duo/docs/companions.md): pages of cards.
 * Parsing never fails on content: unknown card types become cards that say they need a newer app, bad values fall
 * back to defaults, and every such thing is listed in [problems] (shown on the dev companion's screen).
 */
data class CompanionManifest(
    val id: String,
    val name: String,
    val author: String = "",
    val packageVersion: String = "",
    /** Store keys (`steam-413150`), or `*` for every game. */
    val games: List<String> = emptyList(),
    /** The bridge api the live cards need (`hk`, minimum version), or null. */
    val api: Pair<String, Int>? = null,
    /** Game builds the author tested; live sources read nothing on any other build. Empty: no gate. */
    val builds: List<BuildGate> = emptyList(),
    /** `static.<name>` data files in the package. */
    val data: Map<String, String> = emptyMap(),
    val pages: List<CompanionPage> = emptyList(),
    val problems: List<String> = emptyList(),
) {
    /** True when this companion is meant for [gameKey] (or for every game). */
    fun forGame(gameKey: String): Boolean = games.any { it == "*" || it.equals(gameKey, ignoreCase = true) }

    val general: Boolean get() = "*" in games

    companion object {
        const val FORMAT = "gamenative-duo/companion"
        const val VERSION = 1

        fun parse(json: JSONObject, fallbackId: String): CompanionManifest {
            val problems = mutableListOf<String>()
            if (json.optString("format") != FORMAT) problems += "format should be \"$FORMAT\""
            if (json.optInt("version", VERSION) > VERSION) problems += "made for a newer GameNative Duo (version ${json.optInt("version")})"
            val id = json.optString("id").takeIf { ID.matches(it) } ?: fallbackId.also {
                if (json.has("id")) problems += "id must be lower-case letters, digits and -"
            }
            val requires = json.optJSONObject("requires")
            val api = requires?.optJSONObject("api")?.let { a ->
                a.optString("name").takeIf { it.isNotBlank() }?.let { it to a.optInt("min", 1) }
            }
            val builds = requires?.optJSONArray("builds").objects().mapNotNull { b ->
                val source = b.optString("source")
                val values = b.optJSONArray("in").strings()
                if (source.isBlank() || values.isEmpty()) {
                    problems += "requires.builds entries need \"source\" and \"in\""
                    null
                } else {
                    BuildGate(source, values)
                }
            }
            val data = json.optJSONObject("data")?.let { d ->
                d.keys().asSequence().mapNotNull { k ->
                    val path = d.optString(k)
                    if (CompanionFiles.safePath(path) == null) {
                        problems += "data.$k: \"$path\" is not a file inside the package"
                        null
                    } else {
                        k to path
                    }
                }.toMap()
            }.orEmpty()
            // Pages and cards have their own ids: a "notes" card on a "notes" page keeps its id (user state is keyed by it).
            val pageIds = mutableSetOf<String>()
            val cardIds = mutableSetOf<String>()
            val pages = json.optJSONArray("pages").objects().mapIndexed { i, p -> parsePage(p, i, pageIds, cardIds, problems) }
            if (pages.isEmpty()) problems += "no pages"
            return CompanionManifest(
                id = id,
                name = json.optString("name").ifBlank { id },
                author = json.optString("author"),
                packageVersion = json.optString("packageVersion"),
                games = json.optJSONArray("games").strings(),
                api = api,
                builds = builds,
                data = data,
                pages = pages,
                problems = problems,
            )
        }

        private fun parsePage(
            p: JSONObject,
            index: Int,
            pageIds: MutableSet<String>,
            cardIds: MutableSet<String>,
            problems: MutableList<String>,
        ): CompanionPage {
            val id = uniqueId(p.optString("id"), "page${index + 1}", pageIds)
            val cards = p.optJSONArray("cards").objects().mapIndexed { i, c -> parseCard(c, "$id.card${i + 1}", cardIds, problems) }
            return CompanionPage(
                id = id,
                title = p.optString("title").ifBlank { id },
                columns = p.optInt("columns", 1).coerceIn(1, 2),
                cards = cards,
                showWhen = p.optJSONObject("when")?.let { Condition.parse(it, problems) },
                focusWhen = p.optJSONObject("focusWhen")?.let { Condition.parse(it, problems) },
            )
        }

        private fun parseCard(
            c: JSONObject,
            fallbackId: String,
            usedIds: MutableSet<String>,
            problems: MutableList<String>,
        ): CompanionCard {
            val id = uniqueId(c.optString("id"), fallbackId, usedIds)
            val type = CardType.fromId(c.optString("type"))
            if (type == CardType.UNSUPPORTED) problems += "card $id: unknown type \"${c.optString("type")}\""
            val props = mutableMapOf<String, Any?>()
            c.keys().forEach { k -> if (k !in CARD_FIELDS) props[k] = toKotlin(c.opt(k)) }
            return CompanionCard(
                id = id,
                type = type,
                typeName = c.optString("type"),
                title = c.optString("title"),
                span = c.optInt("span", 1).coerceIn(1, 2),
                showWhen = c.optJSONObject("when")?.let { Condition.parse(it, problems) },
                props = props,
            )
        }

        private fun uniqueId(wanted: String, fallback: String, used: MutableSet<String>): String {
            var id = wanted.takeIf { ID.matches(it) } ?: fallback
            var n = 2
            val base = id
            while (!used.add(id)) id = "$base-${n++}"
            return id
        }

        private val ID = Regex("[a-z0-9][a-z0-9.-]{0,63}")
        private val CARD_FIELDS = setOf("id", "type", "title", "span", "when")
    }
}

data class BuildGate(val source: String, val accepted: List<String>)

data class CompanionPage(
    val id: String,
    val title: String,
    val columns: Int = 1,
    val cards: List<CompanionCard> = emptyList(),
    val showWhen: Condition? = null,
    /** The page comes to the front when this turns true (unless the user is using the companion). */
    val focusWhen: Condition? = null,
)

data class CompanionCard(
    /** Unique in the companion; user state is stored under it. */
    val id: String,
    val type: CardType,
    /** The type as written (for "needs a newer app" on unknown types). */
    val typeName: String = type.id,
    val title: String = "",
    val span: Int = 1,
    val showWhen: Condition? = null,
    /** The card's own fields, as plain Kotlin values (String, Long, Double, Boolean, List, Map, null). */
    val props: Map<String, Any?> = emptyMap(),
) {
    fun string(name: String): String? = props[name]?.let {
        if (it is String) {
            it
        } else if (it is Number ||
            it is Boolean
        ) {
            it.toString()
        } else {
            null
        }
    }

    fun bool(name: String): Boolean = props[name] == true

    fun number(name: String): Double? = (props[name] as? Number)?.toDouble()

    fun list(name: String): List<Any?> = props[name] as? List<Any?> ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    fun map(name: String): Map<String, Any?> = props[name] as? Map<String, Any?> ?: emptyMap()
}

enum class CardType(val id: String) {
    TEXT("text"),
    CHECKLIST("checklist"),
    NOTES("notes"),
    TIMER("timer"),
    IMAGE("image"),
    VALUE("value"),
    PROGRESS("progress"),
    LIST("list"),
    MAP("map"),
    UNSUPPORTED(""),
    ;

    companion object {
        fun fromId(id: String): CardType = entries.firstOrNull { it.id == id && it != UNSUPPORTED } ?: UNSUPPORTED
    }
}

/** A `when` condition: JSON only, nothing to parse or run (see [CompanionData.test]). */
sealed class Condition {
    data class Compare(val key: String, val op: Op, val value: Any?) : Condition()

    data class All(val of: List<Condition>) : Condition()

    data class AnyOf(val of: List<Condition>) : Condition()

    data class Not(val of: Condition) : Condition()

    enum class Op(val id: String) { EQ("eq"), NE("ne"), GT("gt"), LT("lt"), IN("in"), EXISTS("exists") }

    /** Every key the condition reads (to re-test it only when one of them changes). */
    fun keys(): Set<String> = when (this) {
        is Compare -> setOf(key)
        is All -> of.flatMapTo(mutableSetOf()) { it.keys() }
        is AnyOf -> of.flatMapTo(mutableSetOf()) { it.keys() }
        is Not -> of.keys()
    }

    companion object {
        /** A condition that cannot be understood is false (the card stays hidden) and listed in [problems]. */
        fun parse(json: JSONObject, problems: MutableList<String>): Condition {
            json.optJSONArray("all")?.let { a -> return All(a.objects().map { parse(it, problems) }) }
            json.optJSONArray("any")?.let { a -> return AnyOf(a.objects().map { parse(it, problems) }) }
            json.optJSONObject("not")?.let { return Not(parse(it, problems)) }
            val key = json.optString("key")
            val op = Op.entries.firstOrNull { json.has(it.id) }
            if (key.isBlank() || op == null) {
                problems += "condition $json needs \"key\" and one of eq, ne, gt, lt, in, exists"
                return AnyOf(emptyList())
            }
            return Compare(key, op, toKotlin(json.opt(op.id)))
        }
    }
}

/** JSON (org.json) to plain Kotlin values; Int becomes Long so numbers compare the same way whatever their source. */
internal fun toKotlin(v: Any?): Any? = when (v) {
    null, JSONObject.NULL -> null
    is JSONObject -> v.keys().asSequence().associateWith { toKotlin(v.opt(it)) }
    is JSONArray -> (0 until v.length()).map { toKotlin(v.opt(it)) }
    is Int -> v.toLong()
    is Float -> v.toDouble()
    else -> v
}

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it)?.takeIf { v -> v != JSONObject.NULL }?.toString() }
