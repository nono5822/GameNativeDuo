package app.gamenative.duo.companion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.bridge.DuoBridge
import app.gamenative.duo.hotkeys.Hotkey
import app.gamenative.duo.hotkeys.HotkeyGrid
import app.gamenative.duo.hotkeys.HotkeyInput
import app.gamenative.duo.hotkeys.HotkeyMode
import app.gamenative.duo.hotkeys.HotkeyPreset
import app.gamenative.duo.hotkeys.HotkeySize
import app.gamenative.duo.ui.DuoUi
import org.json.JSONObject
import timber.log.Timber

/**
 * The `hotkeys` card: a grid of buttons that press keys, mouse or controller buttons in the game, or send commands
 * to its mod (duo/docs/companions.md). `"user": true` shows the player's own grid (kept in the game's UI layout,
 * edited right here, on the PC editor, shared with layouts); otherwise the card's `buttons` are the author's, fixed.
 *
 * Buttons act on touch-down (no waiting for the finger to lift), each finger on its own button. Leaving the page
 * lets go of everything still held or toggled on, so nothing stays stuck in the game.
 */
internal object HotkeyCard {
    fun build(env: CardEnv, card: CompanionCard): CardBinding {
        val source = if (card.bool("user")) env.session.hotkeys else null
        val fixed = if (source == null) fixedGrid(card) else null
        val ctx = env.context
        val box = CompanionCards.column(ctx)
        val grid = CompanionCards.column(ctx)
        val notice = DuoUi.label(ctx, "", 13f, DuoUi.MUTED).apply {
            visibility = View.GONE
            maxLines = 3
        }
        val undo = CompanionCards.UndoBar(ctx)
        val tools = CompanionCards.chipRow(ctx)
        val presets = CompanionCards.chipRow(ctx)
        val presetsRow = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(presets)
        }
        box.addView(grid)
        box.addView(notice)
        box.addView(undo.view)
        box.addView(tools)
        box.addView(presetsRow)

        val state = State(env, notice)
        var editing = false

        lateinit var render: () -> Unit
        fun current(): HotkeyGrid = source?.grid ?: fixed ?: HotkeyGrid(emptyList())

        fun save(next: HotkeyGrid?, undoText: String? = null) {
            val src = source ?: return
            val before = if (src.custom) src.grid else null
            src.save(next)
            if (undoText != null) undo.show(undoText) { src.save(before) }
            render()
        }

