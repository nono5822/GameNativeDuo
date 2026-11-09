package app.gamenative.duo.companion

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.hotkeys.HOTKEY_ICONS
import app.gamenative.duo.hotkeys.Hotkey
import app.gamenative.duo.hotkeys.HotkeyAction
import app.gamenative.duo.hotkeys.HotkeyKeys
import app.gamenative.duo.hotkeys.HotkeyMode
import app.gamenative.duo.hotkeys.MouseButton
import app.gamenative.duo.hotkeys.PadButton
import app.gamenative.duo.ui.Snapshots
import com.winlator.xserver.XKeycode
import timber.log.Timber

/**
 * Edits one hotkey in a dialog on the main screen (like the companion's text input: the keyboard serves the main
 * display, and a normal dialog works with the D-pad). Plain pickers, no typing needed except the label.
 */
internal object HotkeyEditorDialog {
    sealed class Result {
        data class Saved(val hotkey: Hotkey, val position: Int) : Result()

        object Deleted : Result()
    }

    private enum class Kind(val label: String) {
        KEYS("Key or key combo"),
        MOUSE("Mouse button"),
        PAD("Controller button"),
        COMMAND("Mod command"),
    }

    private val MODS = listOf("Ctrl" to XKeycode.KEY_CTRL_L, "Shift" to XKeycode.KEY_SHIFT_L, "Alt" to XKeycode.KEY_ALT_L)
    private const val NO_KEY = "—"

    /**
     * [hotkey] null adds a new button. [position] is its place (1-based) among [positions]; [columns] bounds its width.
     * [done] runs only on Save or Delete.
     */
    fun show(context: Context, hotkey: Hotkey?, columns: Int, position: Int, positions: Int, done: (Result) -> Unit) {
        val activity = Snapshots.activityOf(context)
        if (activity == null) {
            Timber.tag(DualScreenHost.TAG).w("Hotkey editor: no activity to show the dialog on")
            return
        }
        try {
            build(activity, hotkey, columns, position, positions, done)
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Could not open the hotkey editor")
        }
    }

    private fun build(ctx: Context, hotkey: Hotkey?, columns: Int, position: Int, positions: Int, done: (Result) -> Unit) {
        val pad = CompanionCards.dp(ctx, 20)
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun caption(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 13f
            alpha = 0.7f
            setPadding(0, CompanionCards.dp(ctx, 10), 0, 0)
        }
        fun spinner(items: List<String>, selected: Int) = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
            minimumHeight = CompanionCards.dp(ctx, 48)
        }
        fun section() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        // Label and icon.
        form.addView(caption("Label"))
        val label = EditText(ctx).apply {
            setText(hotkey?.label.orEmpty())
            hint = "Shown on the button"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(android.text.InputFilter.LengthFilter(Hotkey.MAX_LABEL))
        }
        form.addView(label)
        form.addView(caption("Icon"))
        val icons = (HOTKEY_ICONS + listOfNotNull(hotkey?.icon?.takeIf { it.isNotEmpty() && it !in HOTKEY_ICONS }))
        val icon = spinner(icons.map { it.ifEmpty { "(none)" } }, icons.indexOf(hotkey?.icon.orEmpty()).coerceAtLeast(0))
        form.addView(icon)

        // What it does.
        val action = hotkey?.action
        form.addView(caption("Sends"))
        val kind = spinner(
            Kind.entries.map { it.label },
            when (action) {
                is HotkeyAction.Mouse -> Kind.MOUSE.ordinal
                is HotkeyAction.Pad -> Kind.PAD.ordinal
                is HotkeyAction.Command -> Kind.COMMAND.ordinal
                else -> Kind.KEYS.ordinal
            },
        )
        form.addView(kind)

