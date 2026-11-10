package app.gamenative.duo.pc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import app.gamenative.BuildConfig
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.bridge.BridgePackets
import app.gamenative.duo.community.ShareLayout
import app.gamenative.duo.dev.DevSession
import app.gamenative.duo.ui.EditCapabilities
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
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    /** Developer mode for this session, or null when it is off (the developer endpoints then answer 403). */
    val dev: DevSession? get() = null
}

/**
 * The PC editor: a small web server on the device's Wi-Fi address while the user has it open, serving a web app
 * (assets/duo/pc-editor/) that shows both screens and edits the same layout as the on-device editor. A browser pairs
 * once with the code shown on the bottom screen and the player's Allow on the device, then every request carries the
 * session token it was given ([PcPairing]). The server listens only on the address it shows, and never runs in the
 * background: started from the bottom screen's menu, stopped with it or with the game.
 */
internal class PcEditorServer(private val context: Context, private val host: PcEditorHost) {
    val pairing = PcPairing()
    val code: String get() = pairing.code
    var port: Int = 0
        private set

    /** The address the server listens on (the Wi-Fi address when it started), or null without one. */
    private var address: InetAddress? = null

    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(2) { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "GNDuo-pc").apply { isDaemon = true }
    }
    private var socket: ServerSocket? = null
    @Volatile var onStopped: (() -> Unit)? = null

    /** The address to type on the PC, or null when the device has no Wi-Fi address. */
    val url: String? get() = address?.let { "http://${it.hostAddress}:$port/" }

    fun start(): Boolean = try {
        val s = ServerSocket()
        s.reuseAddress = true
        // Only the Wi-Fi address shown to the player, not every interface (mobile data, VPN). Without Wi-Fi nothing
        // can reach the editor anyway: loopback, so the card can say "connect to Wi-Fi".
        val lan = lanAddress()
        val bind = lan ?: InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        s.bind(InetSocketAddress(bind, PORT_FIRST).takeIf { isFree(bind, PORT_FIRST) } ?: InetSocketAddress(bind, 0))
        address = lan
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
                route(req, out, c.inetAddress?.hostAddress.orEmpty())
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

    private fun route(req: Request, out: OutputStream, from: String) {
        // The page itself is public (it asks for the code); everything else needs a session token from pairing.
        if (req.method == "GET" && (req.path == "/" || req.path == "/index.html")) {
            return asset(out, "duo/pc-editor/index.html", "text/html; charset=utf-8")
        }
        val route = "${req.method} ${req.path}"
        if (route == "POST /api/pair") return pair(req, out, from)
        if (!pairing.valid(from, req.headers["x-duo-token"])) {
            return send(out, 403, "application/json", """{"error":"not paired"}""".toByteArray())
        }
        if (route == "GET /api/info") return sendJson(out, info())
        if (route in DEV_ROUTES) {
            val dev = onMain(1_000) { done: (DevSession?) -> Unit -> done(host.dev) }
                ?: return send(out, 403, "application/json", JSONObject().put("error", "developer mode is off").toString().toByteArray())
            val api = PcDevApi(context, host, dev) { ms, block -> onMain(ms) { done: (Any?) -> Unit -> block(done) } }
            api.handle(route, req.query, String(req.body))?.let { (status, o) ->
                return send(out, status, "application/json", o.toString().toByteArray())
            }
        }
        when (route) {
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
            // Region view: the user's regions are the elements (see UiRegion).
            "POST /api/regions/add" -> {
                val id = onMain(2_000) { done: (String?) -> Unit -> done(host.controller.addRegion()) }
                sendJson(out, JSONObject().put("ok", id != null).put("id", id ?: JSONObject.NULL))
            }
            "POST /api/regions/remove" -> {
                val id = JSONObject(String(req.body)).getString("id")
                onMainUnit { host.controller.removeRegion(id) }
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

    /** `POST /api/pair {"code"}` (duo/docs/duo-api.md, "Pairing"): the only request that carries the code. */
    private fun pair(req: Request, out: OutputStream, from: String) {
        val given = try {
            JSONObject(String(req.body)).optString("code")
        } catch (e: Exception) {
            ""
        }
        fun answer(status: Int, o: JSONObject) = send(out, status, "application/json", o.toString().toByteArray())
        when (val r = pairing.pair(from, given)) {
            is PcPairing.Result.Paired -> answer(200, JSONObject().put("token", r.token))
            PcPairing.Result.Confirm -> answer(202, JSONObject().put("status", "confirm"))
            PcPairing.Result.Refused -> answer(403, JSONObject().put("error", "refused on the device"))
            PcPairing.Result.WrongCode -> answer(403, JSONObject().put("error", "wrong code"))
            is PcPairing.Result.Locked -> {
                Timber.tag(DualScreenHost.TAG).w("PC editor: %s is locked out for %d s", from, r.seconds)
                answer(429, JSONObject().put("error", "too many wrong codes").put("retryAfter", r.seconds))
            }
        }
    }

    /** `GET /api/info` (duo/docs/duo-api.md): which server, the game and the mod; `dev` says whether the developer tabs work. */
    private fun info(): JSONObject = onMain(2_000) { done: (JSONObject?) -> Unit ->
        val c = host.controller
        val dev = host.dev
        done(
            JSONObject().put("api", 1).put("server", "device").put("app", BuildConfig.VERSION_NAME).put("sdk", BridgePackets.SDK_VERSION)
                .put("dev", dev != null).put("game", c.layout.game).put("title", c.gameName)
                .put(
                    "mod",
                    JSONObject().put("engine", c.engine).put("sdk", dev?.bridge?.sdkVersion.orEmpty())
                        .put("caps", dev?.bridge?.capabilities ?: 0).put("connected", c.capabilities.source.name == "MOD"),
                )
                .apply { dev?.devFiles?.takeIf { it.isNotEmpty() }?.let { put("devFiles", JSONArray(it)) } },
        )
    } ?: JSONObject().put("error", "busy")

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
                            .put("elements", c.capabilities.editable)
                            .put("owner", c.capabilities.owner)
                            .put("source", c.capabilities.source.name.lowercase())
                            .put("reasonOff", c.capabilities.reasonOff ?: JSONObject.NULL)
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
                    .put("elements", JSONArray().apply { elements?.elements?.forEach { e -> put(elementJson(e, c.capabilities)) } }),
            )
        } ?: JSONObject().put("error", "busy")
    }

    /** One element for the web editor, with the features it allows and the same limits sentence the device shows. */
    private fun elementJson(e: app.gamenative.duo.ui.UiElement, caps: EditCapabilities) = JSONObject()
        .put("id", e.id).put("name", e.name).put("parent", e.parent).put("depth", e.depth).put("screen", e.screen.id)
        .put("x", e.rect.left.toDouble()).put("y", e.rect.top.toDouble())
        .put("w", e.rect.width().toDouble()).put("h", e.rect.height().toDouble())
        .put("graphic", e.graphic)
        .put("hasChildren", e.hasChildren)
        .put("present", e.present)
        .put("move", e.canMove)
        .put("scale", e.scaleRange?.let { JSONObject().put("min", it.start.toDouble()).put("max", it.endInclusive.toDouble()) } ?: false)
        .apply {
            if (e.whenText.isNotEmpty()) put("when", e.whenText)
            caps.limits(e)?.let { put("limits", it) }
            e.screens?.let { s -> put("screens", JSONArray().apply { s.forEach { put(it.id) } }) }
            if (e.options.isNotEmpty()) {
                put(
                    "options",
                    JSONArray().apply {
                        e.options.forEach { o ->
                            put(
                                JSONObject().put("id", o.id).put("label", o.label)
                                    .put("values", JSONArray().apply { o.values.forEach { (v, l) -> put(JSONArray().put(v).put(l)) } }),
                            )
                        }
                    },
                )
            }
        }

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
            202 -> "Accepted"
            403 -> "Forbidden"
            429 -> "Too Many Requests"
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

        /** Developer endpoints: 403 unless developer mode is on (duo/docs/duo-api.md). */
        private val DEV_ROUTES = setOf(
            "GET /api/elements", "POST /api/elements/refresh", "GET /api/bridge", "POST /api/bridge/send", "POST /api/command",
            "GET /api/companion/state", "POST /api/companion/subscribe", "POST /api/touch", "GET /api/events", "GET /api/logs",
            "POST /api/check", "GET /api/check",
        )
        private const val MAX_BODY = 16 * 1024 * 1024

        private fun isFree(address: InetAddress, port: Int): Boolean = try {
            ServerSocket(port, 1, address).close()
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
