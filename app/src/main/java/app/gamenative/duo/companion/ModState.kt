package app.gamenative.duo.companion

/**
 * Which keys of a mod's STATE a companion takes, and what it subscribes to (pure, unit-tested). A mod writes only its
 * own keys: under the manifest's api (`hk.`) when it names one, else anything outside the app's own prefixes, so a
 * mod can never overwrite the user's notes or the app's session values.
 */
internal object ModState {
    private val RESERVED = listOf(CompanionSession.USER, CompanionSession.STATIC, CompanionSession.SESSION, "file.")

    fun accepts(key: String, api: String?): Boolean =
        if (api != null) key.startsWith("$api.") else '.' in key && RESERVED.none { key.startsWith(it) }

    /**
     * The SUBSCRIBE list for the keys shown cards and conditions read. A mod matches entry `s` against key `k` when
     * `k == s` or `k` starts with `s.` (a list card reading `hk.charms` gets `hk.charms.0`...).
     */
    fun subscription(keys: Collection<String>, api: String?): List<String> = keys.filter { accepts(it, api) }.distinct().sorted()
}