        val commandBox = section()
        commandBox.addView(caption("Command (the game's mod lists them, e.g. sdv.toolbar.next)"))
        val command = EditText(ctx).apply {
            setText((action as? HotkeyAction.Command)?.command.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        commandBox.addView(command)
        form.addView(commandBox)

        val keysBox = section()
        val keysCaption = caption("Key")
        keysBox.addView(keysCaption)
        val initialKeys = when (action) {
            is HotkeyAction.Keys -> action.keys
            is HotkeyAction.Command -> action.fallback?.keys.orEmpty()
            else -> emptyList()
        }
        val modKeys = MODS.map { it.second }.toSet()
        val mainKey = initialKeys.lastOrNull { it !in modKeys } ?: initialKeys.singleOrNull()
        val checks = MODS.map { (name, key) ->
            CheckBox(ctx).apply {
                text = name
                isChecked = key in initialKeys && key != mainKey
                minHeight = CompanionCards.dp(ctx, 48)
            }
        }
        keysBox.addView(LinearLayout(ctx).apply { checks.forEach { addView(it, LinearLayout.LayoutParams(0, wrap, 1f)) } })
        val keyNames = listOf(NO_KEY) + HotkeyKeys.NAMES.map { it.first }
        val key = spinner(keyNames, mainKey?.let { keyNames.indexOf(HotkeyKeys.name(it)) } ?: 0)
        keysBox.addView(key)
        form.addView(keysBox)

        val mouseBox = section()
        val mouse = spinner(MouseButton.entries.map { it.label }, (action as? HotkeyAction.Mouse)?.button?.ordinal ?: 1)
        mouseBox.addView(mouse)
        form.addView(mouseBox)

        val padBox = section()
        val padButton = spinner(PadButton.entries.map { it.label }, (action as? HotkeyAction.Pad)?.button?.ordinal ?: 0)
        padBox.addView(padButton)
        padBox.addView(caption("Sets the button on the game's controller; your real controller keeps working."))
        form.addView(padBox)

        // How it behaves.
        val modeBox = section()
        modeBox.addView(caption("When touched"))
        val modes = HotkeyMode.entries
        val modeNames = listOf("Tap: one short press", "Hold: pressed while touched", "Toggle: tap on, tap off")
        val mode = spinner(modeNames, modes.indexOf(hotkey?.mode ?: HotkeyMode.TAP))
        modeBox.addView(mode)
        form.addView(modeBox)

        form.addView(caption("Width"))
        val width = spinner((1..columns).map { if (it == 1) "1 cell" else "$it cells" }, (hotkey?.span ?: 1) - 1)
        form.addView(width)
        form.addView(caption("Position"))
        val place = spinner((1..positions).map { "$it of $positions" }, position - 1)
        form.addView(place)
        val problemText = TextView(ctx).apply {
            textSize = 14f
            setTextColor(android.graphics.Color.rgb(0xF6, 0x7B, 0x7B))
            setPadding(0, CompanionCards.dp(ctx, 10), 0, 0)
            visibility = View.GONE
        }
        form.addView(problemText)

        fun showSections() {
            val k = Kind.entries[kind.selectedItemPosition]
            commandBox.visibility = if (k == Kind.COMMAND) View.VISIBLE else View.GONE
            keysBox.visibility = if (k == Kind.KEYS || k == Kind.COMMAND) View.VISIBLE else View.GONE
            keysCaption.text = if (k == Kind.COMMAND) "Without the mod, press (optional)" else "Key"
            mouseBox.visibility = if (k == Kind.MOUSE) View.VISIBLE else View.GONE
            padBox.visibility = if (k == Kind.PAD) View.VISIBLE else View.GONE
            // A wheel notch or a command is always one tap.
            val wheel = k == Kind.MOUSE && MouseButton.entries[mouse.selectedItemPosition].wheel
            modeBox.visibility = if (wheel || k == Kind.COMMAND) View.GONE else View.VISIBLE
        }
        val onPick = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) = showSections()

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        kind.onItemSelectedListener = onPick
        mouse.onItemSelectedListener = onPick
        showSections()

        fun pickedKeys(): List<XKeycode> {
            val chosen = keyNames[key.selectedItemPosition].takeIf { it != NO_KEY }?.let { HotkeyKeys.key(it) }
            val mods = MODS.filterIndexed { i, _ -> checks[i].isChecked }.map { it.second }
            return (mods + listOfNotNull(chosen)).distinct().take(HotkeyKeys.MAX_KEYS)
        }

        /** The edited hotkey, or why it cannot be saved. */
        fun result(): Pair<Hotkey?, String?> {
            val k = Kind.entries[kind.selectedItemPosition]
            val keys = pickedKeys()
            val act: HotkeyAction = when (k) {
                Kind.KEYS -> if (keys.isEmpty()) return null to "Pick a key." else HotkeyAction.Keys(keys)
                Kind.MOUSE -> HotkeyAction.Mouse(MouseButton.entries[mouse.selectedItemPosition])
                Kind.PAD -> HotkeyAction.Pad(PadButton.entries[padButton.selectedItemPosition])
                Kind.COMMAND -> {
                    val parsed = HotkeyAction.fromJson(
                        org.json.JSONObject().put("command", command.text.toString().trim()).apply {
                            if (keys.isNotEmpty()) put("keys", HotkeyKeys.format(keys))
                        },
                    )
                    parsed as? HotkeyAction.Command ?: return null to "A command looks like game.name (letters, digits, dots)."
                }
            }
            return Hotkey(
                label = label.text.toString().trim().take(Hotkey.MAX_LABEL),
                action = act,
                icon = icons[icon.selectedItemPosition],
                mode = modes[mode.selectedItemPosition],
                span = width.selectedItemPosition + 1,
            ) to null
        }

        val builder = AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (hotkey == null) "New button" else "Edit button")
            .setView(ScrollView(ctx).apply { addView(form) })
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
        if (hotkey != null) builder.setNeutralButton("Delete") { _, _ -> done(Result.Deleted) }
        val dialog = builder.create()
        dialog.show()
        // Save checks the form first and stays open when something is missing.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val (h, problem) = result()
            if (h == null) {
                problemText.text = problem
                problemText.visibility = View.VISIBLE
            } else {
                done(Result.Saved(h, place.selectedItemPosition + 1))
                dialog.dismiss()
            }
        }
    }
}
