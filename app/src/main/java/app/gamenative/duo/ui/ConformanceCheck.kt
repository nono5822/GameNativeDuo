package app.gamenative.duo.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.gamenative.duo.DualScreenHost
import java.io.File
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Checks that a mod does what its elements declare (`uniform-editing.md`, "Conformance check"): for each element on
 * screen and each feature it allows, apply a test rule, ask for the element list again, compare the rectangle, put
 * the user's rule back. Declarations are what the editor trusts, so a wrong one is a bug in the mod.
 *
 * Runs on the main thread, one test at a time; nothing is saved (rules go to the mod only, the user's layout file is
 * untouched). The report goes to the callback, to logcat (`GNDuo`) and to `files/duo/conformance/<game>.json`.
 */
class ConformanceCheck(
    private val context: Context,
    private val controller: UiLayoutController,
    /** `{x, y, w, h}` of the top and bottom screens in window pixels (for the expected shift). */
    private val topRect: FloatArray,
    private val bottomRect: FloatArray,
    private val onProgress: (String) -> Unit,
    private val onDone: (Report) -> Unit,
) {
    enum class Feature(val label: String) { SCREEN("other screen"), HIDE("hide"), MOVE("move"), RESIZE("resize") }

    data class Result(val id: String, val name: String, val feature: Feature, val passed: Boolean, val detail: String)

    data class Report(val results: List<Result>, val skipped: List<String>) {
        val passed: Boolean get() = results.isNotEmpty() && results.all { it.passed }

        fun lines(): List<String> {
            val checked = results.sortedBy { it.passed }.map { r ->
                val mark = if (r.passed) "✓" else "✗"
                val why = if (r.passed) "" else " (${r.detail})"
                "$mark ${r.name}: ${r.feature.label}$why"
            }
            return checked + skipped.map { "· $it: not on screen, not checked" }
        }
    }

    private data class Test(val e: UiElement, val feature: Feature, val factor: Float = 1f)

    private val main = Handler(Looper.getMainLooper())
    private val tests = ArrayDeque<Test>()
    private val results = ArrayList<Result>()
    private val skipped = ArrayList<String>()
    private var cancelled = false

    fun start() {
        controller.requestElements { list ->
            if (list == null) return@requestElements finish()
            for (e in list.elements) {
                if (!e.graphic || (e.depth == 0 && e.hasChildren)) continue
                if (!e.present) {
                    skipped += e.name
                    continue
                }
                if (tests.size >= MAX_TESTS) break
                planFor(e).forEach { tests += Test(e, it, scaleFactor(e) ?: 1f) }
            }
            next()
        }
    }

    fun cancel() {
        cancelled = true
    }

    private fun next() {
        if (cancelled) return finish()
        val t = tests.removeFirstOrNull() ?: return finish()
        onProgress("Checking ${t.e.name}: ${t.feature.label} (${results.size + 1}/${results.size + tests.size + 1})")
        val original = controller.layout.rule(t.e.id)
        controller.set(t.e.id, testRule(t, original), save = false)
        main.postDelayed({
            controller.requestElements { list ->
                val after = list?.byId(t.e.id)
                val region = if (t.e.screen == UiScreen.TOP) topRect else bottomRect
                val (passed, detail) = if (after == null) false to "gone" else judge(t.feature, t.e, after, region[2], region[3], t.factor)
                results += Result(t.e.id, t.e.name, t.feature, passed, detail)
                controller.set(t.e.id, original, save = false)
                main.postDelayed({ next() }, SETTLE_MS)
            }
        }, SETTLE_MS)
    }

    private fun finish() {
        val report = Report(results.toList(), skipped.toList())
        report.lines().forEach { Timber.tag(DualScreenHost.TAG).i("Conformance %s: %s", controller.layout.game, it) }
        save(report)
        onDone(report)
    }

    private fun save(report: Report) {
        try {
            val dir = File(context.filesDir, "duo/conformance").apply { mkdirs() }
            val json = JSONObject()
                .put("game", controller.layout.game)
                .put("engine", controller.engine)
                .put("time", System.currentTimeMillis())
                .put("passed", report.passed)
                .put(
                    "results",
                    JSONArray().apply {
                        report.results.forEach {
                            val r = JSONObject().put("id", it.id).put("feature", it.feature.name.lowercase())
                            put(r.put("passed", it.passed).apply { if (!it.passed) put("detail", it.detail) })
                        }
                    },
                )
                .put("skipped", JSONArray(report.skipped))
            File(dir, "${controller.layout.game}.json").writeText(json.toString(2))
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Conformance report not saved")
        }
    }

    companion object {
        /** Long enough for ui.json, the bridge, a game frame and the element dump. */
        private const val SETTLE_MS = 400L

        /** Unity games list hundreds of elements: a sample is enough to catch a wrong declaration. */
        private const val MAX_TESTS = 40

        const val SHIFT = 0.1f

        /** The resize to test: 1.25 when the element allows it, else 0.8, else none. */
        fun scaleFactor(e: UiElement): Float? {
            val range = e.scaleRange ?: return null
            return when {
                range.endInclusive >= 1.25f -> 1.25f
                range.start <= 0.8f -> 0.8f
                else -> null
            }
        }

        /** What to check for [e]: every declared feature, nothing it does not allow. */
        fun planFor(e: UiElement): List<Feature> = buildList {
            val other = if (e.screen == UiScreen.TOP) UiScreen.BOTTOM else UiScreen.TOP
            if (e.allows(other) && (e.screen == UiScreen.TOP || e.screen == UiScreen.BOTTOM)) add(Feature.SCREEN)
            if (e.allows(UiScreen.HIDDEN)) add(Feature.HIDE)
            if (e.canMove) add(Feature.MOVE)
            if (scaleFactor(e) != null) add(Feature.RESIZE)
        }

        private fun testRule(t: Test, original: UiRule): UiRule = when (t.feature) {
            Feature.SCREEN -> original.copy(screen = if (t.e.screen == UiScreen.TOP) UiScreen.BOTTOM else UiScreen.TOP, x = 0f, y = 0f)
            Feature.HIDE -> original.copy(screen = UiScreen.HIDDEN)
            Feature.MOVE -> original.copy(screen = pinned(original, t.e), x = original.x + SHIFT)
            Feature.RESIZE -> original.copy(screen = pinned(original, t.e), scale = original.scale * t.factor)
        }

        /** The rule's screen, or the element's own when the rule leaves it to the mod (a move must not change screens). */
        private fun pinned(rule: UiRule, e: UiElement): UiScreen = rule.screen.takeIf { it != UiScreen.DEFAULT } ?: e.screen

        /**
         * Did [after] change as [feature] says? [regionW] x [regionH]: the screen the element was on, in window pixels.
         * Tolerant on amounts (games round, clamp to the screen edge) but not on direction.
         */
        fun judge(
            feature: Feature,
            before: UiElement,
            after: UiElement,
            regionW: Float,
            regionH: Float,
            factor: Float = 1.25f,
        ): Pair<Boolean, String> = when (feature) {
            Feature.SCREEN -> (after.screen != before.screen && after.screen != UiScreen.HIDDEN) to "still on the ${after.screen.id} screen"
            Feature.HIDE -> (after.screen == UiScreen.HIDDEN || after.rect.width() <= 1f || after.rect.height() <= 1f) to "still visible"
            Feature.MOVE -> {
                val moved = after.rect.left - before.rect.left
                val expected = SHIFT * regionW
                (moved > expected * 0.5f && moved < expected * 1.5f) to "moved ${moved.toInt()} px, expected about ${expected.toInt()}"
            }
            Feature.RESIZE -> {
                val ratio = if (before.rect.width() > 0f) after.rect.width() / before.rect.width() else 0f
                (abs(ratio - factor) < 0.12f) to "size ×${"%.2f".format(ratio)}, expected ×$factor"
            }
        }
    }
}
