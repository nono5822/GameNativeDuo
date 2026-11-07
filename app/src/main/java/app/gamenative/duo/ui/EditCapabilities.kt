package app.gamenative.duo.ui

import app.gamenative.duo.bridge.DuoBridge

/** Where the UI layout editor's elements come from. */
enum class EditSource {
    /** The game's dual-screen mod lists its UI (`elements.json`). */
    MOD,

    /** No mod: the user's own regions of the frame (uniform-editing.md, U3). */
    REGIONS,

    /** Nothing to edit. */
    NONE,
}

/**
 * What the UI layout editor may offer in this session, and why not when it may not. Every editor surface (the bottom
 * screen's editor, the PC editor, GameNative's in-game menu, the two-finger menu) reads this instead of checking
 * bridge capabilities itself, so they all offer the same things and say the same reasons (`uniform-editing.md`).
 *
 * Per-element limits come from the element itself ([UiElement.canMove], [UiElement.scaleRange], [UiElement.screens],
 * [UiElement.present]); [limits] turns them into one sentence.
 */
class EditCapabilities(
    val source: EditSource,
    /** Why the editor is off (null when it is on). */
    val reasonOff: String?,
    /** Who supplies the elements, for messages: "Balatro's mod". */
    val owner: String,
    private val backgrounds: Set<UiBackgroundMode>,
) {
    val editable: Boolean get() = reasonOff == null

    fun supports(mode: UiBackgroundMode): Boolean = mode in backgrounds

    /** Why [mode] is greyed out, or null when it is available. */
    fun backgroundReason(mode: UiBackgroundMode): String? =
        if (supports(mode)) null else "$owner cannot draw a ${mode.label.lowercase()} background."

    /**
     * One sentence on what [e] does not allow, or null when it allows everything. Absent elements explain when they
     * appear; the rest name what the mod does not support ("Balatro's mod cannot move or resize Menus.").
     */
    fun limits(e: UiElement): String? {
        val name = e.name.ifEmpty { e.id.substringAfterLast('/') }
        if (!e.present) {
            val whenText = e.whenText.ifEmpty { "Not on screen right now" }.trimEnd('.')
            return "$name: $whenText. Choose its screen now; move or resize it when it shows."
        }
        val screens = e.screens
        val verbs = buildList {
            if (!e.canMove) add("move")
            if (!e.canScale) add("resize")
            if (screens != null && UiScreen.HIDDEN !in screens) add("hide")
        }
        val places = buildList {
            if (screens != null && UiScreen.TOP !in screens) add("top")
            if (screens != null && UiScreen.BOTTOM !in screens) add("bottom")
        }
        val place = places.joinToString(" or ")
        return when {
            verbs.isEmpty() && places.isEmpty() -> null
            verbs.isEmpty() -> "$owner cannot put $name on the $place screen."
            places.isEmpty() -> "$owner cannot ${join(verbs)} $name."
            else -> "$owner cannot ${join(verbs)} $name or put it on the $place screen."
        }
    }

    companion object {
        /** Nothing to edit, with [reason]. */
        fun off(reason: String, owner: String = "This game"): EditCapabilities = EditCapabilities(EditSource.NONE, reason, owner, emptySet())

        /**
         * From the bridge: [seenCapabilities] are the capabilities of the mod that said hello this session (0 for
         * none). Seen, not "lately": GameNative's own menu pauses the game and with it the mod's hellos.
         */
        fun forMod(seenCapabilities: Int, gameName: String): EditCapabilities {
            val owner = if (gameName.isBlank()) "This game's mod" else "$gameName's mod"
            if (seenCapabilities and DuoBridge.CAP_ELEMENTS == 0) {
                val reason = if (seenCapabilities == 0) NO_MOD else "$owner does not let its UI be edited."
                return off(reason, owner)
            }
            val backgrounds = buildSet {
                if (seenCapabilities and DuoBridge.CAP_BG_GAME != 0) add(UiBackgroundMode.GAME)
                if (seenCapabilities and DuoBridge.CAP_BG_BLUR != 0) add(UiBackgroundMode.BLUR)
                if (seenCapabilities and DuoBridge.CAP_BG_BASIC != 0) {
                    add(UiBackgroundMode.COLOR)
                    add(UiBackgroundMode.IMAGE)
                }
            }
            return EditCapabilities(EditSource.MOD, null, owner, backgrounds)
        }

        const val NO_MOD = "Needs this game's GameNative Duo mod, running."

        private fun join(items: List<String>): String = when (items.size) {
            1 -> items[0]
            2 -> "${items[0]} or ${items[1]}"
            else -> items.dropLast(1).joinToString(", ") + " or " + items.last()
        }
    }
}
