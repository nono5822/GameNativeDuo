package app.gamenative.duo.bridge

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import app.gamenative.duo.DualScreenHost
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import timber.log.Timber

/**
 * GameNative Duo's link to in-game mods: UDP datagrams on 127.0.0.1 (Wine's sockets are this app's own, so it is a
 * kernel loopback hop). This side binds a free port, written into `layout.json` as `bridge.port`; a mod binds its own
 * and announces it with a HELLO every 2 s, which also says what it can do ([CAP_TOUCH], ...). No mod, no traffic.
 *
 * Wire format (little-endian): `'G' 'D' version type`, then per type:
 * - app -> mod: [TOUCH] `action u8, pointer u8, 0, 0, x f32, y f32` (window pixels, origin top-left);
 *   [RULES_CHANGED]; [DUMP_ELEMENTS] `request u32`; [COMMAND] `request u32, json utf-8`.
 * - mod -> app: [HELLO] `capabilities u32, engine ascii`; [ELEMENTS_WRITTEN] `request u32`;
 *   [COMMAND_RESULT] `request u32, status u8, message utf-8`.
 *
 * Costs: the receive thread sleeps in the kernel; sends go through one background thread (Android forbids socket
 * I/O on the main thread, even on loopback), and touch moves are coalesced per finger so a slow game frame never
 * builds a backlog. [listener] callbacks run on the main thread.
 */
class DuoBridge private constructor(private val socket: DatagramSocket) {
    interface Listener {
        fun onModConnected(capabilities: Int, engine: String) {}

        fun onElementsWritten(request: Int) {}
    }

    @Volatile var listener: Listener? = null

    /** Also told when a mod connects (main thread): the session's status record, the bottom screen's status card. */
    val connectListeners = CopyOnWriteArrayList<(capabilities: Int, engine: String) -> Unit>()

    val port: Int get() = socket.localPort

    @Volatile private var modPort = 0

    @Volatile private var lastHelloAt = 0L

    @Volatile var capabilities = 0
        private set

    @Volatile var engine = ""
        private set

    private val main = Handler(Looper.getMainLooper())
    private val txThread = HandlerThread("GNDuo-bridge-tx", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val tx = Handler(txThread.looper)

    /** Packets waiting for the send thread; a MOVE replaces the same finger's queued MOVE. Guarded by itself. */
    private val queue = ArrayDeque<ByteArray>()
    private val flush = Runnable { drain() }

    @Volatile private var closed = false

    private val rxThread = Thread({ receiveLoop() }, "GNDuo-bridge-rx").apply {
        isDaemon = true
        start()
    }

    /** True when a mod with [capability] said hello in the last few seconds. */
    fun hasMod(capability: Int): Boolean =
        modPort != 0 && capabilities and capability != 0 && SystemClock.elapsedRealtime() - lastHelloAt < MOD_TIMEOUT_MS

    /**
     * True when a mod with [capability] said hello this session, even if not lately: GameNative stops the game's
     * processes while its in-game menu is open, so a quiet mod is often only paused.
     */
    fun seenMod(capability: Int): Boolean = modPort != 0 && capabilities and capability != 0

    fun sendTouch(action: Int, pointer: Int, x: Float, y: Float) {
        val p = packet(TOUCH, 16)
        ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(4, action.toByte())
            put(5, pointer.toByte())
            putFloat(8, x)
            putFloat(12, y)
        }
        enqueue(p, coalesce = action == TOUCH_MOVE)
    }

    fun sendRulesChanged() = enqueue(packet(RULES_CHANGED, 4), coalesce = false)

    fun requestElements(request: Int) {
        val p = packet(DUMP_ELEMENTS, 8)
        ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(4, request)
        enqueue(p, coalesce = false)
    }

