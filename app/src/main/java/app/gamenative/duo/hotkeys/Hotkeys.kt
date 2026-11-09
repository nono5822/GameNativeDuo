package app.gamenative.duo.hotkeys

import com.winlator.xserver.XKeycode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Touch hotkeys: a grid of buttons on the bottom screen that press keys, key combos, mouse buttons or controller
 * buttons in the game, or send a command to the game's mod. Stored per game in the UI layout (`hotkeys`), so the
 * layout editors and community layouts carry them. Pure: no Android, unit-tested.
 *
 * JSON (`UiLayout.hotkeys`):
 * `{"columns": 3, "size": "m", "haptics": true, "buttons": [{"label": "Map", "icon": "🗺", "keys": "M"},
 *   {"label": "Run", "keys": "Shift", "mode": "toggle"}, {"label": "Aim", "mouse": "right", "mode": "hold"},
 *   {"label": "Pause", "pad": "start"}, {"label": "Next tool", "command": "sdv.toolbar.next", "keys": "Tab", "span": 2}]}`
 */
data class HotkeyGrid(
    val buttons: List<Hotkey>,
    val columns: Int = DEFAULT_COLUMNS,
    val size: HotkeySize = HotkeySize.MEDIUM,
    val haptics: Boolean = true,
    /** The preset these buttons started from (shown in the editor; "Reset" goes back to it). */
    val preset: HotkeyPreset? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("columns", columns)
        put("size", size.id)
        if (!haptics) put("haptics", false)
        preset?.let { put("preset", it.id) }
        put("buttons", JSONArray().apply { buttons.forEach { put(it.toJson()) } })
    }

    companion object {
        const val DEFAULT_COLUMNS = 3
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 5
        const val MAX_BUTTONS = 40

        /** Never fails: bad buttons are dropped, bad values fall back to defaults. */
        fun fromJson(o: JSONObject): HotkeyGrid {
            val array = o.optJSONArray("buttons")
            val buttons = (0 until (array?.length() ?: 0)).mapNotNull { i -> array!!.optJSONObject(i)?.let { Hotkey.fromJson(it) } }
            return HotkeyGrid(
                buttons = buttons.take(MAX_BUTTONS),
                columns = o.optInt("columns", DEFAULT_COLUMNS).coerceIn(MIN_COLUMNS, MAX_COLUMNS),
                size = HotkeySize.fromId(o.optString("size")),
                haptics = o.optBoolean("haptics", true),
                preset = HotkeyPreset.fromId(o.optString("preset")),
            )
        }
    }
}

enum class HotkeySize(val id: String, val label: String, val heightDp: Int) {
    SMALL("s", "Small", 56),
    MEDIUM("m", "Medium", 72),
    LARGE("l", "Large", 96),
    ;

    companion object {
        fun fromId(id: String?): HotkeySize = entries.firstOrNull { it.id == id } ?: MEDIUM
    }
}

enum class HotkeyMode(val id: String, val label: String) {
    /** One short press however long the finger stays (menus, quick save). */
    TAP("tap", "Tap"),

    /** Held while the finger is down (sprint, aim). */
    HOLD("hold", "Hold"),

    /** First tap holds it, the next one lets go (auto-run, crouch). */
    TOGGLE("toggle", "Toggle"),
    ;

    companion object {
        fun fromId(id: String?): HotkeyMode = entries.firstOrNull { it.id == id } ?: TAP
    }
}

/** One button. [span] is how many grid cells it takes in its row. */
data class Hotkey(
    val label: String,
    val action: HotkeyAction,
    val icon: String = "",
    val mode: HotkeyMode = HotkeyMode.TAP,
    val span: Int = 1,
) {
    /** What the button does, for the editor and for a button with no label. */
    val description: String get() = action.description

    /** The wheel turns one notch per tap: holding or toggling it means nothing. */
    val effectiveMode: HotkeyMode get() = if (action is HotkeyAction.Mouse && action.button.wheel) HotkeyMode.TAP else mode

    fun toJson(): JSONObject = JSONObject().apply {
        put("label", label)
        if (icon.isNotEmpty()) put("icon", icon)
        action.writeTo(this)
        if (mode != HotkeyMode.TAP) put("mode", mode.id)
        if (span != 1) put("span", span)
    }

    companion object {
        const val MAX_LABEL = 24
        const val MAX_ICON = 8

        fun fromJson(o: JSONObject): Hotkey? {
            val action = HotkeyAction.fromJson(o) ?: return null
            return Hotkey(
                label = o.optString("label").trim().take(MAX_LABEL),
                action = action,
                icon = o.optString("icon").trim().take(MAX_ICON),
                mode = HotkeyMode.fromId(o.optString("mode")),
                span = o.optInt("span", 1).coerceIn(1, HotkeyGrid.MAX_COLUMNS),
            )
        }
    }
}

