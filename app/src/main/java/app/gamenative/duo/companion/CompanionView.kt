package app.gamenative.duo.companion

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.ui.DuoUi
import app.gamenative.duo.ui.Snapshots
import timber.log.Timber

/** What the window showing a companion does for it. */
interface CompanionHost {
    /** The system photo picker (it opens on the main screen); null when cancelled or unreadable. */
    fun pickImage(done: (ByteArray?) -> Unit)

    /** The system file picker for a text file (a guide). */
    fun pickText(done: (ByteArray?) -> Unit)

    /**
     * Asks for text in a dialog on the main screen ([done] gets it, or nothing when cancelled). Not on the bottom screen:
     * Android serves the keyboard to the main display while the game runs there, and the full-size keyboard fits better.
     */
    fun editText(title: String, initial: String, multiLine: Boolean, done: (String) -> Unit)

    /** The game activity's context, for dialogs on the main screen (a card's own context is the bottom screen's). */
    val mainContext: Context
}

/**
 * A companion on screen: its pages as tabs along the top, the current page's cards below (one column, or two), and
 * "Back to game" when it covers the game ([onClose]). Built from the manifest; refreshes only the cards whose keys
 * changed, rebuilds a page only when a card's `when` flips. Nothing ticks unless a shown card needs it.
 */
