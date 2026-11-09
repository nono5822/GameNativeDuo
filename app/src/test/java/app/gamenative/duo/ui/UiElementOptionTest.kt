package app.gamenative.duo.ui

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Element options: what a mod declares in elements.json and what the user's choice looks like in ui.json. */
@RunWith(RobolectricTestRunner::class)
class UiElementOptionTest {
    private val zoom = UiElementOption("zoom", "Start zoom", listOf("knight" to "Knight", "area" to "Area", "world" to "Whole map"))

    @Test
    fun optionsSurviveARoundTrip() {
        val layout = UiLayout(
            game = "STEAM_367520",
            rules = mapOf("GameNativeDuo/LiveMap" to UiRule(UiScreen.BOTTOM, 0.6f, 0.18f, 0.1f, mapOf("zoom" to "world", "dim" to "strong"))),
        )
        val back = UiLayout.fromJson(JSONObject(layout.toJson().toString()), "x")
        assertEquals(layout, back)
    }

    @Test
    fun noOptionsLeavesNoKey() {
        assertFalse(UiRule(UiScreen.TOP).toJson().has("options"))
        assertTrue(UiRule().isNeutral)
        assertFalse(UiRule(options = mapOf("zoom" to "area")).isNeutral)
    }

    @Test
    fun optionValuesOfAnyJsonTypeAreKeptAsText() {
        val rule = UiRule.fromJson(JSONObject("""{"options":{"a":"x","b":2,"c":true,"d":{"nested":1}}}"""))
        assertEquals(mapOf("a" to "x", "b" to "2", "c" to "true"), rule.options)
    }

    @Test
    fun valueFallsBackToTheDefault() {
        assertEquals("knight", zoom.value(UiRule()))
        assertEquals("area", zoom.value(UiRule(options = mapOf("zoom" to "area"))))
        assertEquals("knight", zoom.value(UiRule(options = mapOf("zoom" to "gone-in-a-new-version"))))
    }

    @Test
    fun nextWrapsAround() {
        assertEquals("area", zoom.next("knight"))
        assertEquals("knight", zoom.next("world"))
        assertEquals("knight", zoom.next("unknown"))
    }

    @Test
    fun withOptionRemovesAValueSetToNull() {
        val rule = UiRule().withOption("zoom", "area")
        assertEquals(mapOf("zoom" to "area"), rule.options)
        assertTrue(rule.withOption("zoom", null).isNeutral)
    }

    @Test
    fun optionsAndScreensAreReadFromTheElementList() {
        val file = File.createTempFile("elements", ".json")
        file.writeText(
            """{"engine":"unity-ugui","screen":{"w":1920,"h":2160},"elements":[
              {"id":"HUD/Geo","name":"Geo","parent":-1,"screen":"bottom","x":0,"y":0,"w":10,"h":10,"graphic":true},
              {"id":"GameNativeDuo/LiveMap","name":"Live map","parent":-1,"screen":"bottom","x":340,"y":1230,"w":1240,"h":930,"graphic":true,
               "screens":["bottom","hidden"],
               "options":[{"id":"zoom","label":"Start zoom","values":[["knight","Knight"],["area","Area"],["world","Whole map"]]},
                          {"id":"broken"},{"id":"empty","values":[]}]}]}""",
        )
        try {
            val list = UiElementList.parse(file)
            val geo = list.byId("HUD/Geo")!!
            assertNull(geo.screens)
            assertTrue(geo.allows(UiScreen.TOP))
            val map = list.byId("GameNativeDuo/LiveMap")!!
            assertEquals(setOf(UiScreen.BOTTOM, UiScreen.HIDDEN), map.screens)
            assertFalse(map.allows(UiScreen.TOP))
            assertEquals(listOf(zoom), map.options)
        } finally {
            file.delete()
        }
    }
}
