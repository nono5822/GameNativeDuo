package app.gamenative.duo.ui

import android.content.Context
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.bridge.DuoBridge
import com.winlator.container.Container
import java.io.File
import org.json.JSONObject
import timber.log.Timber

/** One UI element as the game's mod reported it. [rect] is in game-window pixels (origin top-left). */
class UiElement(
    val index: Int,
    val id: String,
    val name: String,
    val parent: Int,
    val depth: Int,
    val screen: UiScreen,
    val rect: RectF,
    val graphic: Boolean,
) {
    val area: Float get() = rect.width() * rect.height()
}

/** The game's UI elements, parents before children. */
class UiElementList(val windowW: Int, val windowH: Int, val elements: List<UiElement>) {
    fun byId(id: String?): UiElement? = elements.firstOrNull { it.id == id }

    fun parentOf(e: UiElement): UiElement? = elements.getOrNull(e.parent)

    companion object {
        fun parse(file: File): UiElementList {
            val json = JSONObject(file.readText())
            val screen = json.optJSONObject("screen")
            val array = json.optJSONArray("elements")
            val list = ArrayList<UiElement>(array?.length() ?: 0)
            for (i in 0 until (array?.length() ?: 0)) {
                val o = array!!.getJSONObject(i)
                val parent = o.optInt("parent", -1)
                val x = o.optDouble("x").toFloat()
                val y = o.optDouble("y").toFloat()
                list += UiElement(
                    index = i,
                    id = o.getString("id"),
                    name = o.optString("name"),
                    parent = parent,
                    depth = list.getOrNull(parent)?.let { it.depth + 1 } ?: 0,
                    screen = UiScreen.fromId(o.optString("screen")),
                    rect = RectF(x, y, x + o.optDouble("w").toFloat(), y + o.optDouble("h").toFloat()),
                    graphic = o.optBoolean("graphic"),
                )
            }
            return UiElementList(screen?.optInt("w") ?: 0, screen?.optInt("h") ?: 0, list)
        }
    }
}

/**
 * The UI layout of the running game: loads it ([UiLayoutStore]), hands it to the mod at session start and after
 * every edit (rewrites `ui.json`, then a bridge `RULES_CHANGED` so the mod applies it on its next frame), and fetches
 * the mod's element list for the layout editor. Main thread.
 */
class UiLayoutController(
    private val context: Context,
    private val container: Container,
    private val bridge: DuoBridge?,
) : DuoBridge.Listener {
    var layout: UiLayout = UiLayoutStore.load(context, container.id)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var nextRequest = 1
    private val pending = mutableMapOf<Int, (UiElementList?) -> Unit>()

    init {
        bridge?.listener = this
    }

    /** True when the game's mod can list its UI (the layout editor needs it). */
    val canEdit: Boolean get() = bridge?.hasMod(DuoBridge.CAP_ELEMENTS) == true

    fun start() {
        UiLayoutStore.writeToPrefix(container, layout)
        Timber.tag(DualScreenHost.TAG).i("UI layout for %s: %d rule(s)", container.id, layout.rules.size)
    }

    /** Changes one element; [save] also stores the user's layout (at the end of a gesture, not on every move). */
    fun set(id: String, rule: UiRule, save: Boolean) {
        layout = layout.with(id, rule)
        push()
        if (save) {
            UiLayoutStore.save(context, layout)
            Timber.tag(DualScreenHost.TAG).i("UI layout: %s -> %s", id, rule)
        }
    }

    fun resetAll() {
        layout = UiLayoutStore.reset(context, container.id)
        push()
    }

    /** Asks the mod for its element list; [callback] gets it on the main thread (null after 3 s without an answer). */
    fun requestElements(callback: (UiElementList?) -> Unit) {
        val b = bridge
        if (b == null) {
            callback(null)
            return
        }
        val request = nextRequest++
        pending[request] = callback
        b.requestElements(request)
        main.postDelayed({ pending.remove(request)?.invoke(null) }, 3000)
    }

    override fun onElementsWritten(request: Int) {
        val callback = pending.remove(request) ?: return
        val file = UiLayoutStore.elementsFile(container)
        // Parsing a few hundred entries is quick, but keep file reads off the main thread.
        Thread({
            val list = try {
                UiElementList.parse(file)
            } catch (t: Throwable) {
                Timber.tag(DualScreenHost.TAG).w(t, "Failed to read the UI element list")
                null
            }
            main.post { callback(list) }
        }, "GNDuo-elements").start()
    }

    private fun push() {
        UiLayoutStore.writeToPrefix(container, layout)
        bridge?.sendRulesChanged()
    }
}
