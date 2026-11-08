package app.gamenative.duo.dev

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.bridge.BridgePackets
import app.gamenative.duo.bridge.DuoBridge
import app.gamenative.duo.companion.CompanionSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Developer mode for one game session (duo/docs/duo-api.md, the *dev* endpoints): numbered events for the long poll
 * (mod logs, connects, element changes, state keys, check progress), the last bridge packets, the mod's state, and
 * commands. Exists only while developer mode is on; nothing here runs otherwise.
 */
class DevSession(val bridge: DuoBridge, private val companion: CompanionSession?, val devFiles: List<String>) {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()
    private val events = ArrayDeque<JSONObject>()
    private var seq = 0L
    private val recent = ArrayDeque<JSONObject>()
    private var packetsIn = 0
    private var packetsOut = 0
    private var rateStart = SystemClock.elapsedRealtime()
    private var rates = 0.0 to 0.0

    /** The mod's state as it sent it (also without a companion). Guarded by itself. */
    private val state = LinkedHashMap<String, Any?>()

    /** Keys the developer subscribed to from the Console, sent with the companion's own. */
    @Volatile var consoleKeys: Set<String> = emptySet()
        private set

    private var nextRequest = 1_000_000
    private val pending = HashMap<Int, (Int, String) -> Unit>()

    private val onLog: (Int, String) -> Unit = { level, text -> log("mod", BridgePackets.levelName(level), text) }
    private val onElements: () -> Unit = { add("elements", JSONObject().put("event", "changed")) }
    private val onConnect: (Int, String) -> Unit = { caps, engine ->
        add("bridge", JSONObject().put("event", "connected").put("engine", engine).put("sdk", bridge.sdkVersion).put("caps", caps))
    }
    private val onState: (Int, Map<String, Any?>) -> Unit = { flags, changes ->
        synchronized(state) {
            if (flags and DuoBridge.STATE_FULL != 0) state.clear()
            changes.forEach { (k, v) -> if (v == null) state.remove(k) else if (state.size < 5000) state[k] = v }
        }
        add("state", JSONObject().put("keys", JSONArray(changes.keys.take(50))).put("full", flags and DuoBridge.STATE_FULL != 0))
    }
    private val onCommand: (Int, Int, String) -> Unit = { request, status, message -> pending.remove(request)?.invoke(status, message) }

    init {
        bridge.logListeners += onLog
        bridge.elementsChangedListeners += onElements
        bridge.connectListeners += onConnect
        bridge.stateListeners += onState
        bridge.commandListeners += onCommand
        bridge.recorder = { incoming, _, size, text -> record(incoming, size, text) }
        if (devFiles.isNotEmpty()) log("app", "I", "Developer mode: dev files loaded: " + devFiles.take(8).joinToString())
    }

    fun stop() {
        bridge.logListeners -= onLog
        bridge.elementsChangedListeners -= onElements
        bridge.connectListeners -= onConnect
        bridge.stateListeners -= onState
        bridge.commandListeners -= onCommand
        bridge.recorder = null
        synchronized(lock) { lock.notifyAll() }
    }

    // ---- events ------------------------------------------------------------------------------------------------------

    fun add(kind: String, data: JSONObject) {
        synchronized(lock) {
            data.put("seq", ++seq).put("t", System.currentTimeMillis()).put("kind", kind)
            events.addLast(data)
            while (events.size > CAPACITY) events.removeFirst()
            lock.notifyAll()
        }
    }

    fun log(source: String, level: String, text: String) =
        add("log", JSONObject().put("source", source).put("level", level).put("text", text))

