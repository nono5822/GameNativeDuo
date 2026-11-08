package app.gamenative.duo

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import app.gamenative.duo.bridge.DuoBridge
import app.gamenative.duo.community.GameProfileStore
import app.gamenative.duo.companion.CompanionSession
import app.gamenative.duo.dev.DevMods
import app.gamenative.duo.dev.DevSession
import app.gamenative.duo.display.DeviceProfile
import app.gamenative.duo.display.DisplayInventory
import app.gamenative.duo.display.DisplaySnapshot
import app.gamenative.duo.mods.DuoModInstaller
import app.gamenative.duo.mods.SessionStatus
import app.gamenative.duo.mods.SessionStatusStore
import app.gamenative.duo.panel.BottomPanelController
import app.gamenative.duo.settings.DuoSettingsStore
import app.gamenative.duo.split.LayoutFile
import app.gamenative.duo.split.SplitLayout
import app.gamenative.duo.split.SplitScreenController
import app.gamenative.duo.split.StatusPresentation
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

    /** Dev-folder files loaded for the session being attached (developer mode). */
    internal var devFiles: List<String> = emptyList()
        private set

    /**
     * Starts a dual-screen session for the game that is launching. The session ends by itself when
     * [lifecycleView] (the game screen's root view) is detached from its window.
     */
    @JvmStatic
    fun attach(context: Context, lifecycleView: View, container: Container, xServer: XServer?): DualScreenSession? = try {
        DuoModInstaller.refreshIfUpdated(context, container.id)
        devFiles = if (DuoSettingsStore.load(context, container.id).developerMode) DevMods.apply(context, container.id) else emptyList()
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

    /** The game's companion (duo/docs/companions.md), or null when it is off or there is none. */
    val companion: CompanionSession? = CompanionSession.open(context, container.id, settings.companion)

    // The screen split owns the bottom screen when it is on, so the panel steps aside.
    private val bottomPanel = BottomPanelController(
        context,
        container,
        profile,
        settings.copy(bottomPanelEnabled = settings.bottomPanelEnabled && !(settings.screenSplit && xServer != null)),
        companion,
    )
    private val gamescope = !Container.isX11Display(container.containerVariant, container.displayServer)

    // Under Gamescope the X renderer never presents: no split (the bottom screen says why instead).
    private val splitOn = settings.screenSplit && xServer != null && !gamescope

    /** The installed dual-screen mod (bundled id or community package id), from its undo record. */
    private val modId = DuoModInstaller.enabledModId(context, container.id)

    /** What this session saw, for the Dual screen control's status (see [SessionStatus]). */
    private var sessionStatus = SessionStatus(
        startedAt = System.currentTimeMillis(),
        modId = modId,
        splitOn = splitOn,
        modExpected = splitOn && modId != null && SplitLayout.fromId(settings.splitLayout) != SplitLayout.REGION,
        gamescope = gamescope,
        xScreen = xServer?.screenInfo?.let { "${it.width}x${it.height}" }.orEmpty(),
        expectedScreen = DuoModInstaller.recordedScreenSize(context, container.id),
        devFiles = DualScreenHost.devFiles.size,
    )

    // Talks to the game's mod (native touch, UI layout, companion state); only worth a socket when the game is split
    // or its companion reads a mod's state.
    private val bridge = if (splitOn || companion?.manifest?.api != null) DuoBridge.open() else null
    /** Developer mode (setting), with the dev-folder files [DualScreenHost.attach] loaded. */
    private val dev = DevSession.open(settings.developerMode, bridge, companion, DualScreenHost.devFiles)

    private val uiLayout = if (splitOn) {
        val regions = SplitLayout.fromId(settings.splitLayout) == SplitLayout.REGION
        UiLayoutController(context, container, bridge, regionMode = regions, legacyRegion = settings.region)
    } else {
        null
    }
    private val screenSplit = if (splitOn) {
        SplitScreenController(
            context, xServer!!, profile, container, SplitLayout.fromId(settings.splitLayout),
            bridge = bridge,
            uiLayout = uiLayout,
            nativeTouch = settings.nativeTouch,
            scale = settings.splitScale,
            region = settings.region,
            modExpected = sessionStatus.modExpected,
            onOutputAttached = { updateStatus { it.copy(secondScreen = true) } },
            companion = companion,
            dev = dev,
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
        LayoutFile.writeBridge(container, bridge?.port ?: 0)
        GameProfileStore.writeToPrefix(context, container)
        bridge?.hapticListener = { ms, strength -> buzz(ms, strength) }
        bridge?.let { companion?.attachBridge(it) }
        try {
            uiLayout?.start()
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Failed to hand the UI layout to the game")
        }
        bridge?.connectListeners?.add { caps, engine ->
            if (!sessionStatus.connected) {
                updateStatus {
                    it.copy(
                        connected = true,
                        connectedAfterMs = System.currentTimeMillis() - it.startedAt,
                        engine = engine,
                        sdk = bridge?.sdkVersion.orEmpty(),
                        capabilities = caps,
                    )
                }
            }
        }
        updateStatus { it }
        if (settings.screenSplit && gamescope) showGamescopeStatus()
    }

    /** A mod's HAPTIC (already capped by the bridge). */
    private fun buzz(ms: Int, strength: Int) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(ms.toLong(), strength))
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Haptic failed")
        }
    }

    private fun updateStatus(change: (SessionStatus) -> SessionStatus) {
        try {
            sessionStatus = change(sessionStatus)
            SessionStatusStore.save(context, container.id, sessionStatus)
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to record the session status")
        }
    }

    /** Screen split is on but this session runs under Gamescope: the bottom screen says so instead of staying dark. */
    private var gamescopeStatus: StatusPresentation? = null

    private fun showGamescopeStatus() {
        val display = profile.findBottomDisplay(context) ?: return
        gamescopeStatus = StatusPresentation.show(
            context,
            display,
            "Dual screen needs the X11 display server",
            "This game runs under Gamescope, which the bottom screen cannot show. Quit, then tap ⚠ next to Dual screen in " +
                "the library and choose \"Use X11\".",
        )
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
        companion?.stop()
        dev?.stop()
        try {
            gamescopeStatus?.dismiss()
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to dismiss the bottom screen status")
        }
        gamescopeStatus = null
        bridge?.close()
        if (bridge != null) LayoutFile.writeBridge(container, 0)
        updateStatus { it.copy(endedAt = System.currentTimeMillis()) }
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
