package app.gamenative.duo.community

import android.content.Context
import android.util.Base64
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.mods.DuoGameMod
import app.gamenative.duo.mods.DuoModInstaller
import app.gamenative.duo.split.SplitLayout
import app.gamenative.utils.Net
import java.io.File
import java.io.IOException
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/**
 * Installs and removes community code mods (kind "mod"), through [DuoModInstaller] so they follow the same rules as
 * bundled mods: nothing overwritten without a backup, everything recorded and undone on removal, screen split and
 * screen size set and restored. Every file is downloaded and checked against its sha256 before anything is written.
 * One dual-screen mod per game: installing replaces the one that is there. Blocking: call from a background thread.
 */
object CommunityModInstaller {
    /** What installing [pkg] does, for the consent dialog. */
    data class Plan(
        val gameFiles: Map<String, CommunityFile>,
        val prefixFiles: Map<String, CommunityFile>,
        val sharedAssets: Map<String, String>,
        val dllOverrides: List<String>,
        val launchExecutable: String?,
        val downloadBytes: Long,
    )

    /** Resolves the package's install map into target paths. Throws [IOException] for a package this app cannot install. */
    fun plan(pkg: CommunityPackage): Plan {
        pkg.revoked?.let { throw IOException("this version was withdrawn: $it") }
        val install = pkg.install ?: throw IOException("the package says nothing about where its files go")
        fun resolve(map: Map<String, String>): Map<String, CommunityFile> {
            val out = LinkedHashMap<String, CommunityFile>()
            map.forEach { (src, dst) ->
                val folder = src.trimEnd('/') + "/"
                val matches = pkg.files.filter { it.path == src || it.path.startsWith(folder) }
                if (matches.isEmpty()) throw IOException("$src is not in the package")
                matches.forEach { f ->
                    val rest = if (f.path == src) f.path.substringAfterLast('/') else f.path.removePrefix(folder)
                    val target = when {
                        f.path == src && dst.isNotEmpty() && !dst.endsWith("/") -> dst
                        dst.isEmpty() -> rest
                        else -> dst.trimEnd('/') + "/" + rest
                    }
                    out[target] = f
                }
            }
            return out
        }
        install.sharedAssets.keys.firstOrNull { it !in CommunityIndex.SHARED_ASSETS }?.let {
            throw IOException("this app has no shared asset '$it'")
        }
        val game = resolve(install.gameFiles)
        val prefix = resolve(install.prefixFiles)
        checkTargets(game.keys, prefix.keys, install)
        val download = pkg.files.sumOf { maxOf(it.size, 0) }
        return Plan(game, prefix, install.sharedAssets, install.dllOverrides, install.launchExecutable, download)
    }

    /** Where a mod may write in the prefix (drive_c): the user's own folders, never Windows, programs or our files. */
    private val PREFIX_ALLOWED = listOf("users/xuser/AppData/", "users/xuser/Documents/")
    private const val PREFIX_RESERVED = "users/xuser/AppData/Roaming/GameNativeDuo/"

    /** DLLs a shared asset puts at the game folder's root. */
    private val SHARED_DLLS = mapOf("bepinex" to setOf("winhttp"))

    /**
     * The community validator's install rules again (tools/duo_index.py check_install), so a broken or tampered index
     * entry cannot write outside them: relative paths only, prefix files in the user's folders, DLL overrides only for
     * DLLs the package installs at the game root.
     */
    internal fun checkTargets(game: Set<String>, prefix: Set<String>, install: CommunityInstall) {
        (game + prefix).firstOrNull { t -> t.startsWith("/") || t.contains('\\') || t.contains(':') || t.split('/').any { it == ".." } }
            ?.let { throw IOException("the package writes outside its folders ($it)") }
        prefix.firstOrNull { t -> PREFIX_ALLOWED.none { t.startsWith(it) } || t.startsWith(PREFIX_RESERVED) }
            ?.let { throw IOException("the package writes where mods may not ($it)") }
        val rootDlls = game.filter { '/' !in it && it.endsWith(".dll", ignoreCase = true) }.map { it.dropLast(4).lowercase() }.toSet() +
            install.sharedAssets.keys.flatMap { SHARED_DLLS[it].orEmpty() }
        install.dllOverrides.map { it.substringBefore('=').lowercase() }.firstOrNull { it !in rootDlls }
            ?.let { throw IOException("the package overrides $it.dll without installing it") }
    }

