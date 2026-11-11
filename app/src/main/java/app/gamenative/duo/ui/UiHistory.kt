package app.gamenative.duo.ui

/**
 * Undo and redo for one editing session of a UI layout: the layouts before each saved change, newest last. Kept by
 * [UiLayoutController], so the on-device editor, the PC editor and GameNative's menu share one history. Not saved:
 * a new session starts empty.
 *
 * Changes to the same thing ([record]'s `key`) that follow each other within [MERGE_MS] are one step: a slider or a
 * held + button sends many saved values, and one undo should take all of them back.
 */
class UiHistory(private val limit: Int = STEPS) {
    private val undo = ArrayDeque<UiLayout>()
    private val redo = ArrayDeque<UiLayout>()
    private var lastKey: String? = null
    private var lastAt = 0L

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    /** A saved change from [before] to [after]. Nothing is kept when they are equal. */
    fun record(before: UiLayout, after: UiLayout, key: String, now: Long) {
        if (before == after) return
        val merge = key == lastKey && now - lastAt in 0 until MERGE_MS && undo.isNotEmpty() && redo.isEmpty()
        lastKey = key
        lastAt = now
        redo.clear()
        if (merge) return
        undo.addLast(before)
        while (undo.size > limit) undo.removeFirst()
    }

    /** The layout to go back to from [current], or null when there is nothing to undo. */
    fun undo(current: UiLayout): UiLayout? {
        val target = undo.removeLastOrNull() ?: return null
        redo.addLast(current)
        lastKey = null
        return target
    }

    /** The layout an undo took away, or null when there is nothing to redo. */
    fun redo(current: UiLayout): UiLayout? {
        val target = redo.removeLastOrNull() ?: return null
        undo.addLast(current)
        lastKey = null
        return target
    }

    companion object {
        const val STEPS = 30
        const val MERGE_MS = 700L
    }
}
