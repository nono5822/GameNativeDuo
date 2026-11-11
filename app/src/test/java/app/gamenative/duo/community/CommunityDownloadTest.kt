package app.gamenative.duo.community

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CommunityDownloadTest {
    @Test
    fun `a download of the declared size is read whole`() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        assertArrayEquals(bytes, CommunityPackages.readBounded(ByteArrayInputStream(bytes), bytes.size.toLong(), "a.dll"))
    }

    @Test
    fun `a download larger than declared stops`() {
        val bytes = ByteArray(200_000)
        assertThrows(IOException::class.java) {
            CommunityPackages.readBounded(ByteArrayInputStream(bytes), 199_999, "a.dll")
        }
    }

    @Test
    fun `the limit is the declared size, capped, and the cap when no size is given`() {
        assertEquals(1234L, CommunityPackages.downloadLimit(1234))
        assertEquals(0L, CommunityPackages.downloadLimit(0))
        assertEquals(CommunityPackages.MAX_DOWNLOAD, CommunityPackages.downloadLimit(-1))
        assertEquals(CommunityPackages.MAX_DOWNLOAD, CommunityPackages.downloadLimit(CommunityPackages.MAX_DOWNLOAD + 1))
    }
}
