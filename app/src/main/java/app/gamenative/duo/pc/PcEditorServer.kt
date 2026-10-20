package app.gamenative.duo.pc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.community.ShareLayout
import app.gamenative.duo.ui.UiBackground
import app.gamenative.duo.ui.UiBackgroundMode
import app.gamenative.duo.ui.UiElementList
import app.gamenative.duo.ui.UiLayout
import app.gamenative.duo.ui.UiLayoutController
import app.gamenative.duo.ui.UiRule
import app.gamenative.duo.ui.UiWidget
import app.gamenative.duo.ui.UiWidgetType
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/** What the PC editor needs from the running session. Called on the main thread. */
internal interface PcEditorHost {
    val controller: UiLayoutController
    val windowW: Int
    val windowH: Int

    /** The game-window rectangles shown on each screen (`x, y, w, h`). */
    val topRect: FloatArray
    val bottomRect: FloatArray

    /** The bottom screen's size in pixels (background images are scaled to cover it). */
    val bottomSize: Pair<Int, Int>

    /** A JPEG of what [top] (else the bottom) screen shows now, or null. */
    fun snapshot(top: Boolean, done: (ByteArray?) -> Unit)
}

/**
 * The PC editor: a small web server on the device's Wi-Fi address while the user has it open, serving a web app
 * (assets/duo/pc-editor/) that shows both screens and edits the same layout as the on-device editor. Every request
 * must carry the pairing code shown on the bottom screen; after [MAX_BAD_CODES] wrong ones the server stops. It never
 * runs in the background: started from the bottom screen's menu, stopped with it or with the game.
 */
