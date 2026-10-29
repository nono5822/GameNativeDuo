package app.gamenative.duo.companion

import android.content.Context
import android.os.BatteryManager
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import app.gamenative.duo.DualScreenHost
import app.gamenative.powercontrol.PowerManager
import java.io.File
import java.text.DateFormat
import java.util.Date
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * One game session's companion: its values ([data]), the user's saved state, the app's own `session.` values, and
 * live reload while it is being written. Lives as long as the game session, so closing and reopening the companion
 * page keeps everything. Main thread, except file I/O (one low-priority thread).
 *
 * Costs nothing while nobody looks: `session.` values tick once a second only while a shown card uses them, saves
 * are batched, and the dev-folder watch is inotify (no polling).
 */
class CompanionSession(
    private val context: Context,
    private val containerId: String,
    entry: CompanionEntry,
) {
    var entry: CompanionEntry = entry
        private set

    val manifest: CompanionManifest get() = entry.manifest
    val files: CompanionFiles get() = entry.files

    val data = CompanionData()

    /** Told (main thread) when the package was edited and reloaded: views rebuild. */
    val reloadListeners = mutableListOf<() -> Unit>()

    private val main = Handler(Looper.getMainLooper())
    private val io: Handler by lazy {
        HandlerThread("GNDuo-companion", Process.THREAD_PRIORITY_BACKGROUND).let {
            it.start()
            ioThread = it
            Handler(it.looper)
        }
    }
    private var ioThread: HandlerThread? = null
    private val startedAt = System.currentTimeMillis()
    private var stopped = false

    // ---- user state ------------------------------------------------------------------------------------------------

    /** Sets a `user.` value and saves it soon (batched: typing or ticking fast writes once). */
    fun setUser(key: String, value: Any?) {
        if (!key.startsWith(USER)) return
        data.put(key, value)
        main.removeCallbacks(save)
        main.postDelayed(save, SAVE_DELAY_MS)
        if (key.endsWith(TimerState.KEY_SUFFIX)) {
            main.removeCallbacks(keepAlive)
            main.postDelayed(keepAlive, KEEPALIVE_MS)
        }
    }

    /**
     * While a stopwatch runs, the state is written once a minute: if the app is killed (no clean session end), the
     * next session pauses the timer at the last write instead of counting the time the game was closed.
     */
    private val keepAlive = object : Runnable {
        override fun run() {
            if (stopped || !anyTimerRunning()) return
            saveNow()
            main.postDelayed(this, KEEPALIVE_MS)
        }
    }

    private fun anyTimerRunning() = data.snapshot(USER).any { (k, v) -> k.endsWith(TimerState.KEY_SUFFIX) && TimerState.from(v).running }

    private val save = Runnable { saveNow() }

    private fun saveNow() {
        val state = data.snapshot(USER)
        val id = manifest.id
        io.post { CompanionStore.saveState(context, containerId, id, state) }
    }

    /** A file the user picked for [cardId] ([name]: "image", "guide"), stored with the state. */
    fun userFile(cardId: String, name: String): File = CompanionStore.userFile(context, containerId, manifest.id, "$cardId-$name")

    /** Runs [work] on the file thread, then [done] with its result on the main thread. */
    fun <T> background(work: () -> T, done: (T) -> Unit) {
        io.post {
            val result = try {
                work()
            } catch (t: Throwable) {
                Timber.tag(DualScreenHost.TAG).w(t, "Companion background work failed")
                null
            }
            main.post {
                @Suppress("UNCHECKED_CAST")
                if (!stopped) done(result as T)
            }
        }
    }

    // ---- static data -----------------------------------------------------------------------------------------------

    private fun loadStatic() {
        val sources = manifest.data
        if (sources.isEmpty()) return
        val files = files
        background({
            sources.mapNotNull { (name, path) ->
                val text = files.text(path) ?: return@mapNotNull null
                val value = text.trim().let { if (it.startsWith("[")) JSONArray(it) else JSONObject(it) }
                "$STATIC$name" to toKotlin(value)
            }.toMap()
        }) { values -> if (values != null) data.putAll(values, clearPrefix = STATIC) }
    }

    // ---- session values --------------------------------------------------------------------------------------------

    private var sessionUsers = 0
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val clock = DateFormat.getTimeInstance(DateFormat.SHORT)
    private val tick = object : Runnable {
        override fun run() {
            updateSessionValues()
            if (sessionUsers > 0 && !stopped) main.postDelayed(this, 1000)
        }
    }

    /** A shown page uses `session.` values ([on]) or stopped using them. */
    fun useSessionValues(on: Boolean) {
        val before = sessionUsers
        sessionUsers = (sessionUsers + if (on) 1 else -1).coerceAtLeast(0)
        if (before == 0 && sessionUsers > 0) {
            main.removeCallbacks(tick)
            tick.run()
        } else if (sessionUsers == 0) {
            main.removeCallbacks(tick)
        }
    }

    private fun updateSessionValues() {
        try {
            val m = PowerManager.latestMetrics
            data.putAll(
                mapOf(
                    "${SESSION}playTime" to (System.currentTimeMillis() - startedAt) / 1000,
                    "${SESSION}clock" to clock.format(Date()),
                    "${SESSION}battery" to battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.toLong(),
                    "${SESSION}fps" to m?.fps?.toDouble(),
                ),
            )
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to update companion session values")
        }
    }

    // ---- build gate ------------------------------------------------------------------------------------------------

    /**
     * Null when live sources may read this game build; otherwise what to tell the user. A gate whose key is not known
     * yet (the mod has not said) does not block: there is nothing live to read anyway.
     */
    fun buildNotice(): String? {
        if (manifest.builds.isEmpty()) return null
        val seen = manifest.builds.mapNotNull { g -> data[g.source]?.toString()?.let { g to it } }
        if (seen.isEmpty() || seen.any { (g, v) -> v in g.accepted }) return null
        val (gate, actual) = seen.first()
        return "Made for game version ${gate.accepted.joinToString(", ")}; this is $actual. Live cards are off."
    }

    // ---- live reload (dev packages) --------------------------------------------------------------------------------

    private val observers = mutableListOf<FileObserver>()
    private val reload = Runnable { reloadNow() }

    @Suppress("DEPRECATION")
    private fun watch() {
        val dir = files.watchDir ?: return
        try {
            val dirs = dir.walkTopDown().maxDepth(2).filter { it.isDirectory }.toList()
            dirs.forEach { d ->
                val o = object : FileObserver(d.path, CLOSE_WRITE or MOVED_TO or DELETE or CREATE) {
                    override fun onEvent(event: Int, path: String?) {
                        // adb push writes several files: reload once they are all there.
                        main.removeCallbacks(reload)
                        main.postDelayed(reload, RELOAD_DELAY_MS)
                    }
                }
                o.startWatching()
                observers += o
            }
            Timber.tag(DualScreenHost.TAG).i("Companion %s: watching %s for changes", manifest.id, dir)
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to watch the companion folder")
        }
    }

    private fun reloadNow() {
        if (stopped) return
        val old = entry
        background({ CompanionStore.reload(old) }) { fresh ->
            if (fresh == null) return@background
            entry = fresh
            Timber.tag(DualScreenHost.TAG).i("Companion %s reloaded (%d problems)", fresh.manifest.id, fresh.manifest.problems.size)
            loadStatic()
            reloadListeners.toList().forEach {
                try {
                    it()
                } catch (t: Throwable) {
                    Timber.tag(DualScreenHost.TAG).e(t, "Companion view failed to reload")
                }
            }
        }
    }

    fun stop() {
        if (stopped) return
        try {
            observers.forEach { it.stopWatching() }
            observers.clear()
            main.removeCallbacks(tick)
            main.removeCallbacks(reload)
            main.removeCallbacks(keepAlive)
            pauseTimers()
            main.removeCallbacks(save)
            saveNow()
            ioThread?.quitSafely()
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to stop the companion")
        }
        stopped = true
    }

    /** A stopwatch left running is paused when the game ends ([at]: now, or the last save after an unclean end). */
    private fun pauseTimers(at: Long = System.currentTimeMillis()) {
        data.snapshot(USER).forEach { (key, value) ->
            if (key.endsWith(TimerState.KEY_SUFFIX)) {
                val t = TimerState.from(value)
                if (t.running) data.put(key, t.paused(maxOf(at, t.sinceMs)).toValue())
            }
        }
    }

    // Last on purpose: Kotlin runs initializers in file order, and this uses the properties above.
    init {
        data.putAll(CompanionStore.loadState(context, containerId, entry.manifest.id).filterKeys { it.startsWith(USER) })
        // Still "running" from a session that never ended cleanly: count only up to its last write.
        pauseTimers(at = CompanionStore.stateSavedAt(context, containerId, entry.manifest.id))
        loadStatic()
        watch()
    }

    companion object {
        const val USER = "user."
        const val STATIC = "static."
        const val SESSION = "session."
        private const val SAVE_DELAY_MS = 800L
        private const val RELOAD_DELAY_MS = 400L
        private const val KEEPALIVE_MS = 60_000L

        /** The companion [choice] picks for the container, ready to show; null when there is none (logged). */
        fun open(context: Context, containerId: String, choice: String): CompanionSession? = try {
            CompanionStore.resolve(context, containerId, choice)?.let { entry ->
                CompanionSession(context, containerId, entry).also {
                    Timber.tag(DualScreenHost.TAG).i(
                        "Companion %s (%s, %s) for %s",
                        entry.manifest.id, entry.origin, entry.manifest.packageVersion, containerId,
                    )
                }
            }
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Failed to open the companion")
            null
        }
    }
}

