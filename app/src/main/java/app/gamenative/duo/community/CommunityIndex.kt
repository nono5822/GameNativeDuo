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

    /** Kinds this app version can apply. */
    val APPLICABLE_KINDS = setOf(CommunityKind.UI_LAYOUT, CommunityKind.DUO_SETTINGS, CommunityKind.MOD)

    /** APK asset folders (under assets/duo/mods/shared/) a community mod may reuse instead of shipping its own copy. */
    val SHARED_ASSETS = setOf("bepinex", "unity-split", "BepInEx-LICENSE.txt", "BepInEx-NOTICE.txt")

    /**
     * Community packages published by GameNative Duo itself, shown with the official badge. Pinned here (not read from
     * the index) because every field of a package entry comes from its author's manifest; adding one needs an app update.
     */
    val OFFICIAL_PACKAGES = setOf(
        "gamenative-duo.crosshair-on-top",
        "gamenative-duo.recommended-settings",
        "gamenative-duo.ayn-thor",
        "gamenative-duo.dead-cells-minimap-below",
        "gamenative-duo.hollow-knight-dual-screen",
    )

    const val REPO_URL = "https://github.com/nono5822/GameNativeDuo-community"

    /** "STEAM_433340" -> "steam-433340". */
    fun gameKey(containerId: String): String = containerId.lowercase().replace('_', '-')

    fun gameUrl(key: String) = "$BASE_URL/games/$key.json"

    const val CATALOG_URL = "$BASE_URL/catalog.json"
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

/**
 * Compatibility reports for one device profile (`DeviceProfile.id`), counted by the community repo's bot from the
 * report form: one report per GitHub account and package version, the newest counts.
 */
data class CompatReports(val works: Int, val issues: Int, val broken: Int) {
    val total get() = works + issues + broken

    /** What most reports say; ties lean to the worse outcome, so a 1-1 split never reads "works". */
    val verdict: CompatVerdict get() = when {
        total == 0 -> CompatVerdict.UNKNOWN
        works > issues + broken -> CompatVerdict.WORKS
        broken > works + issues -> CompatVerdict.BROKEN
        else -> CompatVerdict.ISSUES
    }

    companion object {
        fun fromJson(o: JSONObject?): CompatReports? = o?.let {
            CompatReports(it.optInt("works").coerceAtLeast(0), it.optInt("issues").coerceAtLeast(0), it.optInt("broken").coerceAtLeast(0))
                .takeIf { r -> r.total > 0 }
        }
    }
}

enum class CompatVerdict { WORKS, ISSUES, BROKEN, UNKNOWN }

/**
 * 👍/👎 votes and comments on the package's GitHub discussion (folded into the index nightly), plus per-device
 * compatibility reports. Rules (enforced by the community repo's bot, see `duo/docs/community-ratings.md`): one vote per
 * GitHub account (an account with both 👍 and 👎 counts as neither), the package's own authors are not counted.
 * [verified] is true when the index says the bot applied those rules (`"rules": 2`); older indexes counted raw reactions.
 */
data class CommunityRating(
    val up: Int,
    val down: Int,
    val comments: Int,
    val url: String,
    val verified: Boolean = false,
    /** Device profile id -> reports. */
    val compat: Map<String, CompatReports> = emptyMap(),
    /** The report form (a GitHub issue form) for this package; null on indexes that do not have one yet. */
    val reportUrl: String? = null,
) {
    val votes get() = up + down

    /** Share of 👍, 0..100, or null with no votes. */
    val percentUp: Int? get() = if (votes == 0) null else Math.round(up * 100.0 / votes).toInt()

    /**
     * Lower bound of the Wilson score interval (95%) of the share of 👍: ranks by how sure we are that people like
     * it, so one 👍 does not beat forty 👍 and two 👎.
     */
    val score: Double get() {
        val n = (up + down).toDouble()
        if (n == 0.0) return 0.0
        val z = 1.96
        val p = up / n
        return (p + z * z / (2 * n) - z * Math.sqrt((p * (1 - p) + z * z / (4 * n)) / n)) / (1 + z * z / n)
    }

    companion object {
        fun fromJson(o: JSONObject?): CommunityRating? = o?.let {
            val url = it.optString("url")
            // Opened in the browser: only GitHub links, even from the signed index.
            if (!url.startsWith("https://github.com/")) return null
            val compat = it.optJSONObject("compat")?.let { c ->
                c.keys().asSequence().mapNotNull { device -> CompatReports.fromJson(c.optJSONObject(device))?.let { r -> device to r } }.toMap()
            }.orEmpty()
            CommunityRating(
                up = it.optInt("up").coerceAtLeast(0),
                down = it.optInt("down").coerceAtLeast(0),
                comments = it.optInt("comments").coerceAtLeast(0),
                url = url,
                verified = it.optInt("rules") >= 2,
                compat = compat,
                reportUrl = it.optString("reportUrl").takeIf { r -> r.startsWith("https://github.com/") },
            )
        }
    }
}

/** One release of a package, newest first in [CommunityPackage.changelog]. */
data class ChangelogEntry(val version: String, val date: String, val notes: String)

/**
 * Where a code mod's files go. Keys of [gameFiles]/[prefixFiles] are file paths in the package or folder prefixes
 * ("plugin/"); values are paths relative to the game folder / the prefix's drive_c ("" = the root, keeping the rest of
 * the path). [sharedAssets] reuses APK assets (see [CommunityIndex.SHARED_ASSETS]).
 */
data class CommunityInstall(
    val gameFiles: Map<String, String>,
    val prefixFiles: Map<String, String>,
    val sharedAssets: Map<String, String>,
    val dllOverrides: List<String>,
    val launchExecutable: String?,
    val splitLayout: String?,
) {
    companion object {
        fun fromJson(o: JSONObject?): CommunityInstall? = o?.let {
            CommunityInstall(
                gameFiles = it.optJSONObject("gameFiles").stringMap(),
                prefixFiles = it.optJSONObject("prefixFiles").stringMap(),
                sharedAssets = it.optJSONObject("sharedAssets").stringMap(),
                dllOverrides = it.optJSONArray("dllOverrides").strings(),
                launchExecutable = it.optString("launchExecutable").ifEmpty { null },
                splitLayout = it.optString("splitLayout").ifEmpty { null },
            )
        }
    }
}

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
    /** Code mods only. */
    val install: CommunityInstall? = null,
    val rating: CommunityRating? = null,
    /** Experimental packages are listed with a warning. */
    val experimental: Boolean = tags.contains("experimental"),
    val changelog: List<ChangelogEntry> = emptyList(),
) {
    fun file(path: String): CommunityFile? = files.firstOrNull { it.path == path }

    /** Download size in bytes (files of unknown size count as 0). */
    val size: Long get() = files.sumOf { maxOf(it.size, 0) }

    /** Made by GameNative Duo: only from the list pinned in the app, never from anything the package says. */
    val official: Boolean get() = id in CommunityIndex.OFFICIAL_PACKAGES

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
                install = CommunityInstall.fromJson(o.optJSONObject("install")),
                rating = CommunityRating.fromJson(o.optJSONObject("rating")),
                changelog = o.optJSONArray("changelog")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        a.optJSONObject(i)?.let { c -> ChangelogEntry(c.optString("version"), c.optString("date"), c.optString("notes")) }
                            ?.takeIf { c -> c.notes.isNotBlank() }
                    }
                }.orEmpty(),
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

private fun JSONObject?.stringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    return keys().asSequence().associateWith { optString(it) }
}

private fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> optString(i).ifEmpty { null } }
}
