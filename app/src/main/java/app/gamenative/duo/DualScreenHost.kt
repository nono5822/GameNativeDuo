package app.gamenative.duo

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.View
import app.gamenative.duo.bridge.DuoBridge
import app.gamenative.duo.display.DeviceProfile
import app.gamenative.duo.display.DisplayInventory
import app.gamenative.duo.display.DisplaySnapshot
import app.gamenative.duo.mods.DuoModInstaller
import app.gamenative.duo.panel.BottomPanelController
import app.gamenative.duo.settings.DuoSettingsStore
import app.gamenative.duo.split.LayoutFile
import app.gamenative.duo.split.SplitLayout
import app.gamenative.duo.split.SplitScreenController
import app.gamenative.duo.ui.UiLayoutController
import com.winlator.container.Container
import com.winlator.xserver.XServer
import java.io.File
import timber.log.Timber

/**
 * Entry point for GameNativeDuo's dual-screen features.
 *
 * Upstream code calls into the devkit from exactly one place: [attach], once per game session from
 * XServerScreen, right after the stock external-display controllers start. Everything else lives in
 * the `app.gamenative.duo` package so upstream merges never touch it.
 *
 * Nothing here may break a game session: every entry point catches and logs its own failures.
 */
object DualScreenHost {
    internal const val TAG = "GNDuo"

    /** The running game's session (GameNative's in-game menu shows its GameNative Duo section from it). */
    @Volatile
    var current: DualScreenSession? = null
        private set

    /**
     * Starts a dual-screen session for the game that is launching. The session ends by itself when
     * [lifecycleView] (the game screen's root view) is detached from its window.
     */
    @JvmStatic
    fun attach(context: Context, lifecycleView: View, container: Container, xServer: XServer?): DualScreenSession? = try {
        DuoModInstaller.refreshIfUpdated(context, container.id)
        if (!Container.isX11Display(container.containerVariant, container.displayServer)) {
            Timber.tag(TAG).w("Gamescope display server: screen split and bottom-screen views need X11 (Graphics tab)")
        }
        DualScreenSession(context, container, xServer).also { session ->
            session.start()
            current = session
            lifecycleView.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {}

                    override fun onViewDetachedFromWindow(v: View) {
                        v.removeOnAttachStateChangeListener(this)
                        if (current === session) current = null
                        session.detach()
                    }
                },
            )
        }
    } catch (t: Throwable) {
        Timber.tag(TAG).e(t, "Failed to start dual-screen session")
        null
    }
}

/**
 * One game session's dual-screen state. Records how Android reports the device's displays (logcat tag
 * `GNDuo`, plus `Android/data/<package>/files/duo/displays.json`) whenever they change, so device profiles
 * can be built from real data, and keeps the bottom-screen panel in step with those displays.
 */
class DualScreenSession internal constructor(
    private val context: Context,
    val container: Container,
    val xServer: XServer?,
) {
    private val displayManager: DisplayManager? = context.getSystemService(DisplayManager::class.java)
    private val lastChangeKeys = mutableMapOf<Int, String>()
    val profile: DeviceProfile = DeviceProfile.detect()
    val settings = DuoSettingsStore.load(context, container.id)

    // The screen split owns the bottom screen when it is on, so the panel steps aside.
    private val bottomPanel = BottomPanelController(
        context,
        container,
        profile,
        settings.copy(bottomPanelEnabled = settings.bottomPanelEnabled && !(settings.screenSplit && xServer != null)),
    )
    private val splitOn = settings.screenSplit && xServer != null

    // Talks to the game's mod (native touch, UI layout); only worth a socket when the game is split.
    private val bridge = if (splitOn) DuoBridge.open() else null
    private val uiLayout = if (splitOn) UiLayoutController(context, container, bridge) else null
    private val screenSplit = if (splitOn) {
        SplitScreenController(
            context, xServer!!, profile, container, SplitLayout.fromId(settings.splitLayout),
            bridge = bridge,
            uiLayout = uiLayout,
            nativeTouch = settings.nativeTouch,
            scale = settings.splitScale,
            region = settings.region,
            onRegionSaved = { region ->
                DuoSettingsStore.save(context, container.id, DuoSettingsStore.load(context, container.id).copy(region = region))
            },
        )
    } else {
        null
    }
    private var running = false

    /** The game's UI layout while the screen split runs with a mod-aware layout (else null). */
    val layout: UiLayoutController? get() = uiLayout

    /** The screen split, for the in-game menu's actions (null when the split is off). */
    val screens: SplitScreenController? get() = screenSplit

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = report("display $displayId added")

        override fun onDisplayRemoved(displayId: Int) = report("display $displayId removed")

        override fun onDisplayChanged(displayId: Int) {
            // Fires often (brightness, refresh-rate hints); only report changes that matter for layout.
            val changed = DisplayInventory.snapshot(context).firstOrNull { it.id == displayId } ?: return
            if (lastChangeKeys[displayId] != changed.changeKey()) report("display $displayId changed")
        }
    }

    internal fun start() {
        if (running) return
        running = true
        displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        report("session start (container ${container.id}, profile ${profile.id}, $settings)")
        if (screenSplit == null) LayoutFile.delete(container)
        try {
            uiLayout?.start()
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Failed to hand the UI layout to the game")
        }
    }

    fun detach() {
        if (!running) return
        running = false
        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to unregister display listener")
        }
        bottomPanel.stop()
        screenSplit?.stop()
        bridge?.close()
        Timber.tag(DualScreenHost.TAG).i("Session ended")
    }

    private fun report(reason: String) {
        try {
            val displays = DisplayInventory.snapshot(context)
            lastChangeKeys.clear()
            displays.forEach { lastChangeKeys[it.id] = it.changeKey() }
            Timber.tag(DualScreenHost.TAG).i("Displays (%s): %d found", reason, displays.size)
            displays.forEach { Timber.tag(DualScreenHost.TAG).i("  %s", it.summary()) }
            writeReport(displays, reason)
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Failed to read displays")
        }
        bottomPanel.update()
        screenSplit?.update()
    }

    private fun writeReport(displays: List<DisplaySnapshot>, reason: String) {
        val json = DisplayInventory.toJson(displays, reason).toString(2)
        val dir = context.getExternalFilesDir("duo") ?: return
        Thread({
            try {
                File(dir, "displays.json").writeText(json)
            } catch (t: Throwable) {
                Timber.tag(DualScreenHost.TAG).w(t, "Failed to write displays.json")
            }
        }, "GNDuo-report").start()
    }
}
