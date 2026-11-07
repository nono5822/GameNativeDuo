package app.gamenative.duo.ui

import android.content.Context
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import app.gamenative.duo.DualScreenHost
import app.gamenative.duo.bridge.DuoBridge
import app.gamenative.duo.mods.DuoModCatalog
import app.gamenative.duo.mods.DuoModInstaller
import app.gamenative.duo.mods.ModFeatures
import app.gamenative.service.SteamService
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
    /** The screens the element can go to (null: any). A mod's own element may offer fewer, e.g. bottom or hidden. */
    val screens: Set<UiScreen>? = null,
    /** Settings the mod declares for this element (its own elements only). */
    val options: List<UiElementOption> = emptyList(),
    /** The mod honours the rule's `x`/`y` for this element. */
    val canMove: Boolean = true,
    /** The rule's `scale` range the mod honours; null: it ignores `scale`. */
    val scaleRange: ClosedFloatingPointRange<Float>? = UiRule.SCALE_MIN..UiRule.SCALE_MAX,
    /** On screen now; an absent element (not in this scene) has no valid [rect]. */
    val present: Boolean = true,
    /** When an absent element appears, in the mod's words ("During a run"). */
    val whenText: String = "",
    /** Other elements have this one as their parent. */
    val hasChildren: Boolean = false,
    /** What the editor's hint says about it instead of its limits (a region's source in the top-screen map). */
    val note: String = "",
) {
    val area: Float get() = rect.width() * rect.height()
    val canScale: Boolean get() = scaleRange != null

    /** The editor can change something about it (move, resize, screen or settings). */
    val editable: Boolean get() = canMove || canScale || options.isNotEmpty() || (screens?.size ?: 3) > 1

    fun allows(screen: UiScreen): Boolean = screens == null || screen in screens
}

/** The game's UI elements, parents before children. */
class UiElementList(val windowW: Int, val windowH: Int, val elements: List<UiElement>) {
    fun byId(id: String?): UiElement? = elements.firstOrNull { it.id == id }

    fun parentOf(e: UiElement): UiElement? = elements.getOrNull(e.parent)

    companion object {
        fun parse(file: File): UiElementList = parse(file.readText())

        fun parse(text: String): UiElementList {
            val json = JSONObject(text)
            val screen = json.optJSONObject("screen")
            val array = json.optJSONArray("elements")
            val list = ArrayList<UiElement>(array?.length() ?: 0)
            val parents = HashSet<Int>()
            for (i in 0 until (array?.length() ?: 0)) parents += array!!.getJSONObject(i).optInt("parent", -1)
            for (i in 0 until (array?.length() ?: 0)) {
                val o = array!!.getJSONObject(i)
                val parent = o.optInt("parent", -1)
                val x = o.optDouble("x").toFloat()
                val y = o.optDouble("y").toFloat()
                // `features` (contract "Element features"); `screens` at the top level is the older plugin-element form.
                val features = o.optJSONObject("features")
                list += UiElement(
                    index = i,
                    id = o.getString("id"),
                    name = o.optString("name"),
                    parent = parent,
                    depth = list.getOrNull(parent)?.let { it.depth + 1 } ?: 0,
                    screen = UiScreen.fromId(o.optString("screen")),
                    rect = RectF(x, y, x + o.optDouble("w").toFloat(), y + o.optDouble("h").toFloat()),
                    graphic = o.optBoolean("graphic"),
                    screens = parseScreens(features?.optJSONArray("screens") ?: o.optJSONArray("screens")),
                    options = o.optJSONArray("options")?.let { a ->
                        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(UiElementOption::fromJson) }
                    } ?: emptyList(),
                    canMove = features?.optBoolean("move", true) ?: true,
                    scaleRange = parseScale(features?.opt("scale")),
                    present = o.optBoolean("present", true),
                    whenText = o.optString("when"),
                    hasChildren = i in parents,
                )
            }
            return UiElementList(screen?.optInt("w") ?: 0, screen?.optInt("h") ?: 0, list)
        }

