package app.gamenative.duo.ui

import app.gamenative.duo.bridge.DuoBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EditCapabilitiesTest {
    private val balatro = """
        {"engine":"balatro-love","screen":{"w":1920,"h":2160},"elements":[
         {"id":"Hand","name":"Hand","parent":-1,"screen":"bottom","x":300,"y":1300,"w":1300,"h":400,"graphic":true,
          "features":{"screens":["bottom","top"],"move":true,"scale":{"min":0.8,"max":1.6}}},
         {"id":"Menus","name":"Menus","parent":-1,"screen":"bottom","x":0,"y":0,"w":0,"h":0,"graphic":true,
          "present":false,"when":"While a menu is open","features":{"screens":["bottom","top"],"move":false,"scale":false}},
         {"id":"Hand info","name":"Hand info","parent":-1,"screen":"bottom","x":10,"y":1100,"w":400,"h":100,"graphic":true,
          "features":{"screens":["bottom","hidden"],"scale":false}},
         {"id":"GameNativeDuo/LiveMap","name":"Live map","parent":-1,"screen":"bottom","x":0,"y":1080,"w":1240,"h":930,
          "graphic":true,"screens":["bottom","hidden"]}
        ]}
    """.trimIndent()

    @Test
    fun parsesElementFeatures() {
        val list = UiElementList.parse(balatro)
        val hand = list.byId("Hand")!!
        assertTrue(hand.canMove)
        assertEquals(0.8f..1.6f, hand.scaleRange)
        assertEquals(setOf(UiScreen.BOTTOM, UiScreen.TOP), hand.screens)
        val menus = list.byId("Menus")!!
        assertFalse(menus.present)
        assertFalse(menus.canMove)
        assertFalse(menus.canScale)
        assertEquals("While a menu is open", menus.whenText)
        // Older plugin elements: `screens` at the top level, everything else allowed.
        val map = list.byId("GameNativeDuo/LiveMap")!!
        assertEquals(setOf(UiScreen.BOTTOM, UiScreen.HIDDEN), map.screens)
        assertTrue(map.canMove)
        assertTrue(map.canScale)
    }

    @Test
    fun oldElementsAllowEverything() {
        val list = UiElementList.parse(
            """{"screen":{"w":10,"h":10},"elements":[{"id":"a","parent":-1,"x":0,"y":0,"w":5,"h":5},{"id":"a/b","parent":0}]}""",
        )
        val a = list.byId("a")!!
        assertTrue(a.canMove && a.canScale && a.present && a.screens == null)
        assertTrue(a.hasChildren)
        assertFalse(list.byId("a/b")!!.hasChildren)
        assertNull(EditCapabilities.forMod(DuoBridge.CAP_ELEMENTS, "Game").limits(a))
    }

    @Test
    fun limitsNameWhatIsMissing() {
        val caps = EditCapabilities.forMod(DuoBridge.CAP_ELEMENTS or DuoBridge.CAP_BG_BASIC, "Balatro")
        val list = UiElementList.parse(balatro)
        assertEquals("Balatro's mod cannot hide Hand.", caps.limits(list.byId("Hand")!!))
        assertEquals("Balatro's mod cannot resize Hand info or put it on the top screen.", caps.limits(list.byId("Hand info")!!))
        assertEquals(
            "Menus: While a menu is open. Choose its screen now; move or resize it when it shows.",
            caps.limits(list.byId("Menus")!!),
        )
    }

    @Test
    fun editorOffWithoutAnElementList() {
        assertFalse(EditCapabilities.forMod(0, "Isaac").editable)
        assertEquals(EditCapabilities.NO_MOD, EditCapabilities.forMod(0, "Isaac").reasonOff)
        val touchOnly = EditCapabilities.forMod(DuoBridge.CAP_TOUCH, "Isaac")
        assertEquals("Isaac's mod does not let its UI be edited.", touchOnly.reasonOff)
    }

    @Test
    fun backgroundsFollowCapabilities() {
        val unity = EditCapabilities.forMod(DuoBridge.CAP_ELEMENTS or DuoBridge.CAP_BG_BASIC, "Subnautica")
        assertTrue(unity.supports(UiBackgroundMode.COLOR) && unity.supports(UiBackgroundMode.IMAGE))
        assertFalse(unity.supports(UiBackgroundMode.BLUR))
        assertEquals("Subnautica's mod cannot draw a blurred game background.", unity.backgroundReason(UiBackgroundMode.BLUR))
    }
}