/** A timer card's state, stored as one `user.<card>.timer` value: elapsed ms, running since (epoch ms), laps, length. */
data class TimerState(val elapsedMs: Long = 0, val sinceMs: Long = 0, val laps: List<Long> = emptyList(), val lengthMs: Long = 0) {
    val running: Boolean get() = sinceMs > 0

    fun elapsedAt(now: Long): Long = elapsedMs + if (running) (now - sinceMs).coerceAtLeast(0) else 0

    fun started(now: Long) = if (running) this else copy(sinceMs = now)

    fun paused(now: Long) = if (running) copy(elapsedMs = elapsedAt(now), sinceMs = 0) else this

    fun lap(now: Long) = copy(laps = (laps + elapsedAt(now)).takeLast(MAX_LAPS))

    fun toValue(): Map<String, Any?> = mapOf("elapsed" to elapsedMs, "since" to sinceMs, "laps" to laps, "length" to lengthMs)

    companion object {
        const val KEY_SUFFIX = ".timer"
        const val MAX_LAPS = 50

        fun from(v: Any?): TimerState {
            val m = v as? Map<*, *> ?: return TimerState()
            fun long(k: String) = (m[k] as? Number)?.toLong() ?: 0L
            return TimerState(
                elapsedMs = long("elapsed").coerceAtLeast(0),
                sinceMs = long("since").coerceAtLeast(0),
                laps = (m["laps"] as? List<*>)?.mapNotNull { (it as? Number)?.toLong() }.orEmpty(),
                lengthMs = long("length").coerceAtLeast(0),
            )
        }
    }
}
