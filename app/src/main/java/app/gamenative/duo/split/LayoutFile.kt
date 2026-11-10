package app.gamenative.duo.split

import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.bridge.DuoBridge
import app.gamenative.duo.display.ScreenCalibration
import com.winlator.container.Container
import java.io.File
import org.json.JSONObject
import timber.log.Timber

/**
 * Tells in-game mods where the two screens are: `%AppData%\GameNativeDuo\layout.json` in the container's Wine
 * prefix, with the X screen size, the top and bottom rectangles (see [ScreenSplit.toJson]) and the physical
 * [ScreenCalibration], and `bridge.port` / `bridge.token`, the session's [app.gamenative.duo.bridge.DuoBridge] port and
 * the secret a mod repeats in its HELLO (absent without a bridge).
 * Mods use it only when the game window has exactly that size, so a stale file is harmless.
 * Mods re-read it while the game runs, so it is replaced atomically.
 */
object LayoutFile {
    fun file(container: Container) = File(container.rootDir, ".wine/drive_c/users/xuser/AppData/Roaming/GameNativeDuo/layout.json")

    fun write(container: Container, split: ScreenSplit, calibration: ScreenCalibration, bridge: DuoBridge? = null) {
        try {
            val json = split.toJson()
            calibration.toJson().let { c -> c.keys().forEach { json.put(it, c.get(it)) } }
            if (bridge != null) json.put("bridge", bridgeJson(bridge.port, bridge.token))
            val file = file(container).apply { parentFile?.mkdirs() }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString(2))
            if (!tmp.renameTo(file)) file.writeText(json.toString(2))
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to write layout.json")
        }
    }

    /** The `bridge` object of `layout.json`, and all of `bridge.json`. */
    internal fun bridgeJson(port: Int, token: String): JSONObject = JSONObject().put("port", port).put("token", token)

    fun delete(container: Container) {
        try {
            file(container).delete()
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to delete layout.json")
        }
    }

    /**
     * `bridge.json` (`{"port": N, "token": "..."}`) next to `layout.json`: written whenever a bridge is open, also in
     * sessions without a split, so a companion-only mod knows where to send; null deletes it.
     */
    fun writeBridge(container: Container, bridge: DuoBridge?) {
        try {
            val file = File(file(container).parentFile, "bridge.json")
            if (bridge == null) {
                file.delete()
                return
            }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            val text = bridgeJson(bridge.port, bridge.token).toString()
            tmp.writeText(text)
            if (!tmp.renameTo(file)) file.writeText(text)
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to write bridge.json")
        }
    }
}