    /** Downloads, checks and installs [pkg] for the game at [gameDir]. Throws [IOException] with a user-facing reason. */
    fun install(context: Context, containerId: String, gameDir: File, pkg: CommunityPackage) {
        val plan = plan(pkg)
        if (!gameDir.isDirectory) throw IOException("the game folder was not found")
        // Download (and verify) everything before touching the game.
        val bytes = HashMap<String, ByteArray>()
        (plan.gameFiles.values + plan.prefixFiles.values).distinctBy { it.sha256 }.forEach {
            bytes[it.sha256] = CommunityPackages.download(context, it)
        }
        if (DuoModInstaller.isEnabled(context, containerId) && !DuoModInstaller.disable(context, containerId)) {
            throw IOException("the dual-screen mod already installed could not be removed")
        }
        val mod = DuoGameMod(
            id = pkg.id,
            appId = containerId,
            title = pkg.title,
            gameFiles = emptyMap(),
            dllOverrides = plan.dllOverrides,
            launchExecutable = plan.launchExecutable,
            splitLayout = SplitLayout.fromId(pkg.install?.splitLayout ?: SplitLayout.NATIVE.id),
            // Our shared BepInEx only loads in Unity games.
            unity = "bepinex" in plan.sharedAssets,
        )
        val ok = DuoModInstaller.enableDownloaded(
            context,
            mod,
            gameDir,
            gameFiles = plan.gameFiles.mapValues { bytes.getValue(it.value.sha256) },
            prefixFiles = plan.prefixFiles.mapValues { bytes.getValue(it.value.sha256) },
            sharedAssets = plan.sharedAssets,
            origin = JSONObject().put("package", pkg.id).put("version", pkg.version).apply {
                // Built from source by the community CI: the bridge enforces what its manifest declared (none: nothing).
                if (pkg.builtFrom != null) put("bridge", (pkg.bridge ?: BridgePermissions(emptySet(), emptyList())).toJson())
            },
        )
        if (!ok) throw IOException("installing failed (see logs); nothing is left half-installed after removing it")
        CommunityPackages.markApplied(context, containerId, pkg)
        AuthorKeyPins.pin(context, pkg)
        Timber.tag(DualScreenHost.TAG).i("Community: installed mod %s %s for %s", pkg.id, pkg.version, containerId)
    }

    /** True when the community package [packageId] is the mod installed for [containerId]. */
    fun isInstalled(context: Context, containerId: String, packageId: String): Boolean =
        DuoModInstaller.enabledModId(context, containerId) == packageId

    fun uninstall(context: Context, containerId: String) {
        if (!DuoModInstaller.disable(context, containerId)) throw IOException("removing failed (see logs)")
        CommunityPackages.forget(context, containerId, CommunityKind.MOD)
    }
}

/**
 * The community catalog (every game that has packages), so the Community button shows for games without a bundled
 * mod too. Fetched once per app run in the background, verified like the per-game lists, cached for offline use.
 */
object CommunityCatalog {
    @Volatile private var keys: Set<String>? = null

    /** Game keys with packages, from memory or the disk cache; null before the first successful load. */
    fun known(context: Context): Set<String>? = keys ?: readCached(context)?.also { keys = it }

    /** Refreshes from the network at most once per app run. Never throws. */
    fun refreshOnce(context: Context) {
        if (refreshed) return
        refreshed = true
        try {
            val body = fetch(CommunityIndex.CATALOG_URL)
            val sig = String(fetch(CommunityIndex.CATALOG_URL + ".sig"), Charsets.UTF_8)
            val parsed = parse(body, sig, IndexSequences.highest(context, IndexSequences.CATALOG)) ?: return
            IndexSequences.accept(context, IndexSequences.CATALOG, sequenceOf(body))
            keys = parsed
            File(context.filesDir, "duo/community/index").mkdirs()
            cacheFile(context).writeBytes(body)
            File(cacheFile(context).path + ".sig").writeText(sig)
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).d(t, "Community catalog not refreshed")
        }
    }

    @Volatile private var refreshed = false

    private fun readCached(context: Context): Set<String>? = try {
        val file = cacheFile(context)
        val sig = File(file.path + ".sig")
        if (file.isFile && sig.isFile) parse(file.readBytes(), sig.readText()) else null
    } catch (t: Throwable) {
        null
    }

    private fun sequenceOf(body: ByteArray) = IndexFreshness.sequenceOf(JSONObject(String(body, Charsets.UTF_8)))

    /** Null for a wrong signature, and for a signed catalog older than [highest] ([IndexFreshness]). */
    private fun parse(body: ByteArray, signature: String, highest: Long = 0L): Set<String>? {
        val key = Base64.decode(CommunityIndex.PUBLIC_KEY, Base64.DEFAULT)
        val sig = try {
            Base64.decode(signature.trim(), Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (!Ed25519.verify(key, body, sig)) return null
        val json = JSONObject(String(body, Charsets.UTF_8))
        IndexFreshness.problem(json, null, highest)?.let {
            Timber.tag(DualScreenHost.TAG).w("Community catalog refused: %s", it)
            return null
        }
        val games = json.optJSONArray("games") ?: return emptySet()
        return (0 until games.length()).mapNotNull { games.optJSONObject(it)?.optString("key")?.ifEmpty { null } }.toSet()
    }

    private fun fetch(url: String): ByteArray = Net.http.newCall(Request.Builder().url(url).build()).execute().use {
        if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
        it.body.bytes()
    }

    private fun cacheFile(context: Context) = File(context.filesDir, "duo/community/index/catalog.json")
}
