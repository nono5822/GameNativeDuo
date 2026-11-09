package app.gamenative.duo.hotkeys

import app.gamenative.duo.ui.UiLayout
import com.winlator.xserver.XKeycode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HotkeysTest {
    @Test
    fun parsesCombos() {
        assertEquals(listOf(XKeycode.KEY_CTRL_L, XKeycode.KEY_TAB), HotkeyKeys.parse("Ctrl+Tab"))
        assertEquals(listOf(XKeycode.KEY_CTRL_L, XKeycode.KEY_SHIFT_L, XKeycode.KEY_S), HotkeyKeys.parse("ctrl+shift+s"))
        assertEquals(listOf(XKeycode.KEY_KP_ADD), HotkeyKeys.parse("Num+"))
        assertEquals(listOf(XKeycode.KEY_CTRL_L, XKeycode.KEY_KP_ADD), HotkeyKeys.parse("Ctrl+Num+"))
        assertEquals(listOf(XKeycode.KEY_CTRL_L, XKeycode.KEY_EQUAL), HotkeyKeys.parse("Ctrl++"))
        assertEquals(listOf(XKeycode.KEY_ESC), HotkeyKeys.parse(" escape "))
    }

    @Test
    fun rejectsUnknownEmptyAndTooLongCombos() {
        assertNull(HotkeyKeys.parse(""))
        assertNull(HotkeyKeys.parse("   "))
        assertNull(HotkeyKeys.parse("Ctrl+Nope"))
        assertNull(HotkeyKeys.parse("A+B+C+D+E"))
    }

    @Test
    fun formatsWhatItParses() {
        listOf("Ctrl+Tab", "F5", "Shift", "Num+", "PageUp", "Ctrl+=", "Alt+F4").forEach {
            assertEquals(it, HotkeyKeys.format(HotkeyKeys.parse(it)!!))
        }
    }

    @Test
    fun gridSurvivesARoundTrip() {
        val grid = HotkeyGrid(
            buttons = listOf(
                Hotkey("Map", HotkeyAction.Keys(listOf(XKeycode.KEY_M)), icon = "🗺"),
                Hotkey("Aim", HotkeyAction.Mouse(MouseButton.RIGHT), mode = HotkeyMode.HOLD),
                Hotkey("Pause", HotkeyAction.Pad(PadButton.START), span = 2),
                Hotkey("Next", HotkeyAction.Command("sdv.toolbar.next", HotkeyAction.Keys(listOf(XKeycode.KEY_TAB)))),
                Hotkey("", HotkeyAction.Command("hk.map.zoom"), mode = HotkeyMode.TOGGLE),
            ),
            columns = 4,
            size = HotkeySize.LARGE,
            haptics = false,
            preset = HotkeyPreset.STARDEW,
        )
        assertEquals(grid, HotkeyGrid.fromJson(JSONObject(grid.toJson().toString())))
    }

    @Test
    fun badButtonsAreDroppedAndValuesClamped() {
        val json = JSONObject(
            """{"columns":99,"size":"huge","buttons":[{"label":"x"},{"keys":"Nope"},{"mouse":"side"},{"pad":"z"},
               {"command":"Bad Command!"},{"keys":"Esc","span":50,"mode":"sideways"},{"label":"${"x".repeat(40)}","keys":"A"}]}""",
        )
        val grid = HotkeyGrid.fromJson(json)
        assertEquals(2, grid.buttons.size)
        assertEquals(HotkeyAction.Keys(listOf(XKeycode.KEY_ESC)), grid.buttons[0].action)
        assertEquals(HotkeyGrid.MAX_COLUMNS, grid.buttons[0].span)
        assertEquals(HotkeyMode.TAP, grid.buttons[0].mode)
        assertEquals(Hotkey.MAX_LABEL, grid.buttons[1].label.length)
        assertEquals(HotkeyGrid.MAX_COLUMNS, grid.columns)
        assertEquals(HotkeySize.MEDIUM, grid.size)
        assertTrue(grid.haptics)
        assertNull(grid.preset)
    }

    @Test
    fun wheelIsAlwaysATap() {
        assertEquals(HotkeyMode.TAP, Hotkey("Up", HotkeyAction.Mouse(MouseButton.WHEEL_UP), mode = HotkeyMode.HOLD).effectiveMode)
        assertEquals(HotkeyMode.HOLD, Hotkey("Aim", HotkeyAction.Mouse(MouseButton.RIGHT), mode = HotkeyMode.HOLD).effectiveMode)
    }

    @Test
    fun picksAPresetPerGame() {
        assertEquals(HotkeyPreset.STARDEW, HotkeyPreset.forGame("STEAM_413150", false, null))
        assertEquals(HotkeyPreset.UNITY, HotkeyPreset.forGame("STEAM_1", true, null))
        assertEquals(HotkeyPreset.UNITY, HotkeyPreset.forGame("STEAM_1", false, "unity-ugui"))
        assertEquals(HotkeyPreset.GENERIC, HotkeyPreset.forGame("STEAM_1", false, "balatro-love"))
        assertEquals(HotkeyPreset.GENERIC, HotkeyPreset.forGame("STEAM_1", false, null))
    }

    @Test
    fun presetsAreValidAndSurviveARoundTrip() {
        HotkeyPreset.entries.forEach { p ->
            val grid = p.grid
            assertTrue(p.id, grid.buttons.isNotEmpty() && grid.buttons.size <= HotkeyGrid.MAX_BUTTONS)
            assertEquals(p.id, grid, HotkeyGrid.fromJson(JSONObject(grid.toJson().toString())))
        }
    }

    @Test
    fun layoutKeepsHotkeysOnlyWhenTheUserHasThem() {
        assertTrue(!UiLayout("g").toJson().has("hotkeys"))
        val layout = UiLayout("g", hotkeys = HotkeyPreset.GENERIC.grid.copy(columns = 4))
        assertEquals(layout, UiLayout.fromJson(JSONObject(layout.toJson().toString()), "g"))
    }
}
