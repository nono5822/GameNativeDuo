package app.gamenative.duo.companion

import java.util.Locale
import kotlin.math.roundToLong

/**
 * One session's companion values: dotted keys (`hk.geo`, `user.notes`, `session.battery`) to plain values (String,
 * Long, Double, Boolean, List, Map). Every source writes here and cards only read here. Main thread only.
 *
 * [listener] hears which keys changed after each [put]/[putAll], so views refresh only the cards that use them.
 */
class CompanionData {
    private val values = HashMap<String, Any?>()

    var listener: ((Set<String>) -> Unit)? = null

    operator fun get(key: String): Any? = values[key]

    fun put(key: String, value: Any?) {
        if (set(key, value)) listener?.invoke(setOf(key))
    }

    /** Applies several values at once; [clearPrefix] first drops every key under it (a full snapshot from a source). */
    fun putAll(changes: Map<String, Any?>, clearPrefix: String? = null) {
        val changed = mutableSetOf<String>()
        if (clearPrefix != null) {
            val gone = values.keys.filter { it.startsWith(clearPrefix) && it !in changes }
            gone.forEach { values.remove(it) }
            changed += gone
        }
        changes.forEach { (k, v) -> if (set(k, v)) changed += k }
        if (changed.isNotEmpty()) listener?.invoke(changed)
    }

    private fun set(key: String, value: Any?): Boolean {
        val v = normalize(value)
        if (values.containsKey(key) && values[key] == v) return false
        if (v == null) values.remove(key) else values[key] = v
        return true
    }

    /** Keys under [prefix] (for persisting `user.` state). */
    fun snapshot(prefix: String): Map<String, Any?> = values.filterKeys { it.startsWith(prefix) }

    /** Tests a `when` condition. Keys missing from the store are null. */
    fun test(c: Condition): Boolean = when (c) {
        is Condition.All -> c.of.all { test(it) }
        is Condition.AnyOf -> c.of.any { test(it) }
        is Condition.Not -> !test(c.of)
        is Condition.Compare -> compare(get(c.key), c.op, c.value)
    }

    /**
     * Fills a template: `{key}` or `{key:format}`; `{{` is a literal brace. Inside a list row, [scope] (the element)
     * answers first: `{name}` is its field, `{.}` the element itself. Missing values print `--`.
     */
    fun format(template: String, scope: Any? = null): String {
        if ('{' !in template) return template
        val out = StringBuilder(template.length + 16)
        var i = 0
        while (i < template.length) {
            val ch = template[i]
            if (ch == '{' && i + 1 < template.length && template[i + 1] == '{') {
                out.append('{')
                i += 2
                continue
            }
            if (ch == '}' && i + 1 < template.length && template[i + 1] == '}') {
                out.append('}')
                i += 2
                continue
            }
            val end = if (ch == '{') template.indexOf('}', i + 1) else -1
            if (end < 0) {
                out.append(ch)
                i++
                continue
            }
            val ref = template.substring(i + 1, end)
            val key = ref.substringBefore(':').trim()
            val fmt = if (':' in ref) ref.substringAfter(':').trim() else ""
            out.append(formatValue(lookup(key, scope), fmt))
            i = end + 1
        }
        return out.toString()
    }

    /** A value for [key]: from the list element [scope] first, then the store. */
    fun lookup(key: String, scope: Any? = null): Any? {
        if (scope != null) {
            if (key == ".") return scope
            if (scope is Map<*, *> && scope.containsKey(key)) return normalize(scope[key])
        }
        return get(key)
    }

    /** A number from a template or a literal ("{hk.hp}", "5", 5). */
    fun number(spec: Any?, scope: Any? = null): Double? = when (spec) {
        is Number -> spec.toDouble()
        is String -> if ('{' in spec) format(spec, scope).toDoubleOrNull() else spec.toDoubleOrNull()
        else -> null
    }

    companion object {
        const val MISSING = "--"

        /** Every store key a template reads (list-element fields are not keys, but cost only a cheap extra check). */
        fun keysOf(template: String?): Set<String> {
            if (template.isNullOrEmpty() || '{' !in template) return emptySet()
            return KEY_REF.findAll(template.replace("{{", "")).map { it.groupValues[1].trim() }.filter { it != "." }.toSet()
        }

        private val KEY_REF = Regex("\\{([^{}:]+)(?::[^{}]*)?\\}")

        internal fun normalize(v: Any?): Any? = when (v) {
            is Int -> v.toLong()
            is Short -> v.toLong()
            is Byte -> v.toLong()
            is Float -> v.toDouble()
            else -> v
        }

        internal fun formatValue(v: Any?, fmt: String): String {
            if (v == null) return MISSING
            val n = (v as? Number)?.toDouble()
            return when (fmt) {
                "n0" -> n?.let { String.format(Locale.ROOT, "%,d", it.roundToLong()) } ?: v.toString()
                "n1" -> n?.let { String.format(Locale.ROOT, "%,.1f", it) } ?: v.toString()
                "pct" -> n?.let { "${(it * 100).roundToLong()}%" } ?: v.toString()
                "time" -> n?.let { duration(it.toLong()) } ?: v.toString()
                "upper" -> plain(v).uppercase(Locale.ROOT)
                else -> plain(v)
            }
        }

        /** Seconds as m:ss or h:mm:ss. */
        fun duration(totalSeconds: Long): String {
            val s = totalSeconds.coerceAtLeast(0)
            val h = s / 3600
            val m = (s / 60) % 60
            val sec = s % 60
            return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
        }

        private fun plain(v: Any): String = when (v) {
            // 3.0 prints as 3: values from JSON are often whole numbers sent as floats.
            is Double -> if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
            is List<*> -> v.size.toString()
            else -> v.toString()
        }

        internal fun compare(actual: Any?, op: Condition.Op, expected: Any?): Boolean {
            val a = normalize(actual)
            val e = normalize(expected)
            return when (op) {
                Condition.Op.EXISTS -> (a != null) == (e != false)
                Condition.Op.EQ -> same(a, e)
                Condition.Op.NE -> !same(a, e)
                Condition.Op.GT -> a is Number && e is Number && a.toDouble() > e.toDouble()
                Condition.Op.LT -> a is Number && e is Number && a.toDouble() < e.toDouble()
                Condition.Op.IN -> (e as? List<*>)?.any { same(a, normalize(it)) } == true
            }
        }

        private fun same(a: Any?, b: Any?): Boolean =
            if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b
    }
}