        internal fun parseScreens(a: org.json.JSONArray?): Set<UiScreen>? = a?.let {
            (0 until a.length()).map { UiScreen.fromId(a.optString(it)) }.filter { it != UiScreen.DEFAULT }.toSet()
        }?.takeIf { it.isNotEmpty() }

        /** `scale`: absent or true = the full range, false = ignored, `{"min":..,"max":..}` = a narrower range. */
        internal fun parseScale(v: Any?): ClosedFloatingPointRange<Float>? {
            val full = UiRule.SCALE_MIN..UiRule.SCALE_MAX
            return when (v) {
                null, true -> full
                false -> null
                is JSONObject -> {
                    val min = v.optDouble("min", full.start.toDouble()).toFloat().coerceIn(full.start, full.endInclusive)
                    val max = v.optDouble("max", full.endInclusive.toDouble()).toFloat().coerceIn(min, full.endInclusive)
                    if (min.isFinite() && max.isFinite()) min..max else full
                }
                else -> full
            }
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
    /** Region view: the editor's elements are the user's regions (no mod), see [UiRegion]. */
    val regionMode: Boolean = false,
    /** Older region views kept one region as `"x,y,w,h"` in the container's settings: it becomes the first region. */
    legacyRegion: String? = null,
) : DuoBridge.Listener {
    var layout: UiLayout = UiLayoutStore.load(context, container.id).let { loaded ->
        if (!regionMode || loaded.regions.isNotEmpty()) {
            loaded
        } else {
            val first = UiRegion.fromLegacy(app.gamenative.duo.split.RegionView.parse(legacyRegion))
                ?: UiRegion.fromLegacy(floatArrayOf(0.72f, 0f, 0.28f, 0.4f))!!
            loaded.copy(regions = listOf(first))
        }
    }.withRegionRules()
        private set

    /**
     * Region view: X screen and bottom surface sizes (`screenW, screenH, surfaceW, surfaceH`), set by the bottom screen
     * once its surface exists. The editor shows the bottom screen as `{0, screenH, surfaceW, surfaceH}` below the X
     * screen (see [regionBottomRect]).
     */
    var regionGeometry: IntArray? = null

    val regionBottomRect: FloatArray?
        get() = regionGeometry?.let { g -> floatArrayOf(0f, g[1].toFloat(), g[2].toFloat(), g[3].toFloat()) }

    /** The regions as of the last saved change: editor gestures send changes relative to it. */
    private var regionBase: List<UiRegion> = layout.regions

    private val main = Handler(Looper.getMainLooper())
    private var nextRequest = 1
    private val pending = mutableMapOf<Int, (UiElementList?) -> Unit>()

    init {
        bridge?.listener = this
    }

    /**
     * What the layout editor may offer, for every editor surface (`uniform-editing.md`). Built from the mod seen this
     * session, not "lately": GameNative's in-game menu pauses the game, and with it the mod's hellos.
     */
    val capabilities: EditCapabilities
        get() {
            if (regionMode) {
                return EditCapabilities(EditSource.REGIONS, null, REGION_OWNER, setOf(UiBackgroundMode.GAME, UiBackgroundMode.COLOR))
            }
            val seen = bridge?.takeIf { it.seenMod(ANY_CAPABILITY) }?.capabilities ?: 0
            val static = staticFeatures
            if (seen == 0 && static != null) return EditCapabilities.forStatic(static.backgrounds, gameName)
            return EditCapabilities.forMod(seen, gameName)
        }

    /** The installed bundled mod's `features.json` (null without one): known before the mod says hello. */
    private val staticFeatures: ModFeatures? by lazy {
        try {
            DuoModInstaller.enabledModId(context, container.id)
                ?.let { id -> DuoModCatalog.mods.firstOrNull { it.id == id } }
                ?.let { ModFeatures.forMod(context, it) }
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "No features.json for %s", container.id)
            null
        }
    }