sealed class HotkeyAction {
    abstract val description: String

    abstract fun writeTo(o: JSONObject)

    /** Keys pressed together, in order (modifiers first), released in reverse. */
    data class Keys(val keys: List<XKeycode>) : HotkeyAction() {
        override val description: String get() = HotkeyKeys.format(keys)

        override fun writeTo(o: JSONObject) {
            o.put("keys", description)
        }
    }

    data class Mouse(val button: MouseButton) : HotkeyAction() {
        override val description: String get() = button.label

        override fun writeTo(o: JSONObject) {
            o.put("mouse", button.id)
        }
    }

    data class Pad(val button: PadButton) : HotkeyAction() {
        override val description: String get() = "Controller ${button.label}"

        override fun writeTo(o: JSONObject) {
            o.put("pad", button.id)
        }
    }

    /**
     * A command for the game's mod (bridge COMMAND, duo/docs/companions.md), e.g. `sdv.toolbar.next`. [fallback] is
     * pressed instead when no mod that takes commands is running.
     */
    data class Command(val command: String, val fallback: Keys? = null) : HotkeyAction() {
        override val description: String get() = "Mod command $command" + (fallback?.let { " (else ${it.description})" } ?: "")

        override fun writeTo(o: JSONObject) {
            o.put("command", command)
            fallback?.writeTo(o)
        }
    }

    companion object {
        private val COMMAND = Regex("[a-z][a-z0-9_-]*(\\.[a-z0-9_-]+)+")

        fun fromJson(o: JSONObject): HotkeyAction? {
            val keys = o.optString("keys").takeIf { it.isNotBlank() }?.let { HotkeyKeys.parse(it) }?.let { Keys(it) }
            val command = o.optString("command").trim()
            if (command.isNotEmpty()) return if (COMMAND.matches(command)) Command(command, keys) else null
            if (keys != null) return keys
            MouseButton.fromId(o.optString("mouse"))?.let { return Mouse(it) }
            PadButton.fromId(o.optString("pad"))?.let { return Pad(it) }
            return null
        }
    }
}

enum class MouseButton(val id: String, val label: String, val wheel: Boolean = false) {
    LEFT("left", "Left click"),
    RIGHT("right", "Right click"),
    MIDDLE("middle", "Middle click"),
    WHEEL_UP("wheel-up", "Wheel up", wheel = true),
    WHEEL_DOWN("wheel-down", "Wheel down", wheel = true),
    ;

    companion object {
        fun fromId(id: String?): MouseButton? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Controller buttons, as GameNative's virtual controller numbers them ([index] for face, shoulder and stick buttons,
 * [dpad] 0..3 = up, right, down, left). Triggers are left out: the real controller's analog axes rewrite them on every
 * movement, so a touch press would not stay pressed.
 */
enum class PadButton(val id: String, val label: String, val index: Int = -1, val dpad: Int = -1) {
    A("a", "A", index = 0),
    B("b", "B", index = 1),
    X("x", "X", index = 2),
    Y("y", "Y", index = 3),
    LB("lb", "LB", index = 4),
    RB("rb", "RB", index = 5),
    BACK("back", "Back/Select", index = 6),
    START("start", "Start", index = 7),
    L3("l3", "L3", index = 8),
    R3("r3", "R3", index = 9),
    UP("up", "D-pad up", dpad = 0),
    RIGHT("right", "D-pad right", dpad = 1),
    DOWN("down", "D-pad down", dpad = 2),
    LEFT("left", "D-pad left", dpad = 3),
    ;