@SuppressLint("ViewConstructor")
internal class CompanionView(
    context: Context,
    private val session: CompanionSession,
    private val host: CompanionHost,
    private val onClose: (() -> Unit)? = null,
) : LinearLayout(context) {
    private val tabs = LinearLayout(context).apply {
        orientation = HORIZONTAL
        isBaselineAligned = false
        gravity = Gravity.CENTER_VERTICAL
    }
    private val notice = DuoUi.label(context, "", 13f, Color.rgb(0xF6, 0xC3, 0x5B)).apply {
        maxLines = 6
        visibility = GONE
    }
    private val content = FrameLayout(context)
    private var pageId: String? = null
    private var visibleCards: List<String> = emptyList()
    private var bindings: List<CardBinding> = emptyList()
    private var shownPages: List<String> = emptyList()
    private val focusState = mutableMapOf<String, Boolean>()
    private var lastTouch = 0L
    private var usingSession = false
    private val manifest get() = session.manifest

    private val dataListener: (Set<String>) -> Unit = { changed -> onData(changed) }
    private val reloadListener: () -> Unit = { rebuild() }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK) // AMOLED: black pixels are off
        val pad = dp(10)
        setPadding(pad, pad, pad, 0)
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            isBaselineAligned = false
            gravity = Gravity.CENTER_VERTICAL
        }
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
        }
        header.addView(scroller, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        onClose?.let { close ->
            header.addView(
                chip("Back to game") {
                    close()
                },
                LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(notice, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        addView(content, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        rebuild()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        session.data.listener = dataListener
        if (reloadListener !in session.reloadListeners) session.reloadListeners += reloadListener
        setUsingSession(bindings.any { b -> b.keys.any { it.startsWith(CompanionSession.SESSION) } })
    }

    override fun onDetachedFromWindow() {
        if (session.data.listener === dataListener) session.data.listener = null
        session.reloadListeners -= reloadListener
        setUsingSession(false)
        super.onDetachedFromWindow()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastTouch = SystemClock.uptimeMillis()
        return super.dispatchTouchEvent(ev)
    }

    /** Everything from the manifest again (first build, live reload). Keeps the current page when it still exists. */
    private fun rebuild() {
        try {
            focusState.clear()
            manifest.pages.forEach { p -> p.focusWhen?.let { focusState[p.id] = session.data.test(it) } }
            buildTabs()
            showNotice()
            showPage(pageId?.takeIf { it in shownPages } ?: shownPages.firstOrNull())
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Failed to build the companion")
        }
    }

    private fun visiblePages() = manifest.pages.filter { p -> p.showWhen?.let { session.data.test(it) } != false }

    private fun buildTabs() {
        tabs.removeAllViews()
        val pages = visiblePages()
        shownPages = pages.map { it.id }
        if (pages.size <= 1) {
            tabs.addView(
                DuoUi.label(context, pages.firstOrNull()?.title ?: manifest.name, 18f, Color.WHITE, bold = true).apply {
                    setPadding(dp(6), 0, 0, 0)
                },
            )
            return
        }
        pages.forEach { p ->
            tabs.addView(
                chip(p.title) { showPage(p.id) }.apply { on = p.id == pageId },
                LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) },
            )
        }
    }

    private fun showNotice() {
        val lines = mutableListOf<String>()
        session.buildNotice()?.let { lines += it }
        // Package problems are for the author: shown for companions being written, logged for the rest.
        if (session.entry.origin == CompanionEntry.Origin.DEV) {
            manifest.problems.take(4).forEach { lines += "⚠ $it" }
        } else if (manifest.problems.isNotEmpty()) {
            Timber.tag(DualScreenHost.TAG).w("Companion %s: %s", manifest.id, manifest.problems.joinToString("; "))
        }
        notice.text = lines.joinToString("\n")
        notice.visibility = if (lines.isEmpty()) GONE else VISIBLE
    }

    private fun showPage(id: String?) {
        // The same page rebuilt (a card's `when` flipped): stay where the user was scrolled to.
        val keepScroll = if (id == pageId) (content.getChildAt(0) as? ScrollView)?.scrollY ?: 0 else 0
        pageId = id
        for (i in 0 until tabs.childCount) {
            (tabs.getChildAt(i) as? DuoUi.Chip)?.on = shownPages.getOrNull(i) == id
        }
        content.removeAllViews()
        val page = manifest.pages.firstOrNull { it.id == id }
        if (page == null) {
            bindings = emptyList()
            visibleCards = emptyList()
            content.addView(DuoUi.label(context, "This companion has nothing to show yet.", 15f, DuoUi.MUTED))
            setUsingSession(false)
            return
        }
        val cards = page.cards.filter { c -> c.showWhen?.let { session.data.test(it) } != false }
        visibleCards = cards.map { it.id }
        val alone = cards.size == 1 && cards[0].type in setOf(CardType.IMAGE, CardType.MAP)
        val built = cards.map { it to CompanionCards.build(CardEnv(context, session, host, alone), it) }
        bindings = built.map { it.second }
        if (alone) {
            val fill = ViewGroup.LayoutParams.MATCH_PARENT
            content.addView(built[0].second.view, FrameLayout.LayoutParams(fill, fill))
        } else {
            val column = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(0, 0, 0, dp(16))
            }
            var row: LinearLayout? = null
            built.forEach { (card, b) ->
                val gap = dp(8)
                if (page.columns == 2 && card.span == 1) {
                    val r = row ?: LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        isBaselineAligned = false
                    }.also {
                        row = it
                        column.addView(
                            it,
                            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                                bottomMargin =
                                    gap
                            },
                        )
                    }
                    r.addView(
                        b.view,
                        LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (r.childCount ==
                                0
                            ) {
                                marginEnd = gap
                            }
                        },
                    )
                    if (r.childCount == 2) row = null
                } else {
                    row = null
                    column.addView(
                        b.view,
                        LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin =
                                gap
                        },
                    )
                }
            }
            // A lone card in the last row of a two-column page keeps half the width.
            row?.takeIf { it.childCount == 1 }?.addView(View(context), LayoutParams(0, 1, 1f))
            content.addView(
                ScrollView(context).apply {
                    isVerticalScrollBarEnabled = false
                    addView(column)
                    if (keepScroll > 0) post { scrollTo(0, keepScroll) }
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        setUsingSession(isAttachedToWindow && bindings.any { b -> b.keys.any { it.startsWith(CompanionSession.SESSION) } })
    }

    private fun onData(changed: Set<String>) {
        try {
            val pages = manifest.pages
            if (pages.any { p -> p.showWhen?.keys()?.any { it in changed } == true }) {
                buildTabs()
                if (pageId !in shownPages) showPage(shownPages.firstOrNull())
            }
            // A page asks to come forward (the shop opened), unless the user is busy with the companion.
            pages.forEach { p ->
                val c = p.focusWhen ?: return@forEach
                if (c.keys().none { it in changed }) return@forEach
                val now = session.data.test(c)
                val before = focusState.put(p.id, now) ?: false
                if (now &&
                    !before &&
                    p.id != pageId &&
                    p.id in shownPages &&
                    SystemClock.uptimeMillis() - lastTouch > BUSY_MS
                ) {
                    showPage(p.id)
                }
            }
            if (manifest.builds.any { it.source in changed }) showNotice()
            val page = pages.firstOrNull { it.id == pageId } ?: return
            if (page.cards.any { c -> c.showWhen?.keys()?.any { it in changed } == true }) {
                val now = page.cards.filter { c -> c.showWhen?.let { session.data.test(it) } != false }.map { it.id }
                if (now != visibleCards) {
                    showPage(pageId)
                    return
                }
            }
            bindings.forEach { if (it.uses(changed)) it.refresh() }
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Companion refresh failed")
        }
    }

    private fun setUsingSession(on: Boolean) {
        if (on == usingSession) return
        usingSession = on
        session.useSessionValues(on)
    }

    private fun chip(text: String, onClick: () -> Unit) = DuoUi.chip(context, text, onClick).apply { minHeight = dp(48) }

    private fun dp(v: Int) = CompanionCards.dp(context, v)

    private companion object {
        const val BUSY_MS = 10_000L
    }
}

