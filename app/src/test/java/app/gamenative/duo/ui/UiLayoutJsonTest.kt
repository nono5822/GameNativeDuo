package app.gamenative.duo.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UiLayoutJsonTest {
    @Test
    fun backgroundAndWidgetsSurviveARoundTrip() {
        val layout = UiLayout(
            game = "STEAM_413150",
            rules = mapOf("hud/clock" to UiRule(UiScreen.TOP, 1.2f, 0.1f, 0f)),
            background = UiBackground(UiBackgroundMode.BLUR, color = 0xFF12284E.toInt(), dim = 0.3f, blur = 0.75f, image = true),
            widgets = listOf(UiWidget(UiWidgetType.FPS, 0.03f, 0.04f), UiWidget(UiWidgetType.BATTERY, 0.8f, 0.9f, 1.5f)),
        )
        val back = UiLayout.fromJson(JSONObject(layout.toJson().toString()), "x")
        assertEquals(layout, back)
    }

    @Test
    fun unknownKeysSurviveASave() {
        val json = JSONObject("""{"format":"gamenative-duo/ui-layout","version":1,"game":"g","elements":{},"hotkeys":{"columns":3,"buttons":[{"label":"Map","keys":"M"}]}}""")
        val back = UiLayout.fromJson(json, "g").copy(regions = listOf(UiRegion.create(emptyList()))).toJson()
        assertEquals(3, back.getJSONObject("hotkeys").getInt("columns"))
        assertEquals("M", back.getJSONObject("hotkeys").getJSONArray("buttons").getJSONObject(0).getString("keys"))
        assertTrue(back.has("regions"))
    }

    @Test
    fun defaultsAreLeftOutOfTheFile() {
        val json = UiLayout("g").toJson()
        assertFalse(json.has("background"))
        assertFalse(json.has("widgets"))
    }

    @Test
    fun badValuesAreClampedOrDropped() {
        val json = JSONObject(
            """{"game":"g","background":{"mode":"nonsense","color":"#zzzzzz","dim":7,"blur":-1},
               "widgets":[{"type":"fps","x":5,"y":-2,"scale":99},{"type":"unknown","x":0,"y":0}]}""",
        )
        val layout = UiLayout.fromJson(json, "g")
        assertEquals(UiBackgroundMode.GAME, layout.background.mode)
        assertEquals(UiBackground.DEFAULT_COLOR, layout.background.color)
        assertEquals(0.9f, layout.background.dim)
        assertEquals(0f, layout.background.blur)
        assertEquals(listOf(UiWidget(UiWidgetType.FPS, 1f, 0f, UiWidget.SCALE_MAX)), layout.widgets)
    }

    @Test
    fun colorIsWrittenAsHex() {
        val json = UiBackground(UiBackgroundMode.COLOR, color = 0xFF1C4078.toInt()).toJson()
        assertEquals("#1C4078", json.getString("color"))
        assertTrue(UiBackground.fromJson(json).color == 0xFF1C4078.toInt())
    }
}
