package app.gamenative.duo.community

import org.json.JSONArray
import org.json.JSONObject

/**
 * The community package index (repo `nono5822/GameNativeDuo-community`, published by its CI on GitHub Pages). Format
 * and trust model: `duo/docs/community-packages.md`. Every index file is ed25519-signed with [PUBLIC_KEY]; every
 * package file is fetched by its sha256.
 */
object CommunityIndex {
    const val BASE_URL = "https://nono5822.github.io/GameNativeDuo-community/v1"

    /** The index signing key (the community repo's keys/index-public-key.txt). Changing it needs an app update. */
    const val PUBLIC_KEY = "VDgt6QeCefGECccRBbtFePnNoPLAmUhPlN0jyswkpoo="

    /** Kinds this app version can apply. Others (code mods) are listed but not installable yet. */
    val APPLICABLE_KINDS = setOf(CommunityKind.UI_LAYOUT, CommunityKind.DUO_SETTINGS)

    /** "STEAM_433340" -> "steam-433340". */
    fun gameKey(containerId: String): String = containerId.lowercase().replace('_', '-')

    fun gameUrl(key: String) = "$BASE_URL/games/$key.json"
}

enum class CommunityKind(val id: String) {
    UI_LAYOUT("ui-layout"),
    DUO_SETTINGS("duo-settings"),
    DEVICE_PROFILE("device-profile"),
    MOD("mod"),
    UNKNOWN(""),
    ;

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id && it != UNKNOWN } ?: UNKNOWN
    }
}

data class CommunityFile(val path: String, val sha256: String, val url: String, val size: Long)

data class CommunityPackage(
    val id: String,
    val kind: CommunityKind,
    val version: String,
    val title: String,
    val description: String,
    val authors: List<String>,
    val license: String,
    val adapter: String?,
    val testedOn: List<String>,
    val gameBuilds: List<String>,
    val tags: List<String>,
    val files: List<CommunityFile>,
    val screenshots: List<String>,
    /** ISO 8601 date of the package's last change in the repo. */
    val updated: String,
) {
    fun file(path: String): CommunityFile? = files.firstOrNull { it.path == path }

    companion object {
        /** Null for an entry this app cannot read (a newer index format, a broken entry): it is skipped, not fatal. */
        fun fromJson(o: JSONObject): CommunityPackage? = try {
            CommunityPackage(
                id = o.getString("id"),
                kind = CommunityKind.fromId(o.optString("kind")),
                version = o.optString("packageVersion"),
                title = o.getString("title"),
                description = o.optString("description"),
                authors = o.optJSONArray("authors").strings(),
                license = o.optString("license"),
                adapter = o.optJSONObject("requires")?.optString("adapter")?.ifEmpty { null },
                testedOn = o.optJSONArray("testedOn").strings(),
                gameBuilds = o.optJSONObject("game")?.optJSONArray("builds").strings(),
                tags = o.optJSONArray("tags").strings(),
                files = o.getJSONArray("files").let { a ->
                    (0 until a.length()).map { i ->
                        val f = a.getJSONObject(i)
                        CommunityFile(f.getString("path"), f.getString("sha256").lowercase(), f.getString("url"), f.optLong("size", -1))
                    }
                },
                screenshots = o.optJSONArray("screenshots").strings(),
                updated = o.optString("updated"),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** One game's packages, as published in `games/<key>.json`. */
class CommunityGame(val key: String, val title: String, val packages: List<CommunityPackage>) {
    companion object {
        fun fromJson(json: JSONObject): CommunityGame {
            val array = json.optJSONArray("packages") ?: JSONArray()
            val packages = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(CommunityPackage::fromJson) }
            return CommunityGame(json.optString("key"), json.optJSONObject("game")?.optString("title").orEmpty(), packages)
        }
    }
}

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> optString(i).ifEmpty { null } }
}