    /**
     * The mod's fixed elements from its bundled `features.json`, all absent: what the editor shows while the game cannot
     * answer (paused by GameNative's menu, still loading). Null when the mod has none.
     */
    fun staticElements(): UiElementList? {
        val f = staticFeatures ?: return null
        if (f.elements.isEmpty()) return null
        val list = f.elements.mapIndexed { i, e ->
            UiElement(
                index = i, id = e.id, name = e.name, parent = -1, depth = 0, screen = UiScreen.DEFAULT, rect = RectF(),
                graphic = true, screens = e.screens, canMove = e.canMove, scaleRange = e.scaleRange, present = false,
                whenText = e.whenText,
            )
        }
        return UiElementList(0, 0, list)
    }

    /** The game's name for display (the container's), and the engine its mod reported. */
    val gameName: String by lazy {
        container.name?.takeIf { it.isNotBlank() }
            ?: runCatching {
                container.id.removePrefix("STEAM_").toIntOrNull()?.let { SteamService.getAppInfoOf(it)?.name }
            }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: container.id
    }
    val engine: String get() = bridge?.engine.orEmpty()

    /** True when the running mod can draw [mode] behind the bottom screen's UI. */
    fun supports(mode: UiBackgroundMode): Boolean = capabilities.supports(mode)

    /** Called on the main thread after every change (widgets on the bottom screen, the PC editor). */
    val listeners = mutableListOf<(UiLayout) -> Unit>()

    fun start() {
        UiLayoutStore.writeToPrefix(context, container, layout)
        Timber.tag(DualScreenHost.TAG).i("UI layout for %s: %d rule(s)", container.id, layout.rules.size)
    }

    /** Changes one element; [save] also stores the user's layout (at the end of a gesture, not on every move). */
    fun set(id: String, rule: UiRule, save: Boolean) {
        if (regionMode) return setRegion(id, rule, save)
        layout = layout.with(id, rule)
        push()
        if (save) {
            persist()
            Timber.tag(DualScreenHost.TAG).i("UI layout: %s -> %s", id, rule)
        }
    }

    fun resetAll() {
        layout = UiLayoutStore.reset(context, container.id)
        if (regionMode) {
            layout = layout.copy(regions = layout.regions.ifEmpty { listOf(UiRegion.create(emptyList())) }).withRegionRules()
            regionBase = layout.regions
        }
        push()
    }

    // ---- region view ---------------------------------------------------------------------------------------------

    /** A new region in the middle of the frame, shown on the bottom screen; returns its element id. */
    fun addRegion(): String? {
        if (!regionMode || layout.regions.size >= UiRegion.MAX) return null
        val r = UiRegion.create(layout.regions)
        saveRegions(layout.regions + r)
        return r.id
    }

    fun removeRegion(id: String) {
        val regionId = id.removeSuffix(SOURCE_SUFFIX)
        saveRegions(layout.regions.filterNot { it.id == regionId })
    }

    private fun saveRegions(regions: List<UiRegion>) {
        layout = layout.copy(regions = regions).withRegionRules()
        regionBase = layout.regions
        push()
        persist()
    }

    /**
     * An editor change to a region's element. [rule] is relative to the region as last saved ([regionBase]): its
     * screen, a shift as fractions of the screen it is on, a scale. A region's place element moves its place on the
     * bottom screen; its source element (or the region itself while it is only on top) moves the part of the game.
     */
    private fun setRegion(id: String, rule: UiRule, save: Boolean) {
        val regionId = id.removeSuffix(SOURCE_SUFFIX)
        val base = regionBase.firstOrNull { it.id == regionId } ?: return
        // A source element only moves the source: the screen it is drawn on (the top map) is not the region's.
        val newScreen = if (rule.screen == UiScreen.DEFAULT || id.endsWith(SOURCE_SUFFIX)) base.screen else rule.screen
        val changed = when {
            newScreen != base.screen -> base.copy(screen = newScreen)
            // "Reset" on a shown region: back to the middle of the bottom screen.
            rule == UiRule() -> base.copy(cx = 0.5f, cy = 0.5f, width = 0.5f)
            id.endsWith(SOURCE_SUFFIX) || !base.shown -> {
                val s = base.source
                val w = s[2] * rule.scale
                val h = s[3] * rule.scale
                val cx = s[0] + s[2] / 2f + rule.x
                val cy = s[1] + s[3] / 2f + rule.y
                base.copy(source = UiRegion.clampSource(listOf(cx - w / 2f, cy - h / 2f, w, h)))
            }
            else -> base.copy(
                cx = (base.cx + rule.x).coerceIn(0f, 1f),
                cy = (base.cy + rule.y).coerceIn(0f, 1f),
                width = (base.width * rule.scale).coerceIn(0.05f, 1f),
            )
        }
        val regions = layout.regions.map { if (it.id == regionId) changed else it }
        if (save) {
            saveRegions(regions)
        } else {
            layout = layout.copy(regions = regions).withRegionRules()
            listeners.forEach { it(layout) }
        }
    }

