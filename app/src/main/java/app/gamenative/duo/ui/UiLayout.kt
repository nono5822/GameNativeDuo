package app.gamenative.duo.ui

import android.content.Context
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.mods.DuoModCatalog
import com.winlator.container.Container
import java.io.File
import org.json.JSONObject
import timber.log.Timber

/** Where a UI element goes. [DEFAULT] leaves it to the mod (bottom screen, crosshairs on top). */
enum class UiScreen(val id: String) {
    DEFAULT("default"),
    BOTTOM("bottom"),
    TOP("top"),
    HIDDEN("hidden"),
    ;

    companion object {
        fun fromId(id: String?): UiScreen = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * The user's choice for one UI element. [x] and [y] shift it by fractions of its screen's width and height
 * (+ is right and down), so a layout fits any device with the same screen shapes.
 */
data class UiRule(val screen: UiScreen = UiScreen.DEFAULT, val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    val isNeutral: Boolean get() = this == UiRule()

    fun toJson(): JSONObject = JSONObject().apply {
        if (screen != UiScreen.DEFAULT) put("screen", screen.id)
        if (scale != 1f) put("scale", round(scale))
        if (x != 0f) put("x", round(x))
        if (y != 0f) put("y", round(y))
    }

    companion object {
        const val SCALE_MIN = 0.25f
        const val SCALE_MAX = 4f

        fun fromJson(o: JSONObject) = UiRule(
            screen = UiScreen.fromId(o.optString("screen")),
            scale = o.optDouble("scale", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(SCALE_MIN, SCALE_MAX) ?: 1f,
            x = o.optDouble("x", 0.0).toFloat().takeIf { it.isFinite() }?.coerceIn(-2f, 2f) ?: 0f,
            y = o.optDouble("y", 0.0).toFloat().takeIf { it.isFinite() }?.coerceIn(-2f, 2f) ?: 0f,
        )

        private fun round(v: Float) = Math.round(v * 10000.0) / 10000.0
    }
}

/**
 * A game's UI layout for the two screens: element id -> [UiRule]. Element ids come from the game's mod (for Unity,
 * object paths like `HUD/Canvas/Crosshair`), so the same file works for everyone with that game and mod. This is the
 * unit the community preset system shares:
 * `{"format":"gamenative-duo/ui-layout","version":1,"game":"STEAM_433340","engine":"unity-ugui","elements":{...}}`.
 */
data class UiLayout(val game: String, val engine: String? = null, val rules: Map<String, UiRule> = emptyMap()) {
    fun rule(id: String): UiRule = rules[id] ?: UiRule()

    fun with(id: String, rule: UiRule): UiLayout = copy(rules = if (rule.isNeutral) rules - id else rules + (id to rule))

    fun toJson(): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("game", game)
        engine?.let { put("engine", it) }
        put("elements", JSONObject().apply { rules.toSortedMap().forEach { (id, r) -> put(id, r.toJson()) } })
    }

    companion object {
        const val FORMAT = "gamenative-duo/ui-layout"
        const val VERSION = 1

        fun fromJson(json: JSONObject, game: String): UiLayout {
            val elements = json.optJSONObject("elements") ?: JSONObject()
            val rules = buildMap {
                elements.keys().forEach { id -> elements.optJSONObject(id)?.let { put(id, UiRule.fromJson(it)) } }
            }
            return UiLayout(json.optString("game", game).ifEmpty { game }, json.optString("engine").ifEmpty { null }, rules)
        }
    }
}

/**
 * UI layouts per game: the user's own in `files/duo/ui/<game>.json`, else the preset bundled with the game's mod
 * (`assets/duo/mods/<mod>/ui-layout.json`), else empty. The layout in use is copied to the container's prefix as
 * `%AppData%\GameNativeDuo\ui.json`, where the mod reads it (live: rewrite it and send `RULES_CHANGED`).
 */
object UiLayoutStore {
    fun load(context: Context, game: String): UiLayout = readUser(context, game) ?: preset(context, game) ?: UiLayout(game)

    fun hasUserLayout(context: Context, game: String): Boolean = userFile(context, game).isFile

    /** The bundled preset for [game], or null when its mod ships none. */
    fun preset(context: Context, game: String): UiLayout? = try {
        DuoModCatalog.forApp(game)?.let { mod ->
            context.assets.open("duo/mods/${mod.id}/ui-layout.json").use {
                UiLayout.fromJson(JSONObject(it.reader().readText()), game)
            }
        }
    } catch (e: java.io.FileNotFoundException) {
        null
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).w(t, "Ignoring unreadable UI preset for %s", game)
        null
    }

    fun save(context: Context, layout: UiLayout) {
        try {
            atomicWrite(userFile(context, layout.game), layout.toJson().toString(2))
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Failed to save UI layout for %s", layout.game)
        }
    }

    /** Forgets the user's layout; the preset (or nothing) applies again. */
    fun reset(context: Context, game: String): UiLayout {
        userFile(context, game).delete()
        return load(context, game)
    }

    /** Hands [layout] to the game's mod. */
    fun writeToPrefix(container: Container, layout: UiLayout) {
        try {
            atomicWrite(prefixFile(container), layout.toJson().toString())
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to write ui.json")
        }
    }

    fun prefixFile(container: Container) = File(container.rootDir, ".wine/drive_c/users/xuser/AppData/Roaming/GameNativeDuo/ui.json")

    /** The mod's element list for the layout editor (see the bridge's DUMP_ELEMENTS). */
    fun elementsFile(container: Container) =
        File(container.rootDir, ".wine/drive_c/users/xuser/AppData/Roaming/GameNativeDuo/elements.json")

    private fun readUser(context: Context, game: String): UiLayout? = try {
        val file = userFile(context, game)
        if (file.isFile) UiLayout.fromJson(JSONObject(file.readText()), game) else null
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).w(t, "Ignoring unreadable UI layout for %s", game)
        null
    }

    private fun userFile(context: Context, game: String) =
        File(context.filesDir, "duo/ui/" + game.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")

    private fun atomicWrite(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) file.writeText(text)
    }
}
