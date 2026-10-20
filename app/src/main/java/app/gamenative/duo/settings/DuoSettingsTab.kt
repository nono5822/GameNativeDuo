package app.gamenative.duo.settings

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.duo.mods.DuoModInstaller
import app.gamenative.duo.split.ScreenSplit
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.alorma.compose.settings.ui.SettingsSwitch
import org.json.JSONObject

/**
 * The "GameNative Duo" tab in upstream's container settings dialog, placed right after "Emulation".
 *
 * Upstream's dialog picks tab content by index, so it hooks in with a few marked lines: [withDuoTab] adds
 * our label to its tab list, and [upstreamIndex] maps the selected tab back to upstream's numbering
 * ([SELECTED] for ours), so upstream's own `if (selectedTab == N)` lines stay untouched.
 */
object DuoSettingsTab {
    /** Value [upstreamIndex] returns when the GameNative Duo tab is selected. */
    const val SELECTED = -1

    @Composable
    fun withDuoTab(tabs: List<String>): List<String> {
        val emulation = tabs.indexOf(stringResource(R.string.container_config_tab_emulation))
        val position = if (emulation >= 0) emulation + 1 else tabs.size
        return tabs.toMutableList().apply { add(position, stringResource(R.string.duo_settings_tab)) }
    }

    @Composable
    fun upstreamIndex(tabs: List<String>, selectedTab: Int): Int {
        val position = tabs.indexOf(stringResource(R.string.duo_settings_tab))
        return when {
            position < 0 || selectedTab < position -> selectedTab
            selectedTab == position -> SELECTED
            else -> selectedTab - 1
        }
    }
}

/** Unsaved edits made in the dialog; written to disk only when the dialog's Save button calls [commit]. */
class DuoSettingsDraft internal constructor(
    private val context: Context,
    private val containerId: String?,
    private val initial: DuoContainerSettings,
    current: DuoContainerSettings,
) {
    var settings by mutableStateOf(current)

    val isDirty: Boolean get() = settings != initial

    fun commit() {
        if (!isDirty) return
        DuoSettingsStore.save(context, containerId, settings)
        // A dual-screen mod sized the container's screen for the old resolution.
        val scale = settings.splitScale
        if (containerId != null && scale != initial.splitScale) {
            Thread({ DuoModInstaller.applySplitScale(context, containerId, scale) }, "GNDuo-split-scale").start()
        }
    }

    companion object {
        fun saver(context: Context, containerId: String?): Saver<DuoSettingsDraft, Any> = listSaver(
            save = { listOf(it.initial.toJson().toString(), it.settings.toJson().toString()) },
            restore = { (initial, current) ->
                DuoSettingsDraft(
                    context,
                    containerId,
                    DuoContainerSettings.fromJson(JSONObject(initial)),
                    DuoContainerSettings.fromJson(JSONObject(current)),
                )
            },
        )
    }
}

/** [containerId] null edits the defaults used by containers that have no settings of their own. */
@Composable
fun rememberDuoSettingsDraft(containerId: String?): DuoSettingsDraft {
    val context = LocalContext.current.applicationContext
    return rememberSaveable(containerId, saver = DuoSettingsDraft.saver(context, containerId)) {
        val stored = DuoSettingsStore.load(context, containerId)
        DuoSettingsDraft(context, containerId, stored, stored)
    }
}

@Composable
fun DuoSettingsTabContent(draft: DuoSettingsDraft) {
    val settings = draft.settings
    val context = LocalContext.current
    val splitSize = remember { ScreenSplit.recommendedFor(context) }
    SettingsGroup {
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.duo_settings_bottom_panel)) },
            subtitle = { Text(text = stringResource(R.string.duo_settings_bottom_panel_description)) },
            state = settings.bottomPanelEnabled,
            onCheckedChange = { draft.settings = settings.copy(bottomPanelEnabled = it) },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.duo_settings_screen_split)) },
            subtitle = {
                Text(
                    text = if (splitSize != null) {
                        stringResource(R.string.duo_settings_screen_split_description, splitSize.first, splitSize.second)
                    } else {
                        stringResource(R.string.duo_settings_screen_split_no_display)
                    },
                )
            },
            state = settings.screenSplit,
            onCheckedChange = { draft.settings = settings.copy(screenSplit = it) },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            enabled = settings.screenSplit,
            title = { Text(text = stringResource(R.string.duo_settings_native_touch)) },
            subtitle = { Text(text = stringResource(R.string.duo_settings_native_touch_description)) },
            state = settings.nativeTouch,
            onCheckedChange = { draft.settings = settings.copy(nativeTouch = it) },
        )
        SettingsMenuLink(
            colors = settingsTileColorsAlt(),
            enabled = settings.screenSplit,
            title = { Text(text = stringResource(R.string.duo_settings_split_scale, (settings.splitScale * 100).toInt())) },
            subtitle = { Text(text = stringResource(R.string.duo_settings_split_scale_description)) },
            onClick = {
                // Tap to cycle 100% -> 75% -> 50%.
                val scales = DuoContainerSettings.SPLIT_SCALES
                draft.settings = settings.copy(splitScale = scales[(scales.indexOf(settings.splitScale) + 1) % scales.size])
            },
        )
    }
}
