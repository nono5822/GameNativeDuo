package app.gamenative.duo.community

import java.util.Base64
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ed25519Test {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // RFC 8032 section 7.1, tests 1 to 3.
    private val vectors = listOf(
        Triple(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            "",
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        ),
        Triple(
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
            "72",
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
        ),
        Triple(
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
            "af82",
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
        ),
    )

    @Test
    fun rfc8032VectorsVerify() {
        for ((key, message, signature) in vectors) assertTrue(Ed25519.verify(hex(key), hex(message), hex(signature)))
    }

    @Test
    fun alteredMessageOrSignatureFails() {
        val (key, message, signature) = vectors[2]
        assertFalse(Ed25519.verify(hex(key), hex("af83"), hex(signature)))
        val badSignature = hex(signature).also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFalse(Ed25519.verify(hex(key), hex(message), badSignature))
        assertFalse(Ed25519.verify(hex(vectors[0].first), hex(message), hex(signature)))
    }

    /** A real catalog as the community repo's CI published it, checked against the key the app pins. */
    @Test
    fun publishedCatalogVerifiesWithPinnedKey() {
        val catalog = Base64.getDecoder().decode("eyJkZXZpY2VzIjpbeyJhdXRob3JzIjpbIkdhbWVOYXRpdmUgRHVvIl0sImRlc2NyaXB0aW9uIjoiVG9wIDZcIiAxOTIweDEwODAgKEFuZHJvaWQncyBkZWZhdWx0IGRpc3BsYXkpLCBib3R0b20gMy45MlwiIDEyNDB4MTA4MC4gU2NyZWVuIGFsaWdubWVudCB0dW5lZCBieSBleWUgb24gYSBUaG9yLiIsImZpbGVzIjpbeyJwYXRoIjoiZGV2aWNlLXByb2ZpbGUuanNvbiIsInNoYTI1NiI6ImY0Y2VkZDU3ODZhM2U1OTJjZGJkZjM1YjlmYTNhOTBkM2U4NTc4NTg1YzE1ZmVjN2E2MDNiNzg3OTgyMmQ2NWEiLCJzaXplIjoyNzMsInVybCI6Imh0dHBzOi8vbm9ubzU4MjIuZ2l0aHViLmlvL0dhbWVOYXRpdmVEdW8tY29tbXVuaXR5L3YxL2Jsb2JzL2Y0Y2VkZDU3ODZhM2U1OTJjZGJkZjM1YjlmYTNhOTBkM2U4NTc4NTg1YzE1ZmVjN2E2MDNiNzg3OTgyMmQ2NWEifV0sImlkIjoiZ2FtZW5hdGl2ZS1kdW8uYXluLXRob3IiLCJraW5kIjoiZGV2aWNlLXByb2ZpbGUiLCJsaWNlbnNlIjoiQ0MwLTEuMCIsInBhY2thZ2VWZXJzaW9uIjoiMS4wLjAiLCJzY3JlZW5zaG90cyI6W10sInRlc3RlZE9uIjpbImF5bi10aG9yIl0sInRpdGxlIjoiQVlOIFRob3IiLCJ1cGRhdGVkIjoiMjAyNi0xMC0xOVQyMjoxMjozMS0wMzowMCJ9XSwiZm9ybWF0IjoiZ2FtZW5hdGl2ZS1kdW8vaW5kZXgtY2F0YWxvZyIsImdhbWVzIjpbeyJnYW1lIjp7ImFwcElkIjoiNDMzMzQwIiwic3RvcmUiOiJzdGVhbSIsInRpdGxlIjoiU2xpbWUgUmFuY2hlciJ9LCJrZXkiOiJzdGVhbS00MzMzNDAiLCJraW5kcyI6WyJkdW8tc2V0dGluZ3MiLCJ1aS1sYXlvdXQiXSwicGFja2FnZXMiOjIsInVwZGF0ZWQiOiIyMDI2LTEwLTE5VDIyOjEyOjMxLTAzOjAwIn1dLCJnZW5lcmF0ZWQiOiIyMDI2LTEwLTE5VDIyOjEyOjMxLTAzOjAwIiwidmVyc2lvbiI6MX0=")
        val signature = Base64.getDecoder().decode("hB3D2sahrFs43k50Wr+bY9StIEzZSQMqYTKDvN+hOdhrmbQytQv0rNbrnBnQf5DeL2D2brzF9MfDes2C0WIwAA==")
        val key = Base64.getDecoder().decode(CommunityIndex.PUBLIC_KEY)
        assertTrue(Ed25519.verify(key, catalog, signature))
        assertFalse(Ed25519.verify(key, catalog + ' '.code.toByte(), signature))
    }
}
