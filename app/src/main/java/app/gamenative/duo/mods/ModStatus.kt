package app.gamenative.duo.mods

import android.content.Context
import app.gamenative.duo.DualScreenHost
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject
import timber.log.Timber

/**
 * What the last game session saw (written by the session, read by the Dual screen control): whether the screen split
 * ran, the X screen it got, whether the game's mod said hello on the bridge. `files/duo/status/<appId>.json`.
 */
data class SessionStatus(
    val startedAt: Long,
    val endedAt: Long = 0L,
    /** The mod the undo record names (null: no dual-screen mod installed). */
    val modId: String? = null,
    val splitOn: Boolean = false,
    /** True when a bridge-aware mod was expected to connect (a mod is installed and the layout is not a region view). */
    val modExpected: Boolean = false,
    val gamescope: Boolean = false,
    val xScreen: String = "",
    /** The size the screen split needs (from the undo record); empty when unknown. */
    val expectedScreen: String = "",
    val secondScreen: Boolean = false,
    val connected: Boolean = false,
    val connectedAfterMs: Long = 0L,
    val engine: String = "",
    val capabilities: Int = 0,
    /** The Duo SDK version the mod was built with, empty when it does not use the SDK. */
    val sdk: String = "",
    /** Developer mode: files from the dev folder that replaced the installed mod's this session. */
    val devFiles: Int = 0,
) {
    val durationMs: Long get() = if (endedAt > startedAt) endedAt - startedAt else 0L

    fun toJson(): JSONObject = JSONObject()
        .put("startedAt", startedAt)
        .put("endedAt", endedAt)
        .put("modId", modId ?: JSONObject.NULL)
        .put("splitOn", splitOn)
        .put("modExpected", modExpected)
        .put("gamescope", gamescope)
        .put("xScreen", xScreen)
        .put("expectedScreen", expectedScreen)
        .put("secondScreen", secondScreen)
        .put("connected", connected)
        .put("connectedAfterMs", connectedAfterMs)
        .put("engine", engine)
        .put("capabilities", capabilities)
        .put("sdk", sdk)
        .put("devFiles", devFiles)

    companion object {
        fun fromJson(o: JSONObject) = SessionStatus(
            startedAt = o.optLong("startedAt"),
            endedAt = o.optLong("endedAt"),
            modId = o.optString("modId").takeIf { it.isNotEmpty() && !o.isNull("modId") },
            splitOn = o.optBoolean("splitOn"),
            modExpected = o.optBoolean("modExpected"),
            gamescope = o.optBoolean("gamescope"),
            xScreen = o.optString("xScreen"),
            expectedScreen = o.optString("expectedScreen"),
            secondScreen = o.optBoolean("secondScreen"),
            connected = o.optBoolean("connected"),
            connectedAfterMs = o.optLong("connectedAfterMs"),
            engine = o.optString("engine"),
            capabilities = o.optInt("capabilities"),
            sdk = o.optString("sdk"),
            devFiles = o.optInt("devFiles"),
        )
    }
}

/** Reads and writes [SessionStatus] files. Writes go to one background thread (sessions update them from the main thread). */
object SessionStatusStore {
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "GNDuo-status").apply { isDaemon = true } }

    fun load(context: Context, appId: String): SessionStatus? = try {
        file(context, appId).takeIf { it.isFile }?.let { SessionStatus.fromJson(JSONObject(it.readText())) }
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).w(t, "Could not read the last session's status for %s", appId)
        null
    }

    fun save(context: Context, appId: String, status: SessionStatus) {
        val json = status.toJson().toString(2)
        val target = file(context, appId)
        writer.execute {
            try {
                target.parentFile?.mkdirs()
                val temp = File(target.path + ".tmp")
                temp.writeText(json)
                if (!temp.renameTo(target)) temp.delete()
            } catch (t: Throwable) {
                Timber.tag(DualScreenHost.TAG).w(t, "Could not write the session status for %s", appId)
            }
        }
    }

    /** Forgets the last session (after a fix, so an old failure is not shown again). */
    fun clear(context: Context, appId: String) {
        val target = file(context, appId)
        writer.execute { target.delete() }
    }

    private fun file(context: Context, appId: String) =
        File(context.filesDir, "duo/status/" + appId.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")
}

/** Something that stops Dual screen from working for a game, and what fixes it. */
enum class ModProblem {
    /** The container runs under Gamescope: the X renderer never presents. Fix: X11. */
    GAMESCOPE,

    /** The container's screen size is not the one the split needs. Fix: set it. */
    WRONG_SIZE,

    /** Files the mod installed are gone (a game update or a Steam file check removes them). Fix: reset the mod files. */
    FILES_MISSING,

    /** The last session ran long enough but the game's mod never said hello. Fix: reset the mod files. */
    MOD_NOT_LOADED,
}

/** The Dual screen status of one game, for the control next to Play and its status dialog. */
data class ModStatusReport(
    val on: Boolean,
    val problems: List<ModProblem>,
    val last: SessionStatus?,
    /** Recorded size the split needs and the container's current size (for [ModProblem.WRONG_SIZE]). */
    val expectedScreen: String = "",
    val screen: String = "",
    val missingFiles: Int = 0,
    /** The game's own files changed since the mod was installed (an update). Explains a missing or silent mod. */
    val gameUpdated: Boolean = false,
) {
    /** The last session with this mod worked: the mod said hello. */
    val worked: Boolean get() = on && problems.isEmpty() && last?.connected == true

    companion object {
        /** Sessions shorter than this say nothing about a mod that did not connect (the game was quit at its logo). */
        const val MIN_SESSION_MS = 60_000L

        /**
         * The problems, most important first. [modId] is the mod the undo record names, [last] the newest session:
         * a silent mod only counts when that session ran this mod long enough with the split on.
         */
        fun evaluate(
            on: Boolean,
            gamescope: Boolean,
            expectedScreen: String,
            screen: String,
            missingFiles: Int,
            modId: String?,
            last: SessionStatus?,
            gameUpdated: Boolean,
        ): ModStatusReport {
            if (!on) return ModStatusReport(false, emptyList(), last)
            val problems = mutableListOf<ModProblem>()
            if (gamescope) problems += ModProblem.GAMESCOPE
            if (expectedScreen.isNotEmpty() && screen.isNotEmpty() && !expectedScreen.equals(screen, ignoreCase = true)) {
                problems += ModProblem.WRONG_SIZE
            }
            if (missingFiles > 0) problems += ModProblem.FILES_MISSING
            val silent = last != null &&
                last.modId == modId &&
                last.modExpected &&
                last.splitOn &&
                !last.connected &&
                !last.gamescope &&
                last.durationMs >= MIN_SESSION_MS
            if (silent && ModProblem.FILES_MISSING !in problems) problems += ModProblem.MOD_NOT_LOADED
            return ModStatusReport(true, problems, last, expectedScreen, screen, missingFiles, gameUpdated)
        }
    }
}