    /** Events after [since] (of [kind] when given), waiting up to [waitMs] for one. Server threads only. */
    fun since(since: Long, waitMs: Long, kind: String? = null): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + waitMs
        synchronized(lock) {
            while (true) {
                val list = JSONArray()
                events.forEach { e ->
                    if (e.getLong("seq") > since && (kind == null || e.optString("kind") == kind)) list.put(JSONObject(e.toString()))
                }
                val left = deadline - SystemClock.elapsedRealtime()
                if (list.length() > 0 || left <= 0) return JSONObject().put("next", seq).put("events", list)
                lock.wait(left)
            }
        }
    }

    /** The last mod log lines, for the overlay. */
    fun lastLogs(n: Int): List<String> = synchronized(lock) {
        events.filter { it.optString("kind") == "log" }.takeLast(n).map { "${it.optString("level")} ${it.optString("text")}" }
    }

    // ---- bridge tab ---------------------------------------------------------------------------------------------------

    private fun record(incoming: Boolean, size: Int, text: String) {
        synchronized(recent) {
            if (incoming) packetsIn++ else packetsOut++
            if (text.isEmpty()) return
            recent.addLast(
                JSONObject().put("t", System.currentTimeMillis()).put("dir", if (incoming) "in" else "out")
                    .put("type", text.substringBefore(' ')).put("size", size).put("text", text.substringAfter(' ', "")),
            )
            while (recent.size > 100) recent.removeFirst()
        }
    }

    /** Packets per second, in and out, averaged over the last couple of seconds. */
    fun rates(): Pair<Double, Double> = synchronized(recent) {
        val now = SystemClock.elapsedRealtime()
        val secs = (now - rateStart) / 1000.0
        if (secs >= 2) {
            rates = packetsIn / secs to packetsOut / secs
            packetsIn = 0
            packetsOut = 0
            rateStart = now
        }
        rates
    }

    fun bridgeJson(): JSONObject {
        val (rin, rout) = rates()
        val list = JSONArray().apply { synchronized(recent) { recent.forEach { put(JSONObject(it.toString())) } } }
        return JSONObject()
            .put("port", bridge.port)
            .put(
                "hello",
                JSONObject().put("engine", bridge.engine).put("sdk", bridge.sdkVersion).put("caps", bridge.capabilities)
                    .put("connected", bridge.hasMod(0.inv())),
            )
            .put("rates", JSONObject().put("in", Math.round(rin * 10) / 10.0).put("out", Math.round(rout * 10) / 10.0))
            .put("recent", list)
    }

    // ---- console ------------------------------------------------------------------------------------------------------

    fun stateJson(prefix: String): JSONObject {
        val values = JSONObject()
        synchronized(state) { state.toSortedMap().forEach { (k, v) -> if (k.startsWith(prefix)) values.put(k, json(v)) } }
        return JSONObject().put("values", values)
    }

    /** Adds the Console's keys to what the mod sends (with the companion's own keys when one is open). Main thread. */
    fun subscribe(keys: Collection<String>) {
        consoleKeys = keys.toSet()
        if (companion != null) companion.extraKeys = consoleKeys else bridge.subscribe(consoleKeys)
    }

    /** Sends a command and waits (server thread) up to 3 s for its result: status name and message. */
    fun command(cmd: String, args: JSONObject?): Pair<String, String> {
        if (!bridge.seenMod(DuoBridge.CAP_COMMANDS)) return "unknown" to "the mod takes no commands (no capability 128 in its HELLO)"
        val result = AtomicReference<Pair<String, String>>("timeout" to "the mod did not answer")
        val latch = CountDownLatch(1)
        main.post {
            val request = nextRequest++
            pending[request] = { status, message ->
                result.set(statusName(status) to message)
                latch.countDown()
            }
            if (!bridge.sendCommand(request, cmd, args)) {
                pending.remove(request)
                result.set("failed" to "command too big")
                latch.countDown()
            }
            main.postDelayed({ pending.remove(request) }, 3_500)
        }
        latch.await(3, TimeUnit.SECONDS)
        return result.get()
    }

    private fun statusName(s: Int) = when (s) {
        DuoBridge.RESULT_OK -> "ok"
        DuoBridge.RESULT_REFUSED -> "refused"
        DuoBridge.RESULT_UNKNOWN -> "unknown"
        else -> "failed"
    }

    // ---- check --------------------------------------------------------------------------------------------------------

    @Volatile var checkRunning = false

    @Volatile var checkProgress = ""

    @Volatile var checkReport: JSONObject? = null

    private fun json(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply { v.forEach { (k, x) -> put(k.toString(), json(x)) } }
        is List<*> -> JSONArray().apply { v.forEach { put(json(it)) } }
        else -> v
    }

    companion object {
        const val CAPACITY = 2000

        /** A session's developer mode, or null when it is off or there is no bridge (logged). */
        fun open(on: Boolean, bridge: DuoBridge?, companion: CompanionSession?, devFiles: List<String>): DevSession? {
            if (!on || bridge == null) return null
            return try {
                DevSession(bridge, companion, devFiles).also { Timber.tag(DualScreenHost.TAG).i("Developer mode on") }
            } catch (t: Throwable) {
                Timber.tag(DualScreenHost.TAG).e(t, "Developer mode failed to start")
                null
            }
        }
    }
}
