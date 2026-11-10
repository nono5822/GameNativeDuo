package app.gamenative.duo.pc

import java.security.SecureRandom

/**
 * Who may use the PC editor (pure, unit-tested). A browser pairs once: it sends the code shown on the bottom screen
 * (`POST /api/pair`), the player allows that address on the device, and the browser gets a session token that every
 * later request carries. The code is therefore sent once instead of with every request, a token only works from the
 * address it was given to, and wrong codes lock out the address that sent them instead of stopping the editor.
 */
internal class PcPairing(
    val code: String = randomCode(),
    private val now: () -> Long = System::currentTimeMillis,
    private val newToken: () -> String = ::randomToken,
) {
    sealed class Result {
        /** Paired: [token] goes into `X-Duo-Token` from now on. */
        data class Paired(val token: String) : Result()

        /** The code is right; the player has not answered the question on the bottom screen yet. Ask again. */
        object Confirm : Result()

        /** The player refused this address. */
        object Refused : Result()

        object WrongCode : Result()

        /** Too many wrong codes from this address, or too many addresses: try again in [seconds]. */
        data class Locked(val seconds: Long) : Result()
    }

    private enum class Approval { NONE, PENDING, ALLOWED, REFUSED }

    private class Client {
        var approval = Approval.NONE
        var token: String? = null
        var wrong = 0
        var lockedUntil = 0L
    }

    private val clients = LinkedHashMap<String, Client>()

    /** Told (any thread) when a new address gave the right code: the device asks the player to allow it. */
    @Volatile var onConfirm: ((address: String) -> Unit)? = null

    /** The address waiting for the player's answer (the oldest one), or null. */
    val pending: String? @Synchronized get() = clients.entries.firstOrNull { it.value.approval == Approval.PENDING }?.key

    fun pair(address: String, given: String): Result {
        var ask = false
        val result = synchronized(this) {
            val c = clients[address] ?: run {
                // A flood of made-up addresses must not grow the table or push out the ones in use.
                if (clients.size >= MAX_CLIENTS) return@synchronized Result.Locked(LOCK_MS / 1000)
                Client().also { clients[address] = it }
            }
            val wait = c.lockedUntil - now()
            if (wait > 0) return@synchronized Result.Locked((wait + 999) / 1000)
            if (!same(given.trim().uppercase(), code)) {
                if (++c.wrong >= MAX_WRONG) {
                    c.wrong = 0
                    c.lockedUntil = now() + LOCK_MS
                }
                return@synchronized Result.WrongCode
            }
            c.wrong = 0
            when (c.approval) {
                Approval.ALLOWED -> Result.Paired(newToken().also { c.token = it })
                Approval.REFUSED -> Result.Refused
                Approval.PENDING -> Result.Confirm
                Approval.NONE -> {
                    c.approval = Approval.PENDING
                    ask = true
                    Result.Confirm
                }
            }
        }
        if (ask) onConfirm?.invoke(address)
        return result
    }

    /** The player's answer for [address]; nothing happens unless it is waiting for one. */
    @Synchronized
    fun answer(address: String, allow: Boolean) {
        val c = clients[address] ?: return
        if (c.approval == Approval.PENDING) c.approval = if (allow) Approval.ALLOWED else Approval.REFUSED
    }

    /** True when [token] is the one given to [address]. */
    @Synchronized
    fun valid(address: String, token: String?): Boolean {
        val mine = clients[address]?.takeIf { it.approval == Approval.ALLOWED }?.token ?: return false
        return token != null && same(token, mine)
    }

    companion object {
        const val MAX_WRONG = 5
        const val LOCK_MS = 5 * 60_000L
        const val MAX_CLIENTS = 32
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun randomCode(): String {
            val r = SecureRandom()
            return (1..6).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
        }

        fun randomToken(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        /** Equality that takes the same time wherever the texts differ. */
        private fun same(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }
    }
}