    companion object {
        fun fromId(id: String?): PadButton? = entries.firstOrNull { it.id == id }
    }
}

/** Key names in hotkey JSON: `"Ctrl+Shift+S"`, case-insensitive, `+` between keys. */
object HotkeyKeys {
    /** Name -> key, in the order the editor lists them. The first name of a key is how it is written back. */
    val NAMES: List<Pair<String, XKeycode>> = buildList {
        add("Esc" to XKeycode.KEY_ESC)
        add("Enter" to XKeycode.KEY_ENTER)
        add("Tab" to XKeycode.KEY_TAB)
        add("Space" to XKeycode.KEY_SPACE)
        add("Backspace" to XKeycode.KEY_BKSP)
        add("Shift" to XKeycode.KEY_SHIFT_L)
        add("Ctrl" to XKeycode.KEY_CTRL_L)
        add("Alt" to XKeycode.KEY_ALT_L)
        (1..12).forEach { add("F$it" to XKeycode.valueOf("KEY_F$it")) }
        ('A'..'Z').forEach { add("$it" to XKeycode.valueOf("KEY_$it")) }
        ('0'..'9').forEach { add("$it" to XKeycode.valueOf("KEY_$it")) }
        add("Up" to XKeycode.KEY_UP)
        add("Down" to XKeycode.KEY_DOWN)
        add("Left" to XKeycode.KEY_LEFT)
        add("Right" to XKeycode.KEY_RIGHT)
        add("Insert" to XKeycode.KEY_INSERT)
        add("Delete" to XKeycode.KEY_DEL)
        add("Home" to XKeycode.KEY_HOME)
        add("End" to XKeycode.KEY_END)
        add("PageUp" to XKeycode.KEY_PRIOR)
        add("PageDown" to XKeycode.KEY_NEXT)
        add("-" to XKeycode.KEY_MINUS)
        add("=" to XKeycode.KEY_EQUAL)
        add("[" to XKeycode.KEY_BRACKET_LEFT)
        add("]" to XKeycode.KEY_BRACKET_RIGHT)
        add(";" to XKeycode.KEY_SEMICOLON)
        add("'" to XKeycode.KEY_APOSTROPHE)
        add("," to XKeycode.KEY_COMMA)
        add("." to XKeycode.KEY_PERIOD)
        add("/" to XKeycode.KEY_SLASH)
        add("\\" to XKeycode.KEY_BACKSLASH)
        add("`" to XKeycode.KEY_GRAVE)
        add("RShift" to XKeycode.KEY_SHIFT_R)
        add("RCtrl" to XKeycode.KEY_CTRL_R)
        add("RAlt" to XKeycode.KEY_ALT_R)
        add("CapsLock" to XKeycode.KEY_CAPS_LOCK)
        add("PrintScreen" to XKeycode.KEY_PRTSCN)
        (0..9).forEach { add("Num$it" to XKeycode.valueOf("KEY_KP_$it")) }
        add("Num+" to XKeycode.KEY_KP_ADD)
        add("Num-" to XKeycode.KEY_KP_SUBTRACT)
        add("Num*" to XKeycode.KEY_KP_MULTIPLY)
        add("Num/" to XKeycode.KEY_KP_DIVIDE)
        add("NumEnter" to XKeycode.KEY_KP_ENTER)
    }

    /** Other spellings people write. */
    private val ALIASES = mapOf(
        "escape" to XKeycode.KEY_ESC, "return" to XKeycode.KEY_ENTER, "control" to XKeycode.KEY_CTRL_L,
        "del" to XKeycode.KEY_DEL, "pgup" to XKeycode.KEY_PRIOR, "pgdn" to XKeycode.KEY_NEXT, "plus" to XKeycode.KEY_EQUAL,
        "minus" to XKeycode.KEY_MINUS, "~" to XKeycode.KEY_GRAVE, "lshift" to XKeycode.KEY_SHIFT_L,
        "lctrl" to XKeycode.KEY_CTRL_L, "lalt" to XKeycode.KEY_ALT_L,
    )

    private val byName: Map<String, XKeycode> = NAMES.associate { (n, k) -> n.lowercase() to k } + ALIASES
    private val nameOf: Map<XKeycode, String> = NAMES.reversed().associate { (n, k) -> k to n }

    val MODIFIERS = setOf(
        XKeycode.KEY_SHIFT_L, XKeycode.KEY_SHIFT_R, XKeycode.KEY_CTRL_L, XKeycode.KEY_CTRL_R, XKeycode.KEY_ALT_L, XKeycode.KEY_ALT_R,
    )

    const val MAX_KEYS = 4

    fun name(key: XKeycode): String = nameOf[key] ?: key.name.removePrefix("KEY_")

    fun key(name: String): XKeycode? = byName[name.trim().lowercase()]

    /**
     * `"Ctrl+Tab"` -> [Ctrl, Tab]; null when any part is unknown or there are none. A `+` that belongs to a name
     * (`"Num+"`) stays with it; a lone `+` (`"Ctrl++"`) is the `=` key, as on a US keyboard.
     */
    fun parse(text: String): List<XKeycode>? {
        if (text.isBlank()) return null
        val raw = text.trim().split('+')
        val parts = mutableListOf<String>()
        var i = 0
        while (i < raw.size) {
            val t = raw[i].trim()
            if (t.isNotEmpty()) {
                parts += t
                i++
                continue
            }
            val prev = parts.lastOrNull()
            if (prev != null && key("$prev+") != null) {
                parts[parts.size - 1] = "$prev+"
                i++
            } else {
                parts += "+"
                i += if (i + 1 < raw.size && raw[i + 1].isBlank()) 2 else 1
            }
        }
        val keys = parts.map { p -> (if (p == "+") XKeycode.KEY_EQUAL else key(p)) ?: return null }.distinct()
        return keys.takeIf { it.isNotEmpty() && it.size <= MAX_KEYS }
    }