        render = {
            state.releaseAll()
            grid.removeAllViews()
            tools.removeAllViews()
            // While editing, the editor's chips go above the grid (in reach without scrolling); else "Edit" goes below.
            box.removeView(tools)
            box.removeView(presetsRow)
            if (editing) {
                box.addView(presetsRow, 0)
                box.addView(tools, 0)
            } else {
                box.addView(tools)
                box.addView(presetsRow)
            }
            val g = current()
            if (g.buttons.isEmpty() && !editing) {
                grid.addView(CompanionCards.note(ctx, if (source != null) "No buttons yet: tap Edit to add some." else "No buttons."))
            }
            layoutGrid(ctx, grid, g, editing) { index ->
                if (index == null) {
                    // "+": a new button at the end.
                    HotkeyEditorDialog.show(env.host.mainContext, null, g.columns, g.buttons.size + 1, g.buttons.size + 1) { result ->
                        if (result is HotkeyEditorDialog.Result.Saved && g.buttons.size < HotkeyGrid.MAX_BUTTONS) {
                            val list = g.buttons.toMutableList()
                            list.add((result.position - 1).coerceIn(0, list.size), result.hotkey)
                            save(g.copy(buttons = list))
                        }
                    }
                } else {
                    val old = g.buttons[index]
                    HotkeyEditorDialog.show(env.host.mainContext, old, g.columns, index + 1, g.buttons.size) { result ->
                        when (result) {
                            is HotkeyEditorDialog.Result.Saved -> {
                                val list = g.buttons.toMutableList()
                                list.removeAt(index)
                                list.add((result.position - 1).coerceIn(0, list.size), result.hotkey)
                                save(g.copy(buttons = list))
                            }
                            HotkeyEditorDialog.Result.Deleted ->
                                save(g.copy(buttons = g.buttons - old), "Removed \"${old.label.ifEmpty { old.description }.take(24)}\"")
                        }
                    }
                }
            }.forEach { (hotkey, view) -> if (!editing) state.bind(hotkey, view, g.haptics) }
            if (source != null) {
                val params = CompanionCards.chipParams(ctx)
                if (!editing) {
                    tools.addView(
                        DuoUi.chip(ctx, "Edit") {
                            editing = true
                            render()
                        },
                        params,
                    )
                } else {
                    tools.addView(
                        DuoUi.chip(ctx, "Done") {
                            editing = false
                            render()
                        }.apply { on = true },
                        params,
                    )
                    tools.addView(
                        DuoUi.chip(ctx, "${g.columns} across") {
                            val next = if (g.columns >= HotkeyGrid.MAX_COLUMNS) HotkeyGrid.MIN_COLUMNS else g.columns + 1
                            save(g.copy(columns = next))
                        },
                        CompanionCards.chipParams(ctx),
                    )
                    tools.addView(
                        DuoUi.chip(ctx, g.size.label) {
                            save(g.copy(size = HotkeySize.entries[(g.size.ordinal + 1) % HotkeySize.entries.size]))
                        },
                        CompanionCards.chipParams(ctx),
                    )
                    tools.addView(
                        DuoUi.chip(ctx, if (g.haptics) "Vibrate: on" else "Vibrate: off") {
                            save(g.copy(haptics = !g.haptics))
                        },
                        CompanionCards.chipParams(ctx),
                    )
                }
            }
            // A second row while editing: where the buttons came from, and a way back (undoable).
            presets.removeAllViews()
            presetsRow.visibility = if (source != null && editing) View.VISIBLE else View.GONE
            if (source != null && editing) {
                presets.addView(DuoUi.label(ctx, "Start from:", 13f, DuoUi.MUTED), CompanionCards.chipParams(ctx))
                HotkeyPreset.entries.forEach { p ->
                    presets.addView(
                        DuoUi.chip(ctx, p.label) {
                            save(p.grid.copy(columns = g.columns, size = g.size, haptics = g.haptics), "Buttons replaced by \"${p.label}\"")
                        }.apply { on = g.preset == p },
                        CompanionCards.chipParams(ctx),
                    )
                }
            }
            notice.visibility = View.GONE
        }

