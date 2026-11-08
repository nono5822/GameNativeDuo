package app.gamenative.duo.community

import app.gamenative.duo.bridge.DuoBridge
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityTrustTest {
    private val key1 = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOld0000000000000000000000000000000000000000"
    private val key2 = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINew0000000000000000000000000000000000000000"

    private fun entry() = JSONObject()
        .put("id", "me.mod").put("kind", "mod").put("packageVersion", "1.1.0").put("title", "Mod")
        .put("files", JSONArray().put(JSONObject().put("path", "plugin/Mod.dll").put("sha256", "AB").put("url", "https://x/ab")))
        .put("authorKey", key2).put("authorKeys", JSONArray().put(key1).put(key2).put("rsa-sha2 nope"))
        .put("builtFrom", "https://github.com/me/mod@0123")
        .put(
            "bridge",
            JSONObject()
                .put("capabilities", JSONArray().put("touch").put("state").put("haptic"))
                .put("commands", JSONArray().put("me.map.*").put("me.ping")),
        )

    @Test
    fun parsesTrustFields() {
        val p = CommunityPackage.fromJson(entry())!!
        assertEquals(key2, p.authorKey)
        assertEquals(listOf(key1, key2), p.authorKeys)
        assertEquals("https://github.com/me/mod@0123", p.builtFrom)
        assertNull(p.revoked)
        assertEquals("sent saves away", CommunityPackage.fromJson(entry().put("revoked", "sent saves away"))!!.revoked)
        assertNull(CommunityPackage.fromJson(entry().put("authorKey", "ssh-rsa AAAA"))!!.authorKey)
    }

    @Test
    fun bridgePermissions() {
        val b = CommunityPackage.fromJson(entry())!!.bridge!!
        assertEquals(DuoBridge.CAP_TOUCH or DuoBridge.CAP_STATE, b.capabilityMask)
        assertTrue(b.haptic)
        assertTrue(b.allowsCommand("me.map.zoom"))
        assertTrue(b.allowsCommand("me.ping"))
        assertFalse(b.allowsCommand("me.pingall"))
        assertFalse(b.allowsCommand("other.map.zoom"))
        assertEquals(b, BridgePermissions.fromJson(b.toJson()))
    }

    @Test
    fun undeclaredPacketsAreDropped() {
        val b = BridgePermissions(setOf("touch", "elements"), emptyList())
        assertTrue(b.allowsIncoming(DuoBridge.HELLO))
        assertTrue(b.allowsIncoming(DuoBridge.LOG))
        assertTrue(b.allowsIncoming(DuoBridge.ELEMENTS_WRITTEN))
        assertFalse(b.allowsIncoming(DuoBridge.STATE))
        assertFalse(b.allowsIncoming(DuoBridge.HAPTIC))
        assertFalse(b.allowsIncoming(DuoBridge.COMMAND_RESULT))
        assertFalse(b.allowsIncoming(DuoBridge.ELEMENTS_CHANGED))
    }
}

class AuthorKeyPinsTest {
    private val a = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAaaa"
    private val b = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBbbb"
    private val c = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAICccc"

    private fun pkg(key: String?, chain: List<String> = listOfNotNull(key)) = CommunityPackage(
        id = "me.mod", kind = CommunityKind.MOD, version = "1.0.0", title = "t", description = "d", authors = listOf("a"),
        license = "MIT", adapter = null, testedOn = emptyList(), gameBuilds = emptyList(), tags = emptyList(),
        files = emptyList(), screenshots = emptyList(), updated = "", authorKey = key, authorKeys = chain,
    )

    @Test
    fun ownerChecks() {
        assertEquals(AuthorKeyPins.Check.FIRST, AuthorKeyPins.check(null, pkg(a)))
        assertEquals(AuthorKeyPins.Check.SAME, AuthorKeyPins.check(a, pkg(a)))
        assertEquals(AuthorKeyPins.Check.HANDED_OVER, AuthorKeyPins.check(a, pkg(b, listOf(a, b))))
        assertEquals(AuthorKeyPins.Check.HANDED_OVER, AuthorKeyPins.check(a, pkg(c, listOf(a, b, c))))
        assertEquals(AuthorKeyPins.Check.CHANGED_OWNER, AuthorKeyPins.check(a, pkg(c)))
        assertEquals(AuthorKeyPins.Check.CHANGED_OWNER, AuthorKeyPins.check(a, pkg(null)))
        assertEquals("TE5AAAAIBbbb", AuthorKeyPins.fingerprint(b))
    }

    @Test
    fun revokedVersionIsNotInstalled() {
        val install = CommunityInstall(emptyMap(), emptyMap(), emptyMap(), emptyList(), null, null)
        val revoked = pkg(a).copy(revoked = "sent saves away", install = install)
        val e = org.junit.Assert.assertThrows(java.io.IOException::class.java) { CommunityModInstaller.plan(revoked) }
        assertTrue(e.message!!.contains("sent saves away"))
    }
}
