package app.gamenative.duo.hotkeys

import app.gamenative.duo.ui.UiLayout
import app.gamenative.duo.ui.UiLayoutController

/**
 * Where the companion's hotkeys card reads and keeps the user's hotkeys: the session's UI layout (one owner per
 * session, so the layout editor and the hotkeys editor never overwrite each other's saves). Until the user changes
 * anything, the game's preset applies and nothing is written. Main thread.
 */
class HotkeySource(private val controller: UiLayoutController, private val presetFor: () -> HotkeyPreset) {
    /** The preset this game starts from (Stardew, Unity, any PC game). */
    val preset: HotkeyPreset get() = presetFor()

    /** True when the user has their own buttons (else [grid] is the preset's). */
    val custom: Boolean get() = controller.layout.hotkeys != null

    val grid: HotkeyGrid get() = controller.layout.hotkeys ?: preset.grid

    /** Stores [grid] as the user's own; null goes back to the preset. */
    fun save(grid: HotkeyGrid?) = controller.setHotkeys(grid)

    /** Calls [onChange] whenever the layout changes (the PC editor, a community layout); returns how to stop. */
    fun observe(onChange: () -> Unit): () -> Unit {
        var last = controller.layout.hotkeys
        val listener: (UiLayout) -> Unit = { layout ->
            if (layout.hotkeys != last) {
                last = layout.hotkeys
                onChange()
            }
        }
        controller.listeners += listener
        return { controller.listeners -= listener }
    }
}
