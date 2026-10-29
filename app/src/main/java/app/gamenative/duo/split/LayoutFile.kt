package app.gamenative.duo.split

import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.display.ScreenCalibration
import com.winlator.container.Container
import java.io.File
import org.json.JSONObject
import timber.log.Timber

/**
 * Tells in-game mods where the two screens are: `%AppData%\GameNativeDuo\layout.json` in the container's Wine
 * prefix, with the X screen size, the top and bottom rectangles (see [ScreenSplit.toJson]) and the physical
 * [ScreenCalibration], and `bridge.port`, the session's [app.gamenative.duo.bridge.DuoBridge] port (absent without one).
 * Mods use it only when the game window has exactly that size, so a stale file is harmless.
 * Mods re-read it while the game runs, so it is replaced atomically.
 */
object LayoutFile {
    fun file(container: Container) = File(container.rootDir, ".wine/drive_c/users/xuser/AppData/Roaming/GameNativeDuo/layout.json")

    fun write(container: Container, split: ScreenSplit, calibration: ScreenCalibration, bridgePort: Int = 0) {
        try {
            val json = split.toJson()
            calibration.toJson().let { c -> c.keys().forEach { json.put(it, c.get(it)) } }
            if (bridgePort > 0) json.put("bridge", JSONObject().put("port", bridgePort))
            val file = file(container).apply { parentFile?.mkdirs() }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString(2))
            if (!tmp.renameTo(file)) file.writeText(json.toString(2))
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to write layout.json")
        }
    }

    fun delete(container: Container) {
        try {
            file(container).delete()
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to delete layout.json")
        }
    }
}
