package app.gamenative.duo.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

/**
 * How a dual-screen device lays out its screens. Devkit code says "top" and "bottom" and asks the profile
 * which Android display that is, because devices disagree: the AYN Thor reports its top screen as the default
 * display, while others (AYANEO Pocket DS, Retroid Dual Screen) are the other way round.
 */
data class DeviceProfile(
    val id: String,
    val displayName: String,
    /** True when the game's (top) screen is Android's default display and the bottom screen is a secondary one. */
    val topIsDefault: Boolean,
    private val matcher: (manufacturer: String, model: String) -> Boolean,
) {
    fun matches(manufacturer: String, model: String): Boolean = matcher(manufacturer, model)

    /**
     * The bottom screen, or null when it is missing, switched off, or this layout is not supported yet.
     * Only the "top is default" layout is supported so far: the game always renders on the default display.
     */
    fun findBottomDisplay(context: Context): Display? {
        if (!topIsDefault) return null
        val displayManager = context.getSystemService(DisplayManager::class.java) ?: return null
        return displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { display ->
                display.displayId != Display.DEFAULT_DISPLAY &&
                    display.name != "HiddenDisplay" &&
                    display.state != Display.STATE_OFF
            }
    }

    companion object {
        /** Real data in `duo/profiles/`. Thor: top #0 "Built-in Screen" 1080x1920, bottom #4 "Screen-2" 1080x1240, both 60/120 Hz. */
        val AYN_THOR = DeviceProfile(
            id = "ayn-thor",
            displayName = "AYN Thor",
            topIsDefault = true,
        ) { manufacturer, model -> manufacturer.equals("AYN", ignoreCase = true) && model.contains("Thor", ignoreCase = true) }

        /** Unknown devices: assume the game is on the default display and any presentation display is the bottom screen. */
        val GENERIC = DeviceProfile(
            id = "generic",
            displayName = "Generic (default display on top)",
            topIsDefault = true,
        ) { _, _ -> true }

        private val KNOWN = listOf(AYN_THOR)

        fun detect(manufacturer: String = Build.MANUFACTURER, model: String = Build.MODEL): DeviceProfile =
            KNOWN.firstOrNull { it.matches(manufacturer, model) } ?: GENERIC
    }
}
