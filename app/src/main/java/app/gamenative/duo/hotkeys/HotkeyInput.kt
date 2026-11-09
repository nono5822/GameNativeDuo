package app.gamenative.duo.hotkeys

import android.os.Handler
import android.os.Looper
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.bridge.DuoBridge
import com.winlator.winhandler.WinHandler
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer
import timber.log.Timber

/**
 * Presses what touch hotkeys ask for, in the running game. Keys and mouse buttons go through the X server (the same
 * injection as GameNative's on-screen controls), so every game gets them without a mod; controller buttons are set on
 * the real controller's state (physical presses keep working alongside); mod commands go over the bridge when the
 * game's mod takes them, else to the button's fallback keys. Main thread.
 *
 * Every press is counted, so two buttons sharing a key (Ctrl+Tab and Ctrl) do not let go of it early, and
 * [releaseAll] lets go of everything (the companion closes, the session ends): nothing stays stuck in the game.
 */
class HotkeyInput(private val xServer: XServer?, private val bridge: DuoBridge?) {
    enum class Ready { OK, NO_GAME, NO_CONTROLLER, NEEDS_MOD }

    private val main = Handler(Looper.getMainLooper())
    private val keys = mutableMapOf<XKeycode, Int>()
    private val mouse = mutableMapOf<MouseButton, Int>()
    private val pad = mutableMapOf<PadButton, Int>()

    /** Commands whose press went to their fallback keys, so the release does too. */
    private val viaFallback = mutableMapOf<HotkeyAction.Command, Int>()
    private var nextRequest = 1
    private val pendingCommands = mutableMapOf<Int, String>()

    /** Told when the mod answers a command with anything but OK: command, status, message (for a short notice). */
    var onCommandProblem: ((command: String, status: Int, message: String) -> Unit)? = null

    private val commandListener: (Int, Int, String) -> Unit = { request, status, message ->
        pendingCommands.remove(request)?.let { cmd ->
            if (status != DuoBridge.RESULT_OK) {
                Timber.tag(DualScreenHost.TAG).i("Hotkey command %s: status %d %s", cmd, status, message)
                onCommandProblem?.invoke(cmd, status, message)
            }
        }
    }

    init {
        bridge?.commandListeners?.add(commandListener)
    }

    private val commandsOn: Boolean get() = bridge?.hasMod(DuoBridge.CAP_COMMANDS) == true

    private val winHandler: WinHandler? get() = xServer?.winHandler

    fun readiness(action: HotkeyAction): Ready = when (action) {
        is HotkeyAction.Keys, is HotkeyAction.Mouse -> if (xServer != null) Ready.OK else Ready.NO_GAME
        is HotkeyAction.Pad -> if (winHandler?.currentController != null) Ready.OK else Ready.NO_CONTROLLER
        is HotkeyAction.Command -> when {
            commandsOn -> Ready.OK
            action.fallback != null -> readiness(action.fallback)
            else -> Ready.NEEDS_MOD
        }
    }

    /** Presses and lets go after [TAP_MS] (long enough for a game that polls the keyboard once a frame). */
    fun tap(action: HotkeyAction) {
        press(action)
        main.postDelayed({ release(action) }, TAP_MS)
    }

    fun press(action: HotkeyAction): Unit = safely("press") {
        when (action) {
            is HotkeyAction.Keys -> action.keys.forEach { k -> if (inc(keys, k)) xServer?.injectKeyPress(k) }
            is HotkeyAction.Mouse -> if (action.button.wheel) {
                // One notch: X reports the wheel as a button press and release.
                xServer?.injectPointerButtonPress(pointer(action.button))
                xServer?.injectPointerButtonRelease(pointer(action.button))
            } else if (inc(mouse, action.button)) {
                xServer?.injectPointerButtonPress(pointer(action.button))
            }
            is HotkeyAction.Pad -> if (inc(pad, action.button)) setPad(action.button, true)
            is HotkeyAction.Command -> if (commandsOn) {
                val request = nextRequest++
                if (bridge?.sendCommand(request, action.command) == true) {
                    pendingCommands[request] = action.command
                    // Answers that never come must not pile up.
                    main.postDelayed({ pendingCommands.remove(request) }, COMMAND_TIMEOUT_MS)
                }
            } else if (action.fallback != null) {
                viaFallback[action] = (viaFallback[action] ?: 0) + 1
                press(action.fallback)
            }
        }
    }

    fun release(action: HotkeyAction): Unit = safely("release") {
        when (action) {
            is HotkeyAction.Keys -> action.keys.asReversed().forEach { k -> if (dec(keys, k)) xServer?.injectKeyRelease(k) }
            is HotkeyAction.Mouse -> if (!action.button.wheel && dec(mouse, action.button)) {
                xServer?.injectPointerButtonRelease(pointer(action.button))
            }
            is HotkeyAction.Pad -> if (dec(pad, action.button)) setPad(action.button, false)
            is HotkeyAction.Command -> {
                val n = viaFallback[action] ?: 0
                if (n > 0 && action.fallback != null) {
                    if (n == 1) viaFallback -= action else viaFallback[action] = n - 1
                    release(action.fallback)
                }
            }
        }
    }

    /** Lets go of every key, mouse button and controller button a hotkey still holds. */
    fun releaseAll(): Unit = safely("release all") {
        keys.keys.toList().forEach { xServer?.injectKeyRelease(it) }
        mouse.keys.toList().forEach { xServer?.injectPointerButtonRelease(pointer(it)) }
        val heldPad = pad.keys.toList()
        keys.clear()
        mouse.clear()
        pad.clear()
        viaFallback.clear()
        heldPad.forEach { setPad(it, false) }
    }

    fun close() {
        releaseAll()
        bridge?.commandListeners?.remove(commandListener)
        main.removeCallbacksAndMessages(null)
    }

    /** True when this press is the first for [k] (the game must see it). */
    private fun <K> inc(counts: MutableMap<K, Int>, k: K): Boolean {
        val n = (counts[k] ?: 0) + 1
        counts[k] = n
        return n == 1
    }

    /** True when this release lets go of [k] for good. A release with nothing held does nothing. */
    private fun <K> dec(counts: MutableMap<K, Int>, k: K): Boolean {
        val n = counts[k] ?: return false
        return if (n <= 1) {
            counts -= k
            true
        } else {
            counts[k] = n - 1
            false
        }
    }

    private fun setPad(button: PadButton, down: Boolean) {
        val handler = winHandler ?: return
        val controller = handler.currentController ?: return
        val state = controller.state
        if (button.index >= 0) state.setPressed(button.index, down) else state.dpad[button.dpad] = down
        // The same slot the real controller writes (evshim memory file), plus the UDP path older containers use.
        handler.sendVirtualGamepadState(state, 0)
        handler.sendGamepadState()
    }

    private fun pointer(b: MouseButton) = when (b) {
        MouseButton.LEFT -> Pointer.Button.BUTTON_LEFT
        MouseButton.RIGHT -> Pointer.Button.BUTTON_RIGHT
        MouseButton.MIDDLE -> Pointer.Button.BUTTON_MIDDLE
        MouseButton.WHEEL_UP -> Pointer.Button.BUTTON_SCROLL_UP
        MouseButton.WHEEL_DOWN -> Pointer.Button.BUTTON_SCROLL_DOWN
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Hotkey %s failed", what)
        }
    }

    companion object {
        const val TAP_MS = 80L
        private const val COMMAND_TIMEOUT_MS = 5_000L
    }
}
