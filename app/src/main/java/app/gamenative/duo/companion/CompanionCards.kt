package app.gamenative.duo.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.ui.DuoUi
import kotlin.math.roundToInt
import timber.log.Timber

/** What a card view needs from its companion and window. */
internal class CardEnv(
    val context: Context,
    val session: CompanionSession,
    val host: CompanionHost,
    /** The card is the only one on its page: images fill the page. */
    val alone: Boolean,
)

/** A built card: its view, the store keys it reads, and how to refresh it when one of them changes. */
internal class CardBinding(val view: View, val keys: Set<String>, val refresh: () -> Unit = {}) {
    /** True when any of [changed] is a key this card reads, or lies under one (a list's elements). */
    fun uses(changed: Set<String>): Boolean = keys.isNotEmpty() && changed.any { c -> c in keys || keys.any { c.startsWith("$it.") } }
}

/**
 * Native views for the v1 card types (duo/docs/companions.md). Plain views, drawn only when something changes; the
 * timer is the only one that ticks (once a second, only while it runs and is on screen).
 */
internal object CompanionCards {
    fun build(env: CardEnv, card: CompanionCard): CardBinding = try {
        when (card.type) {
            CardType.TEXT -> text(env, card)
            CardType.CHECKLIST -> checklist(env, card)
            CardType.NOTES -> notes(env, card)
            CardType.TIMER -> timer(env, card)
            CardType.IMAGE, CardType.MAP -> image(env, card)
            CardType.VALUE -> value(env, card)
            CardType.PROGRESS -> progress(env, card)
            CardType.LIST -> list(env, card)
            CardType.UNSUPPORTED -> {
                val text = "This card (\"${card.typeName}\") needs a newer GameNative Duo."
                CardBinding(frame(env, card, note(env.context, text)), emptySet())
            }
        }
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).e(t, "Companion card %s failed", card.id)
        CardBinding(frame(env, card, note(env.context, "This card could not be shown.")), emptySet())
    }

    // ---- text ------------------------------------------------------------------------------------------------------

    private fun text(env: CardEnv, card: CompanionCard): CardBinding {
        val ctx = env.context
        val body = bodyText(ctx)
        val box = column(ctx)
        box.addView(body)
        if (card.bool("user")) {
            // The user's own guide (a .txt or .md file they pick); kept with the companion's state.
            val file = env.session.userFile(card.id, "guide.txt")
            val pick = DuoUi.chip(ctx, "") {}
            fun show() {
                env.session.background({ if (file.isFile) file.readText().take(MAX_TEXT) else null }) { t ->
                    body.text =
                        if (t.isNullOrBlank()) card.string("hint") ?: "Pick a guide (.txt or .md file) to read it here." else markdown(t)
                    pick.text = if (t.isNullOrBlank()) "Choose a guide file" else "Replace guide"
                }
            }
            pick.setOnClickListener {
                env.host.pickText { bytes ->
                    if (bytes != null) {
                        env.session.background({
                            file.parentFile?.mkdirs()
                            file.writeBytes(bytes)
                        }) { show() }
                    }
                }
            }
            box.addView(pick, wideChip(ctx))
            show()
            return CardBinding(frame(env, card, box), emptySet())
        }
        val template = card.string("text") ?: card.string("file")?.let { env.session.files.text(it) }?.take(MAX_TEXT).orEmpty()
        val keys = CompanionData.keysOf(template)
        val refresh = { body.text = markdown(env.session.data.format(template)) }
        refresh()
        return CardBinding(frame(env, card, box), keys, refresh)
    }

    /** `#`/`##` headings, `-`/`*` bullets and `**bold**`: enough for guides, nothing that could load anything. */
    fun markdown(source: String): CharSequence {
        val out = SpannableStringBuilder()
        source.lines().forEachIndexed { i, raw ->
            if (i > 0) out.append('\n')
            var line = raw.trimEnd()
            val start = out.length
            var size = 1f
            when {
                line.startsWith("## ") -> {
                    line = line.removePrefix("## ")
                    size = 1.15f
                }
                line.startsWith("# ") -> {
                    line = line.removePrefix("# ")
                    size = 1.3f
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> line = "•  " + line.trimStart().substring(2)
            }
            var rest = line
            while (true) {
                val a = rest.indexOf("**")
                val b = if (a >= 0) rest.indexOf("**", a + 2) else -1
                if (a < 0 || b < 0) {
                    out.append(rest)
                    break
                }
                out.append(rest, 0, a)
                val boldStart = out.length
                out.append(rest, a + 2, b)
                out.setSpan(StyleSpan(Typeface.BOLD), boldStart, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                rest = rest.substring(b + 2)
            }
            if (size != 1f) {
                out.setSpan(RelativeSizeSpan(size), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }

    // ---- checklist -------------------------------------------------------------------------------------------------

    private data class Item(val id: String, val text: String, val group: String, val user: Boolean)

    private fun checklist(env: CardEnv, card: CompanionCard): CardBinding {
        val ctx = env.context
        val data = env.session.data
        val doneKey = "user.${card.id}.done"
        val itemsKey = "user.${card.id}.items"
        val fixed = fixedItems(env, card)
        val box = column(ctx)
        val count = DuoUi.label(ctx, "", 13f, DuoUi.MUTED)
        val rows = column(ctx)
        val undo = UndoBar(ctx)
        box.addView(count)
        box.addView(rows)
        box.addView(undo.view)

        fun done(): Set<String> = (data[doneKey] as? List<*>)?.mapNotNull { it as? String }?.toSet().orEmpty()
        fun userItems(): List<String> = (data[itemsKey] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
        fun items(): List<Item> = fixed + userItems().map { t -> Item("u:$t", t, "", true) }

        lateinit var render: () -> Unit
        render = {
            rows.removeAllViews()
            val all = items()
            val ticked = done()
            count.text = when {
                all.isNotEmpty() -> "${all.count { it.id in ticked }} of ${all.size} done"
                card.bool("userItems") -> "Nothing here yet: add the first thing to do."
                else -> "This list is empty."
            }
            var group: String? = null
            all.forEach { item ->
                if (item.group.isNotEmpty() && item.group != group) {
                    rows.addView(
                        DuoUi.label(ctx, item.group, 14f, DuoUi.ACCENT, bold = true).apply {
                            setPadding(0, dp(ctx, 10), 0, dp(ctx, 2))
                        },
                    )
                }
                group = item.group
                val on = item.id in ticked
                val row = TextView(ctx).apply {
                    text = (if (on) "☑  " else "☐  ") + item.text
                    textSize = 16f
                    setTextColor(if (on) DuoUi.MUTED else Color.WHITE)
                    minHeight = dp(ctx, 48)
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    setOnClickListener {
                        val now = done()
                        env.session.setUser(doneKey, (if (item.id in now) now - item.id else now + item.id).toList())
                    }
                    if (item.user) {
                        // Long press removes an item the user added (undoable).
                        setOnLongClickListener {
                            val before = userItems()
                            env.session.setUser(itemsKey, before - item.text)
                            undo.show("Removed \"${item.text.take(30)}\"") { env.session.setUser(itemsKey, before) }
                            true
                        }
                    }
                }
                rows.addView(row)
            }
        }
        val actions = chipRow(ctx)
        if (card.bool("userItems")) {
            actions.addView(
                DuoUi.chip(ctx, "+ Add item") {
                    env.host.editText("New item", "", multiLine = false) { t ->
                        if (t.isNotBlank()) env.session.setUser(itemsKey, userItems() + t.trim().take(200))
                    }
                },
                chipParams(ctx),
            )
        }
        actions.addView(
            DuoUi.chip(ctx, "Untick all") {
                val before = done().toList()
                if (before.isNotEmpty()) {
                    env.session.setUser(doneKey, emptyList<String>())
                    undo.show("Unticked ${before.size}") { env.session.setUser(doneKey, before) }
                }
            },
            chipParams(ctx),
        )
        box.addView(actions)
        render()
        return CardBinding(frame(env, card, box), setOf(doneKey, itemsKey)) { render() }
    }

    private fun fixedItems(env: CardEnv, card: CompanionCard): List<Item> {
        val out = mutableListOf<Item>()
        card.list("items").forEach { v ->
            when (v) {
                is String -> out += Item(v, v, "", false)
                is Map<*, *> -> {
                    val text = v["text"]?.toString() ?: return@forEach
                    out += Item(v["id"]?.toString() ?: text, text, v["group"]?.toString().orEmpty(), false)
                }
            }
        }
        // A text file: one item per line, "# Name" starts a group.
        card.string("file")?.let { env.session.files.text(it) }?.let { text ->
            var group = ""
            text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                if (line.startsWith("#")) {
                    group = line.trimStart('#').trim()
                } else {
                    out +=
                        Item("$group/$line", line.removePrefix("- "), group, false)
                }
            }
        }
        return out.distinctBy { it.id }.take(MAX_ROWS)
    }

    // ---- notes -----------------------------------------------------------------------------------------------------

    private fun notes(env: CardEnv, card: CompanionCard): CardBinding {
        val ctx = env.context
        val key = "user.${card.id}.text"
        val box = column(ctx)
        val shown = bodyText(ctx).apply {
            minHeight = dp(ctx, 96)
            isClickable = true
        }
        val hint = card.string("hint") ?: "Tap to write notes for this game."
        fun render() {
            val t = env.session.data[key] as? String
            shown.text = if (t.isNullOrBlank()) hint else t
            shown.setTextColor(if (t.isNullOrBlank()) DuoUi.MUTED else Color.WHITE)
        }
        shown.setOnClickListener {
            env.host.editText(card.title.ifEmpty { "Notes" }, env.session.data[key] as? String ?: "", multiLine = true) { t ->
                env.session.setUser(key, t.take(MAX_TEXT).takeIf { it.isNotBlank() })
            }
        }
        box.addView(shown)
        render()
        return CardBinding(frame(env, card, box), setOf(key)) { render() }
    }

    // ---- timer -----------------------------------------------------------------------------------------------------

    private fun timer(env: CardEnv, card: CompanionCard): CardBinding {
        val ctx = env.context
        val key = "user.${card.id}${TimerState.KEY_SUFFIX}"
        val countdown = card.string("mode") == "countdown"
        val defaultLength = ((card.number("seconds") ?: 300.0) * 1000).toLong().coerceIn(1000, 24 * 3600_000L)
        val box = column(ctx)
        val clock = TextView(ctx).apply {
            textSize = 44f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val laps = DuoUi.label(ctx, "", 13f, DuoUi.MUTED).apply {
            maxLines = 4
            gravity = Gravity.CENTER
        }
        val undo = UndoBar(ctx)
        val startChip = DuoUi.chip(ctx, "Start") {}
        val secondChip = DuoUi.chip(ctx, "") {}
        val resetChip = DuoUi.chip(ctx, "Reset") {}
        fun state() = TimerState.from(env.session.data[key]).let {
            if (countdown &&
                it.lengthMs == 0L
            ) {
                it.copy(lengthMs = defaultLength)
            } else {
                it
            }
        }
        fun save(t: TimerState) = env.session.setUser(key, t.toValue())

        val ticker = object : Runnable {
            override fun run() {
                val t = state()
                val now = System.currentTimeMillis()
                val elapsed = t.elapsedAt(now)
                if (countdown) {
                    val left = t.lengthMs - elapsed
                    if (left <= 0 && t.running) {
                        save(t.paused(now).copy(elapsedMs = t.lengthMs))
                        clock.text = "Time's up"
                        clock.setTextColor(DuoUi.ACCENT)
                        return
                    }
                    clock.text = CompanionData.duration((left + 999) / 1000)
                    clock.setTextColor(if (left <= 0) DuoUi.ACCENT else Color.WHITE)
                } else {
                    clock.text = CompanionData.duration(elapsed / 1000)
                }
                clock.removeCallbacks(this)
                // Once a second, only while running and on screen (the view drops callbacks when detached).
                if (t.running && clock.isAttachedToWindow) clock.postDelayed(this, 1000 - (elapsed % 1000))
            }
        }
        fun render() {
            val t = state()
            startChip.text = if (t.running) {
                "Pause"
            } else if (t.elapsedMs > 0) {
                "Resume"
            } else {
                "Start"
            }
            startChip.on = t.running
            if (countdown) {
                secondChip.text = "+1 min"
                secondChip.isEnabled = !t.running
            } else {
                secondChip.text = "Lap"
                secondChip.isEnabled = t.running
            }
            laps.text =
                t.laps.takeLast(3).mapIndexed { i, ms ->
                    "Lap ${t.laps.size - minOf(3, t.laps.size) + i + 1}  ${CompanionData.duration(
                        ms / 1000,
                    )}"
                }
                    .joinToString("\n")
            laps.visibility = if (t.laps.isEmpty()) View.GONE else View.VISIBLE
            if (countdown) {
                val left = t.lengthMs - t.elapsedAt(System.currentTimeMillis())
                if (left <= 0 && !t.running) {
                    clock.text = "Time's up"
                    clock.setTextColor(DuoUi.ACCENT)
                    return
                }
            }
            clock.setTextColor(Color.WHITE)
            ticker.run()
        }
        startChip.setOnClickListener {
            val now = System.currentTimeMillis()
            val t = state()
            if (countdown && !t.running && t.elapsedAt(now) >= t.lengthMs) {
                save(t.copy(elapsedMs = 0).started(now))
            } else {
                save(if (t.running) t.paused(now) else t.started(now))
            }
        }
        secondChip.setOnClickListener {
            val t = state()
            if (countdown) {
                save(t.copy(lengthMs = (t.lengthMs + 60_000).coerceAtMost(24 * 3600_000L)))
            } else {
                save(t.lap(System.currentTimeMillis()))
            }
        }
        resetChip.setOnClickListener {
            val before = state()
            if (before.elapsedAt(System.currentTimeMillis()) == 0L && before.laps.isEmpty()) return@setOnClickListener
            save(TimerState(lengthMs = if (countdown) before.lengthMs else 0))
            undo.show("Timer reset") { save(before.paused(System.currentTimeMillis())) }
        }
        clock.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = ticker.run()

                override fun onViewDetachedFromWindow(v: View) {
                    v.removeCallbacks(ticker)
                }
            },
        )
        box.addView(clock)
        box.addView(laps)
        box.addView(
            chipRow(ctx, centered = true).apply {
                addView(startChip, chipParams(ctx))
                addView(secondChip, chipParams(ctx))
                addView(resetChip, chipParams(ctx))
            },
        )
        box.addView(undo.view)
        render()
        return CardBinding(frame(env, card, box), setOf(key)) { render() }
    }

    // ---- image and map ---------------------------------------------------------------------------------------------

    private fun image(env: CardEnv, card: CompanionCard): CardBinding {
        val ctx = env.context
        val view = ZoomImageView(ctx)
        val marker = card.map("marker")
        val mx = marker["x"] as? String
        val my = marker["y"] as? String
        val box = column(ctx)
        // Alone on its page the picture takes all the room left; otherwise "height" dp (300 by default).
        val height = if (env.alone) 0 else dp(ctx, (card.number("height") ?: 300.0).toInt().coerceIn(120, 900))
        box.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height, if (env.alone) 1f else 0f))
        val status = note(ctx, "")
        box.addView(status)
        var onLoaded: (Boolean) -> Unit = {}
        fun load(source: () -> ByteArray?) {
            env.session.background({ source()?.let { CompanionFiles.decodeImage(it) } }) { bmp: Bitmap? ->
                view.setBitmap(bmp)
                status.visibility = if (bmp == null) View.VISIBLE else View.GONE
                onLoaded(bmp != null)
            }
        }
        if (card.bool("user")) {
            val file = env.session.userFile(card.id, "image")
            status.text = card.string("hint") ?: "Pick a picture (a map, a guide page) to keep it here. Pinch to zoom."
            val pick = DuoUi.chip(ctx, "Choose picture") {
                env.host.pickImage { bytes ->
                    if (bytes != null) {
                        env.session.background({
                            file.parentFile?.mkdirs()
                            file.writeBytes(bytes)
                        }) { load { file.readBytes() } }
                    }
                }
            }
            box.addView(pick, wideChip(ctx))
            onLoaded = { shown -> pick.text = if (shown) "Replace picture" else "Choose picture" }
            load { if (file.isFile) file.readBytes() else null }
        } else {
            val path = card.string("image").orEmpty()
            status.text = "Missing picture \"$path\"."
            load { env.session.files.read(path) }
        }
        val keys = setOfNotNull(mx, my)
        val refresh = {
            val x = mx?.let { env.session.data.number("{$it}") }
            val y = my?.let { env.session.data.number("{$it}") }
            view.setMarker(x?.toFloat(), y?.toFloat())
        }
        refresh()
        val outer = frame(env, card, box)
        if (env.alone) (outer as? LinearLayout)?.let { stretch(it, box) }
        return CardBinding(outer, keys, refresh)
    }

    // ---- live values -----------------------------------------------------------------------------------------------

    private fun value(env: CardEnv, card: CompanionCard): CardBinding {
        val ctx = env.context
        val template = card.string("value").orEmpty()
        val label = card.string("label").orEmpty()
        val box = column(ctx)
        val big = TextView(ctx).apply {
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        if (label.isNotEmpty()) box.addView(DuoUi.label(ctx, label, 13f, DuoUi.MUTED))
        box.addView(big)
        val refresh = { big.text = env.session.data.format(template) }
        refresh()
        return CardBinding(frame(env, card, box), CompanionData.keysOf(template)) { refresh() }
    }

    private fun progress(env: CardEnv, card: CompanionCard): CardBinding {
        val ctx = env.context
        val valueSpec = card.props["value"]
        val maxSpec = card.props["max"] ?: 100L
        val box = column(ctx)
        val label = DuoUi.label(ctx, "", 14f, Color.WHITE)
        val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(DuoUi.ACCENT)
        }
        box.addView(label)
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 16)))
        val refresh = {
            val data = env.session.data
            val v = data.number(valueSpec)
            val m = data.number(maxSpec)
            val name = card.string("label").orEmpty()
            label.text =
                if (v == null ||
                    m == null
                ) {
                    "$name  ${CompanionData.MISSING}"
                } else {
                    "$name  ${CompanionData.formatValue(v, "")} / ${CompanionData.formatValue(m, "")}"
                }
            bar.progress = if (v == null || m == null || m <= 0) 0 else ((v / m) * 1000).roundToInt().coerceIn(0, 1000)
        }
        refresh()
        val keys = CompanionData.keysOf(valueSpec as? String) + CompanionData.keysOf(maxSpec as? String)
        return CardBinding(frame(env, card, box), keys) { refresh() }
    }

    private fun list(env: CardEnv, card: CompanionCard): CardBinding {
        val ctx = env.context
        val key = card.string("items").orEmpty()
        val template = card.string("text") ?: "{.}"
        val box = column(ctx)
        val refresh = {
            box.removeAllViews()
            val items = env.session.data[key] as? List<*>
            if (items.isNullOrEmpty()) {
                box.addView(DuoUi.label(ctx, card.string("empty") ?: "Nothing yet.", 14f, DuoUi.MUTED))
            } else {
                items.take(MAX_ROWS).forEach { item ->
                    box.addView(
                        DuoUi.label(ctx, env.session.data.format(template, item), 15f, Color.WHITE).apply {
                            minHeight = dp(ctx, 32)
                            gravity = Gravity.CENTER_VERTICAL
                        },
                    )
                }
            }
        }
        refresh()
        return CardBinding(frame(env, card, box), setOf(key) + CompanionData.keysOf(template).filter { '.' in it }) { refresh() }
    }

    // ---- shared pieces ---------------------------------------------------------------------------------------------

    /** The card's chrome: a dark rounded panel with its title. */
    private fun frame(env: CardEnv, card: CompanionCard, content: View): View {
        val ctx = env.context
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(CARD)
                cornerRadius = dp(ctx, 16).toFloat()
            }
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            if (card.title.isNotEmpty()) {
                addView(DuoUi.label(ctx, card.title, 16f, Color.WHITE, bold = true).apply { setPadding(0, 0, 0, dp(ctx, 6)) })
            }
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun stretch(outer: LinearLayout, content: View) {
        content.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        outer.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun column(ctx: Context) = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    private fun bodyText(ctx: Context) = TextView(ctx).apply {
        textSize = 16f
        setTextColor(Color.WHITE)
        setLineSpacing(0f, 1.15f)
    }

    private fun note(ctx: Context, text: String) = DuoUi.label(ctx, text, 14f, DuoUi.MUTED).apply { maxLines = 4 }

    /** A row of chips. Not baseline-aligned: that shifts pill buttons down and clips their bottoms. */
    private fun chipRow(ctx: Context, centered: Boolean = false) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        gravity = if (centered) Gravity.CENTER else Gravity.CENTER_VERTICAL
    }

    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun chipParams(ctx: Context) = LinearLayout.LayoutParams(WRAP, WRAP).apply {
        setMargins(0, dp(ctx, 6), dp(ctx, 8), dp(ctx, 2))
    }

    private fun wideChip(ctx: Context) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WRAP).apply {
        setMargins(0, dp(ctx, 8), 0, 0)
    }

    internal fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).roundToInt()

    /** "Removed x · Undo" for a few seconds after a destructive tap. */
    private class UndoBar(ctx: Context) {
        private var action: (() -> Unit)? = null
        private val label = DuoUi.label(ctx, "", 14f, DuoUi.MUTED)
        private val hide = Runnable { view.visibility = View.GONE }
        val view: LinearLayout = chipRow(ctx).apply {
            visibility = View.GONE
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                DuoUi.chip(ctx, "Undo") {
                    action?.invoke()
                    action = null
                    visibility = View.GONE
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        fun show(text: String, undo: () -> Unit) {
            label.text = text
            action = undo
            view.visibility = View.VISIBLE
            view.removeCallbacks(hide)
            view.postDelayed(hide, UNDO_MS)
        }
    }

    private val CARD = Color.rgb(20, 24, 40)
    private const val MAX_TEXT = 200_000
    private const val MAX_ROWS = 300
    private const val UNDO_MS = 6000L
}