        val stopObserving = source?.observe { render() }
        box.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}

                override fun onViewDetachedFromWindow(v: View) {
                    // Leaving the page (or the companion closing) lets go of everything this card holds.
                    state.releaseAll()
                    stopObserving?.invoke()
                    box.removeOnAttachStateChangeListener(this)
                }
            },
        )
        render()
        return CardBinding(CompanionCards.frame(env, card, box), emptySet())
    }

    /** The author's own buttons (`buttons`, `columns`, `size` on the card). */
    private fun fixedGrid(card: CompanionCard): HotkeyGrid = try {
        val o = JSONObject()
        o.put("buttons", org.json.JSONArray(card.list("buttons").map { (it as? Map<*, *>)?.let { m -> JSONObject(m) } }))
        card.number("columns")?.let { o.put("columns", it.toInt()) }
        card.string("size")?.let { o.put("size", it) }
        if (card.props["haptics"] == false) o.put("haptics", false)
        HotkeyGrid.fromJson(o)
    } catch (t: Throwable) {
        Timber.tag(DualScreenHost.TAG).w(t, "Hotkeys card %s: unreadable buttons", card.id)
        HotkeyGrid(emptyList())
    }

    /**
     * Rows of [grid]'s buttons, `columns` cells wide (a button too wide for what is left of a row starts the next).
     * In edit mode every button opens its editor ([onEdit] with its index) and a last "+" cell adds one ([onEdit] null).
     */
    private fun layoutGrid(
        ctx: Context,
        parent: LinearLayout,
        grid: HotkeyGrid,
        editing: Boolean,
        onEdit: (Int?) -> Unit,
    ): List<Pair<Hotkey, HotkeyButton>> {
        val out = mutableListOf<Pair<Hotkey, HotkeyButton>>()
        val gap = CompanionCards.dp(ctx, 8)
        val height = CompanionCards.dp(ctx, grid.size.heightDp)
        var row: LinearLayout? = null
        var used = 0
        fun place(view: View, span: Int) {
            val s = span.coerceIn(1, grid.columns)
            if (row == null || used + s > grid.columns) {
                finishRow(ctx, row, grid.columns - used, gap)
                row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    isBaselineAligned = false
                    // Each finger on its own button.
                    isMotionEventSplittingEnabled = true
                }
                parent.addView(
                    row,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply { bottomMargin = gap },
                )
                used = 0
            }
            row!!.addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, s.toFloat()).apply { marginEnd = gap })
            used += s
        }
        grid.buttons.forEachIndexed { i, hotkey ->
            val b = HotkeyButton(ctx, hotkey, grid.size)
            if (editing) {
                b.editing = true
                b.setOnClickListener { onEdit(i) }
            }
            place(b, hotkey.span)
            out += hotkey to b
        }
        if (editing && grid.buttons.size < HotkeyGrid.MAX_BUTTONS) {
            place(
                TextView(ctx).apply {
                    text = "+"
                    textSize = 28f
                    gravity = Gravity.CENTER
                    setTextColor(DuoUi.ACCENT)
                    background = GradientDrawable().apply {
                        cornerRadius = CompanionCards.dp(ctx, 14).toFloat()
                        setStroke(CompanionCards.dp(ctx, 2), DuoUi.ACCENT_DIM)
                    }
                    contentDescription = "Add a button"
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onEdit(null) }
                },
                1,
            )
        }
        finishRow(ctx, row, grid.columns - used, gap)
        return out
    }

    /** The last row keeps its cells' width: empty weight fills what the buttons leave. */
    private fun finishRow(ctx: Context, row: LinearLayout?, free: Int, gap: Int) {
        if (row == null || free <= 0) return
        row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, free.toFloat()).apply { marginEnd = gap })
    }

    /** The buttons' touch handling and what they hold, for one built grid. */
    private class State(private val env: CardEnv, private val notice: TextView) {
        private val held = mutableMapOf<HotkeyButton, Hotkey>()
        private val vibrator: Vibrator? = env.context.getSystemService(Vibrator::class.java)
        private val hideNotice = Runnable { notice.visibility = View.GONE }

        @SuppressLint("ClickableViewAccessibility")
        fun bind(hotkey: Hotkey, view: HotkeyButton, haptics: Boolean) {
            val input = env.session.hotkeyInput
            view.ready = input?.readiness(hotkey.action) == HotkeyInput.Ready.OK
            input?.onCommandProblem = { cmd, status, message -> showProblem(cmd, status, message) }
            view.setOnTouchListener { v, event ->
                try {
                    onTouch(hotkey, v as HotkeyButton, event, haptics)
                } catch (t: Throwable) {
                    Timber.tag(DualScreenHost.TAG).w(t, "Hotkey touch failed")
                }
                true
            }
        }

        private fun onTouch(hotkey: Hotkey, view: HotkeyButton, event: MotionEvent, haptics: Boolean) {
            val input = env.session.hotkeyInput
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // The page scroller must not take this finger away while a key is held.
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    val ready = input?.readiness(hotkey.action) ?: HotkeyInput.Ready.NO_GAME
                    view.ready = ready == HotkeyInput.Ready.OK
                    if (input == null || ready != HotkeyInput.Ready.OK) {
                        show(why(ready))
                        return
                    }
                    if (haptics) buzz()
                    when (hotkey.effectiveMode) {
                        HotkeyMode.TAP -> {
                            input.tap(hotkey.action)
                            view.flash()
                        }
                        HotkeyMode.HOLD -> {
                            input.press(hotkey.action)
                            held[view] = hotkey
                            view.down = true
                        }
                        HotkeyMode.TOGGLE -> if (held.containsKey(view)) {
                            input.release(hotkey.action)
                            held -= view
                            view.down = false
                        } else {
                            input.press(hotkey.action)
                            held[view] = hotkey
                            view.down = true
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (hotkey.effectiveMode == HotkeyMode.HOLD && held.remove(view) != null) {
                        input?.release(hotkey.action)
                        view.down = false
                    }
                }
            }
        }

        fun releaseAll() {
            val input = env.session.hotkeyInput
            held.forEach { (view, hotkey) ->
                input?.release(hotkey.action)
                view.down = false
            }
            held.clear()
        }

        private fun buzz() {
            try {
                val v = vibrator ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                } else {
                    v.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            } catch (t: Throwable) {
                Timber.tag(DualScreenHost.TAG).w(t, "Hotkey vibration failed")
            }
        }

        private fun why(ready: HotkeyInput.Ready) = when (ready) {
            HotkeyInput.Ready.NO_CONTROLLER -> "No controller is connected to the game."
            HotkeyInput.Ready.NEEDS_MOD -> "This button needs the game's dual-screen mod, which is not running."
            else -> "The game is not ready for input."
        }

        private fun showProblem(command: String, status: Int, message: String) {
            val what = when (status) {
                DuoBridge.RESULT_REFUSED -> "Not now"
                DuoBridge.RESULT_UNKNOWN -> "The mod does not know $command"
                else -> "$command failed"
            }
            show(if (message.isBlank()) what else "$what: $message")
        }

        private fun show(text: String) {
            notice.text = text
            notice.visibility = View.VISIBLE
            notice.removeCallbacks(hideNotice)
            notice.postDelayed(hideNotice, NOTICE_MS)
        }
    }

    private const val NOTICE_MS = 4000L
}