internal class PcEditorServer(private val context: Context, private val host: PcEditorHost) {
    val code: String = randomCode()
    var port: Int = 0
        private set

    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(2) { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "GNDuo-pc").apply { isDaemon = true }
    }
    private var socket: ServerSocket? = null
    private val badCodes = AtomicInteger()

    @Volatile var onStopped: (() -> Unit)? = null

    /** The address to type on the PC, or null when the device has no Wi-Fi address. */
    val url: String? get() = lanAddress()?.let { "http://${it.hostAddress}:$port/" }

    fun start(): Boolean = try {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(java.net.InetSocketAddress(PORT_FIRST).takeIf { isFree(PORT_FIRST) } ?: java.net.InetSocketAddress(0))
        socket = s
        port = s.localPort
        Thread({ acceptLoop(s) }, "GNDuo-pc-accept").apply { isDaemon = true }.start()
        Timber.tag(DualScreenHost.TAG).i("PC editor on port %d", port)
        true
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).e(t, "Could not start the PC editor")
        false
    }

    fun stop() {
        try {
            socket?.close()
        } catch (t: Throwable) {
        }
        socket = null
        workers.shutdownNow()
        onStopped?.let { main.post(it) }
        onStopped = null
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val client = try {
                s.accept()
            } catch (e: SocketException) {
                return
            } catch (t: Throwable) {
                Timber.tag(DualScreenHost.TAG).w(t, "PC editor accept failed")
                return
            }
            try {
                workers.execute { serve(client) }
            } catch (t: Throwable) {
                client.close()
            }
        }
    }

    // ---- HTTP ------------------------------------------------------------------------------------------------

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private fun serve(client: Socket) {
        client.use { c ->
            try {
                c.soTimeout = 15_000
                val input = BufferedInputStream(c.getInputStream())
                val req = read(input) ?: return
                val out = c.getOutputStream()
                route(req, out)
            } catch (t: Throwable) {
                Timber.tag(DualScreenHost.TAG).w(t, "PC editor request failed")
            }
        }
    }

    private fun read(input: InputStream): Request? {
        val head = StringBuilder()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.length > 16_384) return null
        }
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 2) return null
        val headers = lines.drop(1).mapNotNull { l ->
            l.indexOf(':').takeIf { it > 0 }?.let {
                l.substring(0, it).trim().lowercase() to
                    l.substring(it + 1).trim()
            }
        }.toMap()
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        return Request(parts[0], path, query, headers, body)
    }

    private fun route(req: Request, out: OutputStream) {
        // The page itself is public (it asks for the code); everything else needs it.
        if (req.method == "GET" && (req.path == "/" || req.path == "/index.html")) {
            return asset(out, "duo/pc-editor/index.html", "text/html; charset=utf-8")
        }
        val given = req.headers["x-duo-code"] ?: req.query["k"]
        if (given == null || !constantTimeEquals(given.uppercase(), code)) {
            if (badCodes.incrementAndGet() >= MAX_BAD_CODES) {
                Timber.tag(DualScreenHost.TAG).w("PC editor: too many wrong codes, stopping")
                main.post { stop() }
            }
            return send(out, 403, "application/json", """{"error":"wrong code"}""".toByteArray())
        }
        when ("${req.method} ${req.path}") {
            "GET /api/state" -> sendJson(out, state())
            "GET /api/snapshot/top.jpg", "GET /api/snapshot/bottom.jpg" -> {
                val jpeg = onMain(5_000) { done: (ByteArray?) -> Unit -> host.snapshot(req.path.contains("top"), done) }
                if (jpeg != null) send(out, 200, "image/jpeg", jpeg) else send(out, 503, "text/plain", "no picture".toByteArray())
            }
            "POST /api/rule" -> {
                val o = JSONObject(String(req.body))
                val rule = UiRule.fromJson(o.getJSONObject("rule"))
                onMainUnit { host.controller.set(o.getString("id"), rule, save = true) }
                sendJson(out, JSONObject().put("ok", true))
            }
            "POST /api/background" -> {
                val bg = UiBackground.fromJson(JSONObject(String(req.body)))
                onMainUnit { host.controller.setBackground(bg.copy(image = host.controller.layout.background.image), save = true) }
                sendJson(out, JSONObject().put("ok", true))
            }
            "POST /api/widgets" -> {
                val a = JSONArray(String(req.body))
                val widgets = (0 until a.length()).mapNotNull { UiWidget.fromJson(a.getJSONObject(it)) }
                onMainUnit { host.controller.setWidgets(widgets, save = true) }
                sendJson(out, JSONObject().put("ok", true))
            }
            "POST /api/image" -> {
                val (w, h) = onMain(2_000) { done: (Pair<Int, Int>?) -> Unit -> done(host.bottomSize) } ?: (1240 to 1080)
                val ok =
                    onMain(20_000) { done: (Boolean?) -> Unit -> host.controller.setBackgroundImage(req.body, w, h) { done(it) } } == true
                sendJson(out, JSONObject().put("ok", ok))
            }
            "POST /api/snapshot-background" -> {
                // The top screen's current picture (the game without its UI) as the bottom screen's background.
                val jpeg = onMain(5_000) { done: (ByteArray?) -> Unit -> host.snapshot(true, done) }
                val (w, h) = onMain(2_000) { done: (Pair<Int, Int>?) -> Unit -> done(host.bottomSize) } ?: (1240 to 1080)
                val ok =
                    jpeg != null &&
                        onMain(20_000) { done: (Boolean?) -> Unit -> host.controller.setBackgroundImage(jpeg, w, h) { done(it) } } == true
                sendJson(out, JSONObject().put("ok", ok))
            }
            "POST /api/reset" -> {
                onMainUnit { host.controller.resetAll() }
                sendJson(out, JSONObject().put("ok", true))
            }
            "POST /api/share" -> {
                val o = JSONObject(String(req.body))
                val layout = onMain(2_000) { done: (UiLayout?) -> Unit -> done(host.controller.layout) } ?: UiLayout("")
                val json = ShareLayout.shareable(layout).toJson().toString()
                val title = onMain(2_000) { done: (String?) -> Unit -> done(host.controller.gameName) }.orEmpty()
                fun url(withLayout: Boolean) = ShareLayout.formUrl(
                    layout.game, title, o.optString("name").take(60), o.optString("description").take(600), o.optString("author").take(60),
                    if (withLayout) json else null,
                )
                val full = url(withLayout = true)
                val fits = ShareLayout.fitsUrl(full)
                val answer = JSONObject().put("url", if (fits) full else url(withLayout = false))
                sendJson(out, answer.put("layout", json).put("layoutInUrl", fits))
            }
            else -> send(out, 404, "text/plain", "not found".toByteArray())
        }
    }

    private fun state(): JSONObject {
        val elements = onMain(4_000) { done: (UiElementList?) -> Unit -> host.controller.requestElements { done(it) } }
        return onMain(2_000) { done: (JSONObject?) -> Unit ->
            val c = host.controller
            done(
                JSONObject()
                    .put("game", c.layout.game)
                    .put("title", c.gameName)
                    .put("engine", c.engine)
                    .put("window", JSONObject().put("w", host.windowW).put("h", host.windowH))
                    .put("top", JSONArray(host.topRect.toList()))
                    .put("bottom", JSONArray(host.bottomRect.toList()))
                    .put(
                        "caps",
                        JSONObject()
                            .put("elements", c.canEdit)
                            .apply { UiBackgroundMode.entries.forEach { put(it.id, c.supports(it)) } },
                    )
                    .put("layout", c.layout.toJson())
                    .put("hasImage", c.layout.background.image)
                    .put(
                        "widgetTypes",
                        JSONArray().apply {
                            UiWidgetType.entries.forEach { put(JSONObject().put("id", it.id).put("label", it.label)) }
                        },
                    )
                    .put("elements", JSONArray().apply { elements?.elements?.forEach { e -> put(elementJson(e)) } }),
            )
        } ?: JSONObject().put("error", "busy")
    }

    private fun elementJson(e: app.gamenative.duo.ui.UiElement) = JSONObject()
        .put("id", e.id).put("name", e.name).put("parent", e.parent).put("depth", e.depth).put("screen", e.screen.id)
        .put("x", e.rect.left.toDouble()).put("y", e.rect.top.toDouble())
        .put("w", e.rect.width().toDouble()).put("h", e.rect.height().toDouble())
        .put("graphic", e.graphic)

    /** Runs [block] on the main thread and waits for the value it passes to its callback (null on timeout). */
    private fun <T> onMain(timeoutMs: Long, block: (done: (T?) -> Unit) -> Unit): T? {
        val result = AtomicReference<T?>()
        val latch = CountDownLatch(1)
        main.post {
            try {
                block { v ->
                    result.set(v)
                    latch.countDown()
                }
            } catch (t: Throwable) {
                Timber.tag(DualScreenHost.TAG).w(t, "PC editor action failed")
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result.get()
    }

    private fun onMainUnit(block: () -> Unit) {
        onMain(3_000) { done: (Boolean?) -> Unit ->
            block()
            done(true)
        }
    }

    private fun asset(out: OutputStream, path: String, type: String) {
        val bytes = context.assets.open(path).use { it.readBytes() }
        send(out, 200, type, bytes)
    }

    private fun sendJson(out: OutputStream, o: JSONObject) = send(out, 200, "application/json", o.toString().toByteArray())

    private fun send(out: OutputStream, status: Int, type: String, body: ByteArray) {
        val reason = when (status) {
            200 -> "OK"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "Error"
        }
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    companion object {
        const val PORT_FIRST = 8642
        private const val MAX_BODY = 16 * 1024 * 1024
        private const val MAX_BAD_CODES = 20
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        private fun randomCode(): String {
            val r = SecureRandom()
            return (1..6).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
        }

        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }

        private fun isFree(port: Int): Boolean = try {
            ServerSocket(port).close()
            true
        } catch (t: Throwable) {
            false
        }

        /** The device's IPv4 address on Wi-Fi (or any non-loopback interface). */
        fun lanAddress(): InetAddress? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        } catch (t: Throwable) {
            null
        }
    }
}
