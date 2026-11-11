package app.gamenative.duo.ui

import android.content.Context
import app.gamenative.duo.DualScreenHost
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Several named UI layouts per game, one of them active.
 *
 * The active layout is still `files/duo/ui/<game>.json` ([UiLayoutStore]): the editors, the mod and sharing keep
 * reading and writing that one file, so nothing else changes. This class keeps the others beside it:
 * `<game>.layouts.json` (the names and which one is active) and `<game>.layout-<id>.json` (each layout that is not
 * active right now). Switching moves files; a layout with no file is "nothing changed" (the mod's preset applies).
 *
 * A game that has only ever had the single file shows as one layout called "My layout"; the index is written at the
 * first change. The background image stays one per game (`<game>.background.jpg`), shared by its layouts.
 *
 * Blocking file work: call off the main thread. Only between game sessions: a running session holds the active
 * layout in memory and would write it back over a switch.
 */
class UiLayoutLibrary(private val dir: File, game: String) {
    data class Entry(
        val id: String,
        val name: String,
        /** The community package it came from, or empty for the user's own. */
        val source: String = "",
    )

    private val key = game.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    private val activeFile = File(dir, "$key.json")
    private val indexFile = File(dir, "$key.layouts.json")

    private fun stored(id: String) = File(dir, "$key.layout-$id.json")

    var entries: List<Entry> = emptyList()
        private set
    var activeId: String = ""
        private set

    init {
        load()
    }

    val active: Entry get() = entries.first { it.id == activeId }

    private fun load() {
        val parsed = try {
            if (indexFile.isFile) JSONObject(indexFile.readText()) else null
        } catch (t: Throwable) {
            Timber.tag(DualScreenHost.TAG).w(t, "Ignoring the unreadable layout list %s", indexFile.name)
            null
        }
        val array = parsed?.optJSONArray("layouts")
        val list = (0 until (array?.length() ?: 0)).mapNotNull { i ->
            array!!.optJSONObject(i)?.let { o ->
                val id = o.optString("id")
                if (ID.matches(id)) Entry(id, o.optString("name").ifBlank { DEFAULT_NAME }, o.optString("source")) else null
            }
        }.distinctBy { it.id }
        if (list.isEmpty()) {
            // Never used: the single layout the game always had.
            entries = listOf(Entry("1", DEFAULT_NAME))
            activeId = "1"
        } else {
            entries = list
            activeId = parsed?.optString("active").takeIf { a -> list.any { it.id == a } } ?: list.first().id
        }
    }

    private fun save() {
        val json = JSONObject().put("active", activeId).put(
            "layouts",
            JSONArray().apply {
                entries.forEach { e ->
                    put(JSONObject().put("id", e.id).put("name", e.name).apply { if (e.source.isNotEmpty()) put("source", e.source) })
                }
            },
        )
        write(indexFile, json.toString(2))
    }

    /** The layout's JSON text, or null when it changes nothing. */
    fun text(id: String): String? {
        val file = if (id == activeId) activeFile else stored(id)
        return if (file.isFile) file.readText() else null
    }

    /** Makes [id] the active layout: the current one is put away, [id]'s file becomes the game's. */
    fun activate(id: String) {
        if (id == activeId) return
        require(entries.any { it.id == id }) { "no layout $id" }
        move(activeFile, stored(activeId))
        move(stored(id), activeFile)
        activeId = id
        save()
    }

    /** A copy of [id] under a new name (not made active); returns its entry. */
    fun duplicate(id: String, name: String? = null): Entry {
        val from = entries.first { it.id == id }
        val copy = Entry(nextId(), uniqueName(name?.trim()?.ifEmpty { null } ?: from.name))
        text(id)?.let { write(stored(copy.id), it) }
        entries = entries + copy
        save()
        return copy
    }

    fun rename(id: String, name: String) {
        val clean = name.trim().take(MAX_NAME)
        if (clean.isEmpty()) return
        entries = entries.map { if (it.id == id) it.copy(name = uniqueName(clean, except = id)) else it }
        save()
    }

    /**
     * Deletes [id]. The last layout cannot go: it is emptied instead (back to the preset). Deleting the active one
     * makes the first remaining layout active.
     */
    fun delete(id: String) {
        if (entries.none { it.id == id }) return
        if (entries.size == 1) {
            activeFile.delete()
            entries = listOf(Entry(id, DEFAULT_NAME))
            save()
            return
        }
        if (id == activeId) {
            val next = entries.first { it.id != id }
            activeFile.delete()
            move(stored(next.id), activeFile)
            activeId = next.id
        } else {
            stored(id).delete()
        }
        entries = entries.filterNot { it.id == id }
        save()
    }

    /**
     * Adds a layout from JSON [text] (an imported file, a community package) and makes it active. A layout from the
     * same [source] package is replaced instead of added twice. Throws [IOException] when [text] is not a UI layout.
     */
    fun add(name: String, text: String, source: String = ""): Entry {
        val json = try {
            JSONObject(text)
        } catch (t: Throwable) {
            throw IOException(NOT_A_LAYOUT)
        }
        checkFormat(json)
        val existing = entries.firstOrNull { source.isNotEmpty() && it.source == source }
        val entry = existing ?: Entry(nextId(), uniqueName(name.trim().take(MAX_NAME).ifEmpty { DEFAULT_NAME }), source)
        if (existing == null) entries = entries + entry
        if (entry.id != activeId) {
            move(activeFile, stored(activeId))
            stored(entry.id).delete()
            activeId = entry.id
        }
        write(activeFile, text)
        save()
        return entry
    }

    /** Throws [IOException] unless [json] says it is a UI layout. */
    fun checkFormat(json: JSONObject) {
        if (json.optString("format") != UiLayout.FORMAT) throw IOException(NOT_A_LAYOUT)
    }

    /** Removes the layout that came from package [source], if any. */
    fun removeSource(source: String) {
        entries.firstOrNull { source.isNotEmpty() && it.source == source }?.let { delete(it.id) }
    }

    private fun nextId(): String = ((entries.mapNotNull { it.id.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()

    private fun uniqueName(name: String, except: String? = null): String {
        val taken = entries.filter { it.id != except }.map { it.name }.toSet()
        if (name !in taken) return name
        var n = 2
        while ("$name ($n)" in taken) n++
        return "$name ($n)"
    }

    /** Moves [from] to [to]; when [from] does not exist, [to] must not either (a layout that changes nothing). */
    private fun move(from: File, to: File) {
        to.delete()
        if (!from.isFile) return
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    companion object {
        const val DEFAULT_NAME = "My layout"
        const val MAX_NAME = 40
        private const val NOT_A_LAYOUT = "This file is not a GameNative Duo layout"
        private val ID = Regex("[0-9]{1,6}")

        fun of(context: Context, game: String) = UiLayoutLibrary(File(context.filesDir, "duo/ui"), game)
    }
}
