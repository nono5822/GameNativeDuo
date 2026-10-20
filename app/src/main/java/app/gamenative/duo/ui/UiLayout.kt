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
    }
}

/** What the bottom screen shows behind the game's UI. Drawn by the game's mod (see [UiBackground.supported]). */
enum class UiBackgroundMode(val id: String, val label: String) {
    GAME("game", "Game"),
    BLUR("blur", "Blurred game"),
    COLOR("color", "Color"),
    IMAGE("image", "Image"),
    ;

    companion object {
        fun fromId(id: String?): UiBackgroundMode = entries.firstOrNull { it.id == id } ?: GAME
    }
}

/**
 * The bottom screen's background. [dim] darkens it (0 to 0.9), [blur] is the blur strength (0 to 1), [color] is
 * opaque RGB. [image] is set when an image was chosen: the app keeps it next to the layout and hands it to the mod
 * as `background.jpg`.
 */
data class UiBackground(
    val mode: UiBackgroundMode = UiBackgroundMode.GAME,
    val color: Int = DEFAULT_COLOR,
    val dim: Float = 0f,
    val blur: Float = 0.5f,
    val image: Boolean = false,
) {
    val isDefault: Boolean get() = this == UiBackground()

    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode.id)
        put("color", String.format("#%06X", color and 0xFFFFFF))
        if (dim != 0f) put("dim", round(dim))
        put("blur", round(blur))
        if (image) put("image", IMAGE_FILE)
    }

    companion object {
        const val IMAGE_FILE = "background.jpg"
        const val DEFAULT_COLOR = 0xFF101322.toInt()

        fun fromJson(o: JSONObject?): UiBackground {
            if (o == null) return UiBackground()
            val color = try {
                0xFF000000.toInt() or Integer.parseInt(o.optString("color").removePrefix("#").take(6), 16)
            } catch (e: NumberFormatException) {
                DEFAULT_COLOR
            }
            return UiBackground(
                mode = UiBackgroundMode.fromId(o.optString("mode")),
                color = color,
                dim = o.optDouble("dim", 0.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 0.9f) ?: 0f,
                blur = o.optDouble("blur", 0.5).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f,
                image = o.optString("image").isNotEmpty(),
            )
        }
    }
}

/** A small live readout the app draws on the bottom screen, above the game. */
enum class UiWidgetType(val id: String, val label: String) {
    FPS("fps", "FPS"),
    CPU("cpu", "CPU"),
    GPU("gpu", "GPU"),
    TEMPERATURE("temp", "Temperature"),
    BATTERY("battery", "Battery"),
    CLOCK("clock", "Clock"),
    ;

    companion object {
        fun fromId(id: String?): UiWidgetType? = entries.firstOrNull { it.id == id }
    }
}

/** A placed widget: its top-left corner at fractions [x], [y] of the bottom screen, [scale] times its normal size. */
data class UiWidget(val type: UiWidgetType, val x: Float, val y: Float, val scale: Float = 1f) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.id)
        put("x", round(x))
        put("y", round(y))
        if (scale != 1f) put("scale", round(scale))
    }

    companion object {
        const val SCALE_MIN = 0.5f
        const val SCALE_MAX = 3f

        fun fromJson(o: JSONObject): UiWidget? {
            val type = UiWidgetType.fromId(o.optString("type")) ?: return null
            fun frac(key: String) = o.optDouble(key, 0.0).toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            val scale = o.optDouble("scale", 1.0).toFloat().takeIf { it.isFinite() }?.coerceIn(SCALE_MIN, SCALE_MAX) ?: 1f
            return UiWidget(type, frac("x"), frac("y"), scale)
        }
    }
}

private fun round(v: Float) = Math.round(v * 10000.0) / 10000.0

/**
 * A game's UI layout for the two screens: element id -> [UiRule]. Element ids come from the game's mod (for Unity,
 * object paths like `HUD/Canvas/Crosshair`), so the same file works for everyone with that game and mod. This is the
 * unit the community preset system shares:
 * `{"format":"gamenative-duo/ui-layout","version":1,"game":"STEAM_433340","engine":"unity-ugui","elements":{...}}`.
 */
data class UiLayout(
    val game: String,
    val engine: String? = null,
    val rules: Map<String, UiRule> = emptyMap(),
    val background: UiBackground = UiBackground(),
    val widgets: List<UiWidget> = emptyList(),
) {
    fun rule(id: String): UiRule = rules[id] ?: UiRule()

    fun with(id: String, rule: UiRule): UiLayout = copy(rules = if (rule.isNeutral) rules - id else rules + (id to rule))

    fun toJson(): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("game", game)
        engine?.let { put("engine", it) }
        put("elements", JSONObject().apply { rules.toSortedMap().forEach { (id, r) -> put(id, r.toJson()) } })
        if (!background.isDefault) put("background", background.toJson())
        if (widgets.isNotEmpty()) put("widgets", org.json.JSONArray().apply { widgets.forEach { put(it.toJson()) } })
    }

    companion object {
        const val FORMAT = "gamenative-duo/ui-layout"
        const val VERSION = 1
        const val MAX_WIDGETS = 12

        fun fromJson(json: JSONObject, game: String): UiLayout {
            val elements = json.optJSONObject("elements") ?: JSONObject()
            val rules = buildMap {
                elements.keys().forEach { id -> elements.optJSONObject(id)?.let { put(id, UiRule.fromJson(it)) } }
            }
            val widgetArray = json.optJSONArray("widgets")
            val widgets = (0 until (widgetArray?.length() ?: 0)).mapNotNull { i ->
                widgetArray!!.optJSONObject(i)?.let { UiWidget.fromJson(it) }
            }.take(MAX_WIDGETS)
            return UiLayout(
                json.optString("game", game).ifEmpty { game },
                json.optString("engine").ifEmpty { null },
                rules,
                UiBackground.fromJson(json.optJSONObject("background")),
                widgets,
            )
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

    /** Hands [layout] to the game's mod, with its background image (copied only when it changed). */
    fun writeToPrefix(context: Context, container: Container, layout: UiLayout) {
        try {
            if (layout.background.image) {
                val source = imageFile(context, layout.game)
                val target = File(prefixFile(container).parentFile, UiBackground.IMAGE_FILE)
                if (source.isFile && (source.length() != target.length() || source.lastModified() > target.lastModified())) {
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                }
            }
            atomicWrite(prefixFile(container), layout.toJson().toString())
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to write ui.json")
        }
    }

    /** The background image chosen for [game] (JPEG, at most the bottom screen's size). */
    fun imageFile(context: Context, game: String) =
        File(context.filesDir, "duo/ui/" + game.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".background.jpg")

    /**
     * Stores an image (any format Android decodes) as [game]'s background, scaled down to cover [maxW] x [maxH].
     * Returns false when it cannot be decoded. Call off the main thread.
     */
    fun saveImage(context: Context, game: String, bytes: ByteArray, maxW: Int, maxH: Int): Boolean = try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) sample *= 2
        val decoded = android.graphics.BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply {
                inSampleSize =
                    sample
            },
        )
        if (decoded == null) {
            false
        } else {
            val cover = maxOf(maxW.toFloat() / decoded.width, maxH.toFloat() / decoded.height).coerceAtMost(1f)
            val scaled = if (cover < 1f) {
                android.graphics.Bitmap.createScaledBitmap(decoded, (decoded.width * cover).toInt(), (decoded.height * cover).toInt(), true)
            } else {
                decoded
            }
            val file = imageFile(context, game)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            true
        }
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).w(t, "Failed to store the background image for %s", game)
        false
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