/** One hotkey: its icon over its label, lit while held or toggled on, faded when it cannot work right now. */
@SuppressLint("ViewConstructor")
internal class HotkeyButton(context: Context, hotkey: Hotkey, size: HotkeySize) : TextView(context) {
    private val shape = GradientDrawable().apply { cornerRadius = CompanionCards.dp(context, 14).toFloat() }
    private val unflash = Runnable { flashing = false }
    private var flashing = false
        set(value) {
            field = value
            refresh()
        }

    var down = false
        set(value) {
            field = value
            refresh()
        }
    var ready = true
        set(value) {
            field = value
            refresh()
        }
    var editing = false
        set(value) {
            field = value
            refresh()
        }

    init {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        isClickable = true
        isFocusable = true
        background = shape
        val pad = CompanionCards.dp(context, 4)
        setPadding(pad, pad, pad, pad)
        val label = hotkey.label.ifEmpty { hotkey.description }
        val labelSp = if (size == HotkeySize.SMALL) 12 else 14
        val iconSp = when (size) {
            HotkeySize.SMALL -> 18
            HotkeySize.MEDIUM -> 22
            HotkeySize.LARGE -> 30
        }
        text = SpannableStringBuilder().apply {
            if (hotkey.icon.isNotEmpty()) {
                append(hotkey.icon)
                setSpan(AbsoluteSizeSpan(iconSp, true), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append('\n')
            }
            val start = length
            append(label)
            setSpan(AbsoluteSizeSpan(labelSp, true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        contentDescription = "$label (${hotkey.description}, ${hotkey.effectiveMode.label})"
        refresh()
    }

    /** A short light-up for a tap (the key itself is already pressed and released). */
    fun flash() {
        removeCallbacks(unflash)
        flashing = true
        postDelayed(unflash, FLASH_MS)
    }

    private fun refresh() {
        shape.setColor(
            when {
                down -> DuoUi.ACCENT
                flashing -> DuoUi.CHIP_PRESSED
                else -> DuoUi.CHIP
            },
        )
        if (editing) shape.setStroke(CompanionCards.dp(context, 2), DuoUi.ACCENT_DIM) else shape.setStroke(0, Color.TRANSPARENT)
        alpha = if (ready || editing) 1f else 0.38f
        setTypeface(null, if (down) Typeface.BOLD else Typeface.NORMAL)
    }

    private companion object {
        const val FLASH_MS = 140L
    }
}
