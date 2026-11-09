package app.gamenative.duo.mods

/**
 * Unity's standalone player takes `-screen-width W -screen-height H` on its command line and keeps them as its
 * resolution from then on, so a Unity game starts at the tall split window without the user picking it in the game's
 * menu once. Added to the container's launch arguments (`Container.execArgs`) when Dual screen turns on and taken out
 * again when it turns off; only our own pair is ever removed, whatever else the user put there.
 */
object UnityScreenArgs {
    private val PAIR = Regex("""(^|\s)-screen-width\s+\d+\s+-screen-height\s+\d+(?=\s|$)""")

    fun of(width: Int, height: Int) = "-screen-width $width -screen-height $height"

    /** [existing] with our pair for [width]x[height] (an older pair of ours is replaced). */
    fun add(existing: String, width: Int, height: Int): String = join(remove(existing), of(width, height))

    /** [existing] without any `-screen-width N -screen-height N` pair. */
    fun remove(existing: String): String = PAIR.replace(existing, " ").trim().replace(Regex("""\s{2,}"""), " ")

    /** The size of our pair in [args], or null. */
    fun parse(args: String): Pair<Int, Int>? = PAIR.find(args)?.value?.trim()?.split(Regex("""\s+"""))?.let {
        val w = it.getOrNull(1)?.toIntOrNull() ?: return null
        val h = it.getOrNull(3)?.toIntOrNull() ?: return null
        w to h
    }

    /** "1920x2160" -> (1920, 2160). */
    fun size(screenSize: String?): Pair<Int, Int>? {
        val parts = screenSize?.split('x') ?: return null
        val w = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val h = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        return if (parts.size == 2 && w > 0 && h > 0) w to h else null
    }

    private fun join(a: String, b: String) = listOf(a.trim(), b).filter { it.isNotEmpty() }.joinToString(" ")
}
