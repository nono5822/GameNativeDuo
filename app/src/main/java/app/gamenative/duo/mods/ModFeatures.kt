package app.gamenative.duo.mods

import android.content.Context
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.ui.UiBackgroundMode
import app.gamenative.duo.ui.UiElementList
import app.gamenative.duo.ui.UiScreen
import org.json.JSONObject
import timber.log.Timber

/** How much of the UI layout editor a mod supports, shown as a tag on its card (`uniform-editing.md`). */
enum class EditingLevel {
    /** Every game UI element can go top, bottom or hidden, move and resize; background color and image. */
    FULL,

    /** Anything less: the details list each element's limits. */
    PARTIAL,

    /** No mod: region view, the user picks parts of the game's frame for the bottom screen. */
    REGIONS,
}

/** One element a mod declares in `features.json`. [own]: drawn by the mod itself (a live map), not the game's UI. */
data class FeatureElement(
    val id: String,
    val name: String,
    val whenText: String,
    val screens: Set<UiScreen>?,
    val canMove: Boolean,
    val scaleRange: ClosedFloatingPointRange<Float>?,
    val own: Boolean,
) {
    val full: Boolean get() = (screens == null || screens.size == 3) && canMove && scaleRange != null
}

/**
 * What a dual-screen mod supports, from its `features.json` (contract "features.json"): bundled with the mod in the
 * APK, so the app knows it before the game runs. [elements] are the mod's fixed elements; [dynamic] means more come
 * from the running game (Unity scenes), which allow everything unless they say otherwise.
 */
data class ModFeatures(
    val engine: String,
    val touch: Boolean,
    val backgrounds: Set<UiBackgroundMode>,
    val elements: List<FeatureElement>,
    val dynamic: Boolean,
) {
    val level: EditingLevel
        get() {
            val bg = UiBackgroundMode.COLOR in backgrounds && UiBackgroundMode.IMAGE in backgrounds
            return if (bg && elements.filterNot { it.own }.all { it.full }) EditingLevel.FULL else EditingLevel.PARTIAL
        }

    companion object {
        fun parse(text: String): ModFeatures? = try {
            val o = JSONObject(text)
            val bg = o.optJSONArray("background")
            val list = o.optJSONArray("elements")
            ModFeatures(
                engine = o.optString("engine"),
                touch = o.optBoolean("touch"),
                backgrounds = (0 until (bg?.length() ?: 0)).mapNotNull { i ->
                    UiBackgroundMode.entries.firstOrNull { it.id == bg!!.optString(i) }
                }.toSet(),
                elements = (0 until (list?.length() ?: 0)).mapNotNull { i ->
                    val e = list!!.optJSONObject(i) ?: return@mapNotNull null
                    val id = e.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val features = e.optJSONObject("features")
                    FeatureElement(
                        id = id,
                        name = e.optString("name").ifEmpty { id },
                        whenText = e.optString("when"),
                        screens = UiElementList.parseScreens(features?.optJSONArray("screens")),
                        canMove = features?.optBoolean("move", true) ?: true,
                        scaleRange = UiElementList.parseScale(features?.opt("scale")),
                        own = e.optBoolean("own"),
                    )
                },
                dynamic = o.optBoolean("dynamic"),
            )
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Unreadable features.json")
            null
        }

        /** Bundled with [mod]: `assets/duo/mods/<id>/features.json`, else the shared Unity one for Unity games. */
        fun forMod(context: Context, mod: DuoGameMod): ModFeatures? {
            val paths = listOfNotNull("duo/mods/${mod.id}/features.json", UNITY.takeIf { mod.unity })
            for (path in paths) {
                val text = try {
                    context.assets.open(path).bufferedReader().use { it.readText() }
                } catch (_: java.io.IOException) {
                    continue
                }
                return parse(text)
            }
            return null
        }

        private const val UNITY = "duo/mods/shared/unity-features.json"
    }
}
