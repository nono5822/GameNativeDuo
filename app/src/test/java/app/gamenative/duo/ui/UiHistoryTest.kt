package app.gamenative.duo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiHistoryTest {
    private fun layout(n: Int) = UiLayout("game", rules = mapOf("e" to UiRule(x = n / 100f)))

    @Test
    fun `undo goes back one saved change and redo brings it again`() {
        val h = UiHistory()
        h.record(layout(0), layout(1), "rule:e", 0)
        h.record(layout(1), layout(2), "rule:e", 5_000)

        assertEquals(layout(1), h.undo(layout(2)))
        assertEquals(layout(0), h.undo(layout(1)))
        assertNull(h.undo(layout(0)))
        assertEquals(layout(1), h.redo(layout(0)))
        assertEquals(layout(2), h.redo(layout(1)))
        assertNull(h.redo(layout(2)))
    }

    @Test
    fun `a change that changes nothing is not a step`() {
        val h = UiHistory()
        h.record(layout(1), layout(1), "rule:e", 0)
        assertFalse(h.canUndo)
    }

    @Test
    fun `a new change after an undo drops what could be redone`() {
        val h = UiHistory()
        h.record(layout(0), layout(1), "rule:e", 0)
        h.undo(layout(1))
        assertTrue(h.canRedo)
        h.record(layout(0), layout(5), "rule:e", 10_000)
        assertFalse(h.canRedo)
        assertEquals(layout(0), h.undo(layout(5)))
    }

    @Test
    fun `quick changes to the same thing are one step`() {
        val h = UiHistory()
        // A slider: many saved values in a row.
        h.record(layout(0), layout(1), "background", 1_000)
        h.record(layout(1), layout(2), "background", 1_200)
        h.record(layout(2), layout(3), "background", 1_500)
        assertEquals(layout(0), h.undo(layout(3)))
        assertFalse(h.canUndo)
    }

    @Test
    fun `quick changes to different things, or slow ones to the same, stay apart`() {
        val h = UiHistory()
        h.record(layout(0), layout(1), "rule:a", 1_000)
        h.record(layout(1), layout(2), "rule:b", 1_100)
        h.record(layout(2), layout(3), "rule:b", 1_100 + UiHistory.MERGE_MS)
        assertEquals(layout(2), h.undo(layout(3)))
        assertEquals(layout(1), h.undo(layout(2)))
        assertEquals(layout(0), h.undo(layout(1)))
    }

    @Test
    fun `a change right after an undo is its own step`() {
        val h = UiHistory()
        h.record(layout(0), layout(1), "rule:e", 1_000)
        h.record(layout(1), layout(2), "rule:e", 5_000)
        h.undo(layout(2))
        h.record(layout(1), layout(7), "rule:e", 5_100)
        assertEquals(layout(1), h.undo(layout(7)))
        assertEquals(layout(0), h.undo(layout(1)))
    }

    @Test
    fun `keeps the newest steps up to its limit`() {
        val h = UiHistory(limit = 20)
        for (i in 0 until 25) h.record(layout(i), layout(i + 1), "rule:e", i * 10_000L)
        var current = layout(25)
        var steps = 0
        while (true) {
            current = h.undo(current) ?: break
            steps++
        }
        assertEquals(20, steps)
        assertEquals(layout(5), current)
    }

    @Test
    fun `reset all can be undone`() {
        val h = UiHistory()
        val widget = UiWidget(UiWidgetType.entries[0], 0.1f, 0.2f)
        val edited = UiLayout("game", rules = mapOf("a" to UiRule(scale = 1.4f)), widgets = listOf(widget))
        h.record(edited, UiLayout("game"), "reset", 0)
        assertEquals(edited, h.undo(UiLayout("game")))
    }
}