    /** The regions as editor elements: each shown region on the bottom screen with its source in the top-screen map. */
    private fun regionElements(): UiElementList? {
        val g = regionGeometry ?: return null
        val (sw, sh, bw, bh) = g.toList()
        val list = ArrayList<UiElement>()
        for (r in layout.regions) {
            val src = RegionOutput.sourcePx(r, sw, sh)
            val srcRect = RectF(src[0], src[1], src[0] + src[2], src[1] + src[3])
            if (!r.shown) {
                list += UiElement(list.size, r.id, r.name, -1, 0, UiScreen.TOP, srcRect, graphic = true)
                continue
            }
            val p = RegionOutput.placePx(r, sw, sh, bw, bh)
            val place = list.size
            val placeRect = RectF(p[0], sh + p[1], p[0] + p[2], sh + p[1] + p[3])
            list += UiElement(place, r.id, r.name, -1, 0, UiScreen.BOTTOM, placeRect, graphic = true)
            list += UiElement(
                list.size, r.id + SOURCE_SUFFIX, r.name + " · source", place, 1, UiScreen.TOP, srcRect, graphic = true,
                screens = setOf(UiScreen.TOP), note = "The part of the game ${r.name} shows: drag and pinch it in the top-screen map.",
            )
        }
        return UiElementList(sw, sh + bh, list)
    }

    /** Stores the user's layout; in region view without the rules made from the regions. */
    private fun persist() = UiLayoutStore.save(context, if (regionMode) layout.copy(rules = emptyMap()) else layout)

    /** Each region's screen as a rule, so the editor's screen buttons show it (never saved: regions are the truth). */
    private fun UiLayout.withRegionRules(): UiLayout =
        if (!regionMode) this else copy(rules = regions.associate { it.id to UiRule(screen = it.screen) })

    fun setBackground(background: UiBackground, save: Boolean) {
        layout = layout.copy(background = background)
        push()
        if (save) persist()
    }

    fun setWidgets(widgets: List<UiWidget>, save: Boolean) {
        layout = layout.copy(widgets = widgets.take(UiLayout.MAX_WIDGETS))
        // Widgets are the app's own: the mod has nothing to redraw.
        listeners.forEach { it(layout) }
        if (save) persist()
    }

    /**
     * Stores [bytes] (PNG, JPEG, WebP...) as the bottom screen's background image and switches to it. [done] gets the
     * result on the main thread. The image is decoded and scaled on a background thread.
     */
    fun setBackgroundImage(bytes: ByteArray, maxW: Int, maxH: Int, done: (Boolean) -> Unit = {}) {
        Thread({
            val ok = UiLayoutStore.saveImage(context, container.id, bytes, maxW, maxH)
            main.post {
                if (ok) setBackground(layout.background.copy(mode = UiBackgroundMode.IMAGE, image = true), save = true)
                done(ok)
            }
        }, "GNDuo-background").start()
    }

    /** Asks the mod for its element list; [callback] gets it on the main thread (null after 3 s without an answer). */
    fun requestElements(callback: (UiElementList?) -> Unit) {
        if (regionMode) {
            val list = regionElements()
            main.post { callback(list) }
            return
        }
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

    companion object {
        private const val ANY_CAPABILITY = -1

        /** A region's source element id: the region's id plus this. */
        const val SOURCE_SUFFIX = "#source"
        const val REGION_OWNER = "Region view"
    }

    private fun push() {
        UiLayoutStore.writeToPrefix(context, container, layout)
        bridge?.sendRulesChanged()
        listeners.forEach { it(layout) }
    }
}
