package app.gamenative.duo.ui

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UiLayoutLibraryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val game = "STEAM_367520"
    private val dir: File get() = tmp.root
    private val activeFile: File get() = File(dir, "$game.json")

    private fun layout(x: Float) = UiLayout(game, rules = mapOf("hud" to UiRule(x = x))).toJson().toString()

    private fun library() = UiLayoutLibrary(dir, game)

    @Test
    fun `a game with its single layout shows it as My layout`() {
        activeFile.writeText(layout(0.1f))
        val lib = library()
        assertEquals(listOf("My layout"), lib.entries.map { it.name })
        assertEquals(layout(0.1f), lib.text(lib.activeId))
        // Looking changes nothing on disk.
        assertEquals(listOf("$game.json"), dir.list()!!.toList())
    }

    @Test
    fun `a game with no layout has one empty layout`() {
        val lib = library()
        assertEquals(1, lib.entries.size)
        assertNull(lib.text(lib.activeId))
    }

    @Test
    fun `duplicate keeps a copy and switching swaps the game's file`() {
        activeFile.writeText(layout(0.1f))
        val lib = library()
        val copy = lib.duplicate(lib.activeId, "Experiment")
        assertEquals("Experiment", copy.name)
        // The player edits the active layout, then switches to the copy and back.
        activeFile.writeText(layout(0.5f))
        lib.activate(copy.id)
        assertEquals(layout(0.1f), activeFile.readText())
        lib.activate("1")
        assertEquals(layout(0.5f), activeFile.readText())
        assertEquals(layout(0.1f), lib.text(copy.id))
    }

    @Test
    fun `the list survives a reload`() {
        activeFile.writeText(layout(0.1f))
        val lib = library()
        val copy = lib.duplicate(lib.activeId)
        lib.rename(copy.id, "Docked")
        lib.activate(copy.id)
        val again = library()
        assertEquals(listOf("My layout", "Docked"), again.entries.map { it.name })
        assertEquals(copy.id, again.activeId)
    }

    @Test
    fun `switching to an empty layout removes the game's file, and back restores it`() {
        val lib = library()
        val empty = lib.activeId
        val mine = lib.add("Mine", layout(0.3f))
        assertEquals(mine.id, lib.activeId)
        lib.activate(empty)
        assertFalse(activeFile.exists())
        lib.activate(mine.id)
        assertEquals(layout(0.3f), activeFile.readText())
    }

    @Test
    fun `names stay unique`() {
        val lib = library()
        val a = lib.duplicate(lib.activeId)
        val b = lib.duplicate(lib.activeId)
        assertEquals(listOf("My layout", "My layout (2)", "My layout (3)"), lib.entries.map { it.name })
        lib.rename(b.id, "My layout")
        assertEquals("My layout (3)", lib.entries.first { it.id == b.id }.name)
        lib.rename(a.id, "  ")
        assertEquals("My layout (2)", lib.entries.first { it.id == a.id }.name)
    }

    @Test
    fun `deleting the active layout makes another one active`() {
        activeFile.writeText(layout(0.1f))
        val lib = library()
        val copy = lib.duplicate(lib.activeId)
        File(dir, "$game.layout-${copy.id}.json").writeText(layout(0.9f))
        lib.delete("1")
        assertEquals(copy.id, lib.activeId)
        assertEquals(layout(0.9f), activeFile.readText())
        assertEquals(1, lib.entries.size)
    }

    @Test
    fun `deleting the last layout empties it`() {
        activeFile.writeText(layout(0.1f))
        val lib = library()
        lib.delete(lib.activeId)
        assertEquals(1, lib.entries.size)
        assertFalse(activeFile.exists())
    }

    @Test
    fun `a community layout is added beside the player's own, and updated in place`() {
        activeFile.writeText(layout(0.1f))
        val lib = library()
        val pkg = lib.add("Compact HUD", layout(0.7f), source = "alice.compact-hud")
        assertEquals(listOf("My layout", "Compact HUD"), lib.entries.map { it.name })
        assertEquals(layout(0.7f), activeFile.readText())
        assertEquals(layout(0.1f), lib.text("1"))
        // A newer version of the same package replaces its layout.
        lib.activate("1")
        val updated = lib.add("Compact HUD", layout(0.8f), source = "alice.compact-hud")
        assertEquals(pkg.id, updated.id)
        assertEquals(2, lib.entries.size)
        assertEquals(layout(0.8f), activeFile.readText())
        // Removing the package leaves the player's own layout active.
        lib.removeSource("alice.compact-hud")
        assertEquals(listOf("My layout"), lib.entries.map { it.name })
        assertEquals(layout(0.1f), activeFile.readText())
    }

    @Test
    fun `import refuses what is not a layout`() {
        val lib = library()
        assertThrows(IOException::class.java) { lib.add("x", "not json") }
        assertThrows(IOException::class.java) { lib.add("x", """{"format":"something-else"}""") }
        assertEquals(1, lib.entries.size)
        assertTrue(dir.list().orEmpty().none { it.endsWith(".layouts.json") })
    }

    @Test
    fun `a broken index falls back to the single layout`() {
        activeFile.writeText(layout(0.1f))
        File(dir, "$game.layouts.json").writeText("{ broken")
        val lib = library()
        assertEquals(1, lib.entries.size)
        assertEquals(layout(0.1f), lib.text(lib.activeId))
    }
}