    fun format(keys: List<XKeycode>): String = keys.joinToString("+") { name(it) }
}

/** Starting grids per kind of game. The user edits from there; nothing here is sent anywhere until a button is touched. */
enum class HotkeyPreset(val id: String, val label: String) {
    GENERIC("generic", "PC game"),
    UNITY("unity", "Unity game"),
    STARDEW("stardew", "Stardew Valley"),
    ;

    val grid: HotkeyGrid get() = HotkeyGrid(buttons(), preset = this)

    private fun buttons(): List<Hotkey> = when (this) {
        GENERIC -> listOf(
            keys("Menu", "Esc", "☰"),
            keys("Enter", "Enter", "⏎"),
            keys("Tab", "Tab", "⇥"),
            keys("Map", "M", "🗺"),
            keys("Inventory", "I", "🎒"),
            keys("Journal", "J", "📖"),
            keys("Quick save", "F5", "💾"),
            keys("Quick load", "F9", "📂"),
            keys("Space", "Space", "␣"),
            mouse("Right click", MouseButton.RIGHT, "🖱"),
            mouse("Scroll up", MouseButton.WHEEL_UP, "▲"),
            mouse("Scroll down", MouseButton.WHEEL_DOWN, "▼"),
        )
        UNITY -> listOf(
            keys("Pause", "Esc", "⏸"),
            keys("Tab", "Tab", "⇥"),
            keys("Map", "M", "🗺"),
            keys("Inventory", "I", "🎒"),
            keys("Jump", "Space", "⤒"),
            keys("Interact", "E", "✋"),
            keys("Sprint", "Shift", "🏃", HotkeyMode.TOGGLE),
            keys("Crouch", "Ctrl", "⤓", HotkeyMode.HOLD),
            keys("Help", "F1", "?"),
            mouse("Right click", MouseButton.RIGHT, "🖱", HotkeyMode.HOLD),
            mouse("Scroll up", MouseButton.WHEEL_UP, "▲"),
            mouse("Scroll down", MouseButton.WHEEL_DOWN, "▼"),
        )
        // Stardew's default keyboard controls (Options -> Controls).
        STARDEW -> listOf(
            keys("Menu", "E", "🎒"),
            keys("Map", "M", "🗺"),
            keys("Journal", "F", "📖"),
            keys("Use tool", "C", "⛏", HotkeyMode.HOLD),
            keys("Action", "X", "✋"),
            keys("Run", "Shift", "🏃", HotkeyMode.TOGGLE),
            keys("Next row", "Tab", "⇥"),
            keys("Prev row", "Ctrl+Tab", "⇤"),
            keys("Chat", "T", "💬"),
            keys("Emote", "Y", "☺"),
            keys("Esc", "Esc", "✕"),
            mouse("Right click", MouseButton.RIGHT, "🖱"),
        )
    }

    companion object {
        fun fromId(id: String?): HotkeyPreset? = entries.firstOrNull { it.id == id }

        /** The preset for a game: Stardew by its Steam id, Unity by its bundled mod or the engine its mod reported. */
        fun forGame(appId: String, unityMod: Boolean, engine: String?): HotkeyPreset = when {
            appId == "STEAM_413150" -> STARDEW
            unityMod || engine.orEmpty().startsWith("unity") -> UNITY
            else -> GENERIC
        }

        private fun keys(label: String, keys: String, icon: String, mode: HotkeyMode = HotkeyMode.TAP) =
            Hotkey(label, HotkeyAction.Keys(HotkeyKeys.parse(keys)!!), icon, mode)

        private fun mouse(label: String, button: MouseButton, icon: String, mode: HotkeyMode = HotkeyMode.TAP) =
            Hotkey(label, HotkeyAction.Mouse(button), icon, mode)
    }
}

/** Short emoji and symbols the editor offers as icons (any short text works; these render on every Android). */
val HOTKEY_ICONS = listOf(
    "", "☰", "⏸", "🗺", "🎒", "📖", "💾", "📂", "⚙", "🏃", "✋", "⛏", "⚔", "🛡", "🏹", "🔍",
    "💬", "📷", "❤", "⭐", "▲", "▼", "◀", "▶", "⏎", "⇥", "✕", "?", "🖱", "🎮",
)
