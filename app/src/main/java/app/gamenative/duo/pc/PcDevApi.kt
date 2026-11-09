package app.gamenative.duo.pc

import android.content.Context
import app.gamenative.duo.bridge.DuoBridge
import app.gamenative.duo.dev.DevSession
import app.gamenative.duo.ui.ConformanceCheck
import app.gamenative.duo.ui.UiElementList
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The developer endpoints of the Duo API on the device (duo/docs/duo-api.md, marked *dev*): inspector, bridge,
 * console, logs, conformance. Served by [PcEditorServer] only while developer mode is on ([PcEditorHost.dev]).
 */
internal class PcDevApi(
    private val context: Context,
    private val host: PcEditorHost,
    private val dev: DevSession,
    /** Runs a block on the main thread and waits for its value (the server's own helper). */
    private val onMain: (Long, (done: (Any?) -> Unit) -> Unit) -> Any?,
) {
    /** The answer to [route] (`"GET /api/bridge"`), or null when it is not a developer endpoint. */
    fun handle(route: String, query: Map<String, String>, body: String): Pair<Int, JSONObject>? = when (route) {
        "GET /api/elements", "POST /api/elements/refresh" -> elements()
        "GET /api/bridge" -> 200 to dev.bridgeJson()
        "POST /api/bridge/send" -> {
            when (JSONObject(body.ifEmpty { "{}" }).optString("type")) {
                "RULES_CHANGED" -> {
                    onMain(2_000) { done -> done(dev.bridge.sendRulesChanged()) }
                    200 to ok()
                }
                "DUMP_ELEMENTS" -> elements().let { 200 to ok() }
                else -> 400 to JSONObject().put("error", "send RULES_CHANGED or DUMP_ELEMENTS")
            }
        }
        "POST /api/command" -> {
            val o = JSONObject(body.ifEmpty { "{}" })
            val (status, message) = dev.command(o.optString("cmd"), o.optJSONObject("args"))
            200 to JSONObject().put("status", status).put("message", message)
        }
        "GET /api/companion/state" -> 200 to dev.stateJson(query["prefix"].orEmpty())
        "POST /api/companion/subscribe" -> {
            val keys = JSONObject(body.ifEmpty { "{}" }).optJSONArray("keys") ?: JSONArray()
            val list = (0 until keys.length()).map { keys.getString(it) }.filter { it.isNotBlank() }
            onMain(2_000) { done -> done(dev.subscribe(list)) }
            200 to ok()
        }
        "POST /api/touch" -> touch(JSONObject(body.ifEmpty { "{}" }))
        "GET /api/events", "GET /api/logs" -> {
            val since = query["since"]?.toLongOrNull() ?: 0L
            val wait = (query["wait"]?.toLongOrNull() ?: 0L).coerceIn(0, 25) * 1000
            200 to dev.since(since, wait, if (route.endsWith("logs")) "log" else null)
        }
        "POST /api/check" -> startCheck()
        "GET /api/check" -> 200 to JSONObject().put("running", dev.checkRunning).put("progress", dev.checkProgress)
            .put("report", dev.checkReport ?: JSONObject.NULL)
        else -> null
    }

    private fun ok() = JSONObject().put("ok", true)

    private fun elements(): Pair<Int, JSONObject> {
        val list = onMain(4_000) { done -> host.controller.requestElements { done(it) } } as UiElementList?
        val file = onMain(1_000) { done -> done(host.controller.elementsFile) } as File?
        if (list == null || file == null || !file.isFile) return 504 to JSONObject().put("error", "the mod did not answer")
        val o = JSONObject(file.readText())
        File(file.parentFile, "features.json").takeIf { it.isFile }?.let { o.put("features", JSONObject(it.readText())) }
        return 200 to o
    }

    /** A bottom-screen finger in bottom-screen pixels: native touch through the mod's bridge. */
    private fun touch(o: JSONObject): Pair<Int, JSONObject> {
        if (!dev.bridge.hasMod(DuoBridge.CAP_TOUCH)) return 409 to JSONObject().put("error", "no mod with native touch connected")
        val (sw, sh) = onMain(1_000) { done -> done(host.bottomSize) } as Pair<*, *>? ?: return 503 to JSONObject().put("error", "busy")
        val r = onMain(1_000) { done -> done(host.bottomRect) } as FloatArray? ?: return 503 to JSONObject().put("error", "busy")
        val x = r[0] + o.optDouble("x", 0.0).toFloat() * r[2] / (sw as Int)
        val y = r[1] + o.optDouble("y", 0.0).toFloat() * r[3] / (sh as Int)
        val pointer = o.optInt("pointer", 0)
        onMain(1_000) { done ->
            when (o.optString("action", "tap")) {
                "down" -> dev.bridge.sendTouch(DuoBridge.TOUCH_DOWN, pointer, x, y)
                "move" -> dev.bridge.sendTouch(DuoBridge.TOUCH_MOVE, pointer, x, y)
                "up" -> dev.bridge.sendTouch(DuoBridge.TOUCH_UP, pointer, x, y)
                else -> {
                    dev.bridge.sendTouch(DuoBridge.TOUCH_DOWN, pointer, x, y)
                    dev.bridge.sendTouch(DuoBridge.TOUCH_UP, pointer, x, y)
                }
            }
            done(true)
        }
        return 200 to ok()
    }

    private fun startCheck(): Pair<Int, JSONObject> {
        if (dev.checkRunning) return 409 to JSONObject().put("error", "a check is running")
        dev.checkRunning = true
        dev.checkReport = null
        onMain(2_000) { done ->
            ConformanceCheck(
                context, host.controller, host.topRect, host.bottomRect,
                onProgress = { p ->
                    dev.checkProgress = p
                    dev.add("check", JSONObject().put("event", "progress").put("text", p))
                },
                onDone = { report ->
                    dev.checkReport = reportJson(report)
                    dev.checkProgress = if (report.passed) "All checks passed" else "Some checks failed"
                    dev.checkRunning = false
                    dev.add("check", JSONObject().put("event", "done").put("passed", report.passed))
                },
            ).start()
            done(true)
        }
        return 200 to JSONObject().put("started", true)
    }

    private fun reportJson(r: ConformanceCheck.Report) = JSONObject()
        .put("game", host.controller.layout.game)
        .put("engine", host.controller.engine)
        .put("time", System.currentTimeMillis())
        .put("passed", r.passed)
        .put(
            "results",
            JSONArray().apply {
                r.results.forEach {
                    put(
                        JSONObject().put("id", it.id).put("name", it.name).put("feature", it.feature.name.lowercase())
                            .put("passed", it.passed)
                            .apply { if (!it.passed) put("detail", it.detail) },
                    )
                }
            },
        )
        .put("skipped", JSONArray(r.skipped))
}
