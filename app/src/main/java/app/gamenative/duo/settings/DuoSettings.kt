package app.gamenative.duo.settings

import android.content.Context
import app.gamenative.duo.DualScreenHost
import java.io.File
import org.json.JSONObject
import timber.log.Timber

/**
 * GameNative Duo's own per-container settings. Upstream's `Container` is never touched: these live in
 * `files/duo/settings/`, one JSON file per container id plus `defaults.json` for the global default
 * container config. A container without its own file uses the defaults.
 *
 * To add a setting: add a field with a default here, read and write it in [toJson]/[fromJson], and add
 * a row to `DuoSettingsTabContent`.
 */
data class DuoContainerSettings(
    /** Show the devkit panel on the bottom screen while this container's game runs. */
    val bottomPanelEnabled: Boolean = true,
    /**
     * Show one tall X screen across both screens (upper part on top, lower part on the bottom screen).
     * Needs a matching container screen size and a game or mod that puts its UI in the lower part.
     */
    val screenSplit: Boolean = false,
    /** How the X screen is cut: "fill" (stretch both parts to the full width) or "native" (1:1 per display). */
    val splitLayout: String = "fill",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("bottomPanelEnabled", bottomPanelEnabled)
        put("screenSplit", screenSplit)
        put("splitLayout", splitLayout)
    }

    companion object {
        fun fromJson(json: JSONObject): DuoContainerSettings {
            val fallback = DuoContainerSettings()
            return DuoContainerSettings(
                bottomPanelEnabled = json.optBoolean("bottomPanelEnabled", fallback.bottomPanelEnabled),
                screenSplit = json.optBoolean("screenSplit", fallback.screenSplit),
                splitLayout = json.optString("splitLayout", fallback.splitLayout),
            )
        }
    }
}

object DuoSettingsStore {
    private const val DEFAULTS_FILE = "defaults.json"

    /** Settings for [containerId], or the global defaults when [containerId] is null or has no file yet. */
    fun load(context: Context, containerId: String?): DuoContainerSettings {
        if (containerId != null) read(fileFor(context, containerId))?.let { return it }
        return read(File(dir(context), DEFAULTS_FILE)) ?: DuoContainerSettings()
    }

    /** Saves settings for [containerId], or the global defaults when [containerId] is null. */
    fun save(context: Context, containerId: String?, settings: DuoContainerSettings) {
        val file = if (containerId == null) File(dir(context), DEFAULTS_FILE) else fileFor(context, containerId)
        try {
            file.parentFile?.mkdirs()
            file.writeText(settings.toJson().toString(2))
            Timber.tag(DualScreenHost.TAG).i("Saved settings for %s: %s", containerId ?: "defaults", settings)
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Failed to save settings for %s", containerId ?: "defaults")
        }
    }

    private fun read(file: File): DuoContainerSettings? = try {
        if (file.isFile) DuoContainerSettings.fromJson(JSONObject(file.readText())) else null
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).w(t, "Ignoring unreadable settings file %s", file)
        null
    }

    private fun dir(context: Context) = File(context.filesDir, "duo/settings")

    // Container ids are app ids like STEAM_588650; keep anything else from escaping the directory.
    private fun fileFor(context: Context, containerId: String) =
        File(File(dir(context), "containers"), containerId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")
}