/** The system pickers for companion cards, from whatever activity runs the game (they open on the main screen). */
internal object CompanionPickers {
    private val io: Handler by lazy {
        Handler(HandlerThread("GNDuo-companion-pick", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.looper)
    }
    private val main = Handler(Looper.getMainLooper())

    fun image(context: Context, done: (ByteArray?) -> Unit) {
        val activity = Snapshots.activityOf(context) as? ComponentActivity ?: return done(null)
        try {
            var launcher: ActivityResultLauncher<PickVisualMediaRequest>? = null
            launcher = activity.activityResultRegistry.register(key("image"), ActivityResultContracts.PickVisualMedia()) { uri ->
                launcher?.unregister()
                read(activity, uri, done)
            }
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Could not open the photo picker")
            done(null)
        }
    }

    fun text(context: Context, done: (ByteArray?) -> Unit) {
        val activity = Snapshots.activityOf(context) as? ComponentActivity ?: return done(null)
        try {
            var launcher: ActivityResultLauncher<Array<String>>? = null
            launcher = activity.activityResultRegistry.register(key("text"), ActivityResultContracts.OpenDocument()) { uri ->
                launcher?.unregister()
                read(activity, uri, done)
            }
            launcher.launch(arrayOf("text/*"))
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Could not open the file picker")
            done(null)
        }
    }

    /** A text dialog over the game (see [CompanionHost.editText]); Save hands the text over, Cancel drops it. */
    fun editText(context: Context, title: String, initial: String, multiLine: Boolean, done: (String) -> Unit) {
        val activity = Snapshots.activityOf(context) ?: return
        try {
            val pad = CompanionCards.dp(activity, 20)
            val edit = android.widget.EditText(activity).apply {
                setText(initial)
                setSelection(initial.length)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    if (multiLine) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
                if (multiLine) {
                    minLines = 4
                    maxLines = 10
                    gravity = Gravity.TOP or Gravity.START
                }
            }
            val box = FrameLayout(activity).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(edit)
            }
            val dialog = android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Save") { _, _ -> done(edit.text.toString()) }
                .setNegativeButton("Cancel", null)
                .create()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            if (!multiLine) {
                // One line: the keyboard's Enter saves.
                edit.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                edit.setOnEditorActionListener { _, _, _ ->
                    done(edit.text.toString())
                    dialog.dismiss()
                    true
                }
            }
            dialog.show()
            edit.requestFocus()
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).e(t, "Could not open the text dialog")
        }
    }

    private fun key(kind: String) = "gnduo-companion-$kind-" + SystemClock.uptimeMillis()

    private fun read(activity: ComponentActivity, uri: android.net.Uri?, done: (ByteArray?) -> Unit) {
        if (uri == null) return done(null)
        io.post {
            val bytes = try {
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (out.size() <= CompanionFiles.MAX_FILE) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.toByteArray().takeIf { it.size <= CompanionFiles.MAX_FILE }
                }
            } catch (t: Throwable) {
                Timber.tag(DualScreenHost.TAG).w(t, "Could not read the chosen file")
                null
            }
            main.post { done(bytes) }
        }
    }
}