    /**
     * Asks the mod to run [command] (`{"cmd": ..., "args": {...}}`, see duo/docs/companions.md); the mod answers with
     * COMMAND_RESULT for [request] ([commandListeners]). False when the JSON is too big for one datagram.
     */
    fun sendCommand(request: Int, command: String, args: org.json.JSONObject? = null): Boolean {
        val json = org.json.JSONObject().put("cmd", command).apply { if (args != null) put("args", args) }.toString().toByteArray()
        if (json.size > MAX_COMMAND_BYTES) return false
        val p = packet(COMMAND, 8 + json.size)
        ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).putInt(4, request)
        System.arraycopy(json, 0, p, 8, json.size)
        enqueue(p, coalesce = false)
        return true
    }

    /** Told (main thread) when the mod answers a command: request, status ([RESULT_OK]...), message. */
    val commandListeners = CopyOnWriteArrayList<(request: Int, status: Int, message: String) -> Unit>()

    fun close() {
        closed = true
        try {
            socket.close()
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Failed to close bridge socket")
        }
        txThread.quitSafely()
    }

    private fun enqueue(p: ByteArray, coalesce: Boolean) {
        if (closed || modPort == 0) return
        synchronized(queue) {
            val last = queue.lastOrNull()
            // Only the newest position of a moving finger matters.
            if (coalesce && last != null && last[3] == TOUCH && last[4] == TOUCH_MOVE.toByte() && last[5] == p[5]) {
                queue[queue.size - 1] = p
                return
            }
            queue.addLast(p)
            if (queue.size == 1) tx.post(flush)
        }
    }

    private fun drain() {
        while (true) {
            val p = synchronized(queue) { queue.removeFirstOrNull() } ?: return
            val port = modPort
            if (port == 0 || closed) continue
            try {
                socket.send(DatagramPacket(p, p.size, InetSocketAddress(LOOPBACK, port)))
            } catch (t: Throwable) {
                if (!closed) Timber.tag(DualScreenHost.TAG).w(t, "Bridge send failed")
            }
        }
    }

    private fun receiveLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val buffer = ByteArray(512)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!closed) {
            try {
                packet.setLength(buffer.size)
                socket.receive(packet)
                if (!packet.address.isLoopbackAddress) continue
                val n = packet.length
                if (n < 4 || buffer[0] != 'G'.code.toByte() || buffer[1] != 'D'.code.toByte() || buffer[2] != VERSION) continue
                val b = ByteBuffer.wrap(buffer, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                when (buffer[3]) {
                    HELLO -> if (n >= 8) onHello(packet.port, b.getInt(4), String(buffer, 8, n - 8, Charsets.US_ASCII))
                    ELEMENTS_WRITTEN -> if (n >= 8) {
                        val request = b.getInt(4)
                        main.post { listener?.onElementsWritten(request) }
                    }
                    COMMAND_RESULT -> if (n >= 9) {
                        val request = b.getInt(4)
                        val status = buffer[8].toInt() and 0xFF
                        val message = String(buffer, 9, n - 9, Charsets.UTF_8)
                        main.post { commandListeners.forEach { it(request, status, message) } }
                    }
                }
            } catch (t: Throwable) {
                if (closed) return
                Timber.tag(DualScreenHost.TAG).w(t, "Bridge receive failed")
                SystemClock.sleep(200)
            }
        }
    }

    private fun onHello(port: Int, caps: Int, name: String) {
        val isNew = port != modPort || caps != capabilities || SystemClock.elapsedRealtime() - lastHelloAt >= MOD_TIMEOUT_MS
        modPort = port
        capabilities = caps
        engine = name
        lastHelloAt = SystemClock.elapsedRealtime()
        if (isNew) {
            Timber.tag(DualScreenHost.TAG).i("Bridge: %s mod connected from port %d (capabilities 0x%x)", name, port, caps)
            main.post {
                listener?.onModConnected(caps, name)
                connectListeners.forEach { it(caps, name) }
            }
        }
    }

    private fun packet(type: Byte, size: Int) = ByteArray(size).also {
        it[0] = 'G'.code.toByte()
        it[1] = 'D'.code.toByte()
        it[2] = VERSION
        it[3] = type
    }

    companion object {
        const val VERSION: Byte = 1
        const val TOUCH: Byte = 0x01
        const val RULES_CHANGED: Byte = 0x02
        const val DUMP_ELEMENTS: Byte = 0x03
        const val HELLO: Byte = 0x81.toByte()
        const val ELEMENTS_WRITTEN: Byte = 0x83.toByte()
        const val COMMAND: Byte = 0x05
        const val COMMAND_RESULT: Byte = 0x85.toByte()

        const val RESULT_OK = 0
        const val RESULT_REFUSED = 1
        const val RESULT_UNKNOWN = 2
        const val RESULT_FAILED = 3

        /** A command and its arguments fit one small datagram; anything bigger is a mistake. */
        const val MAX_COMMAND_BYTES = 1200

        const val CAP_TOUCH = 1
        const val CAP_UI_RULES = 2
        const val CAP_ELEMENTS = 4

        /** Bottom-screen background: solid color and image. */
        const val CAP_BG_BASIC = 8

        /** Bottom-screen background: the game blurred. */
        const val CAP_BG_BLUR = 16

        /** Bottom-screen background: the game itself (the world continues on the bottom screen). */
        const val CAP_BG_GAME = 32

        /** The mod publishes companion state (STATE; app side not built yet). */
        const val CAP_STATE = 64

        /** The mod runs commands (COMMAND / COMMAND_RESULT): companion buttons, hotkeys. */
        const val CAP_COMMANDS = 128

        const val TOUCH_DOWN = 0
        const val TOUCH_MOVE = 1
        const val TOUCH_UP = 2
        const val TOUCH_CANCEL = 3

        /**
         * IPv4 127.0.0.1 on purpose: Wine's sockets are IPv4, and `InetAddress.getLoopbackAddress()` is `::1` on
         * some Android builds (the AYN Thor's), where a mod's packets never arrive.
         */
        private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        /** Mods say hello every 2 s, but a loading screen can stall a game for seconds; this much silence means it is gone. */
        private const val MOD_TIMEOUT_MS = 15_000L

        /** Opens a bridge on a free loopback port, or null (logged) when that fails. */
        fun open(): DuoBridge? = try {
            DuoBridge(DatagramSocket(InetSocketAddress(LOOPBACK, 0))).also {
                Timber.tag(DualScreenHost.TAG).i("Bridge listening on 127.0.0.1:%d", it.port)
            }
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Failed to open the bridge")
            null
        }
    }
}
