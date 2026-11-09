package app.gamenative.duo.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModStatusReportTest {

    private fun session(
        connected: Boolean,
        durationMs: Long,
        modId: String? = "hk",
        modExpected: Boolean = true,
        splitOn: Boolean = true,
        gamescope: Boolean = false,
    ) = SessionStatus(
        startedAt = 1_000L,
        endedAt = 1_000L + durationMs,
        modId = modId,
        splitOn = splitOn,
        modExpected = modExpected,
        gamescope = gamescope,
        connected = connected,
    )

    private fun report(
        on: Boolean = true,
        gamescope: Boolean = false,
        expectedScreen: String = "1920x2160",
        screen: String = "1920x2160",
        missingFiles: Int = 0,
        modId: String? = "hk",
        last: SessionStatus? = null,
        gameUpdated: Boolean = false,
    ) = ModStatusReport.evaluate(
        on = on,
        gamescope = gamescope,
        expectedScreen = expectedScreen,
        screen = screen,
        missingFiles = missingFiles,
        modId = modId,
        last = last,
        gameUpdated = gameUpdated,
    )

    @Test
    fun `off reports no problems and does not work`() {
        val report = report(
            on = false,
            gamescope = true,
            last = session(connected = true, durationMs = 120_000L),
        )
        assertFalse(report.on)
        assertTrue(report.problems.isEmpty())
        assertFalse(report.worked)
    }

    @Test
    fun `on with all fine and connected last works`() {
        val report = report(last = session(connected = true, durationMs = 120_000L))
        assertTrue(report.on)
        assertTrue(report.problems.isEmpty())
        assertTrue(report.worked)
    }

    @Test
    fun `on with all fine but no last does not work`() {
        val report = report()
        assertTrue(report.on)
        assertTrue(report.problems.isEmpty())
        assertFalse(report.worked)
    }

    @Test
    fun `gamescope is the first problem`() {
        val report = report(gamescope = true, last = session(connected = true, durationMs = 120_000L))
        assertTrue(report.problems.contains(ModProblem.GAMESCOPE))
        assertEquals(ModProblem.GAMESCOPE, report.problems.first())
    }

    @Test
    fun `mismatched screen size is wrong size`() {
        val report = report(
            expectedScreen = "1920x2160",
            screen = "1280x720",
            last = session(connected = true, durationMs = 120_000L),
        )
        assertEquals(listOf(ModProblem.WRONG_SIZE), report.problems)
    }

    @Test
    fun `same size differing only in case is not wrong size`() {
        val report = report(
            expectedScreen = "1920x2160",
            screen = "1920X2160",
            last = session(connected = true, durationMs = 120_000L),
        )
        assertTrue(report.problems.isEmpty())
    }

    @Test
    fun `empty expected screen is not wrong size`() {
        val report = report(
            expectedScreen = "",
            screen = "1280x720",
            last = session(connected = true, durationMs = 120_000L),
        )
        assertTrue(report.problems.isEmpty())
    }

    @Test
    fun `missing files are reported`() {
        val report = report(missingFiles = 3, last = session(connected = true, durationMs = 120_000L))
        assertTrue(report.problems.contains(ModProblem.FILES_MISSING))
        assertEquals(3, report.missingFiles)
    }

    @Test
    fun `long silent same mod is not loaded`() {
        val report = report(last = session(connected = false, durationMs = 120_000L, modId = "hk"))
        assertEquals(listOf(ModProblem.MOD_NOT_LOADED), report.problems)
    }

    @Test
    fun `short silent session is not not loaded`() {
        val report = report(last = session(connected = false, durationMs = 30_000L, modId = "hk"))
        assertTrue(report.problems.isEmpty())
    }

    @Test
    fun `different mod id is not not loaded`() {
        val report = report(last = session(connected = false, durationMs = 120_000L, modId = "other"))
        assertTrue(report.problems.isEmpty())
    }

    @Test
    fun `mod not expected is not not loaded`() {
        val report = report(last = session(connected = false, durationMs = 120_000L, modExpected = false))
        assertTrue(report.problems.isEmpty())
    }

    @Test
    fun `missing files suppress mod not loaded`() {
        val report = report(missingFiles = 2, last = session(connected = false, durationMs = 120_000L))
        assertTrue(report.problems.contains(ModProblem.FILES_MISSING))
        assertFalse(report.problems.contains(ModProblem.MOD_NOT_LOADED))
    }

    @Test
    fun `everything wrong keeps priority order`() {
        val report = report(
            gamescope = true,
            expectedScreen = "1920x2160",
            screen = "1280x720",
            missingFiles = 3,
            last = session(connected = false, durationMs = 120_000L),
        )
        assertEquals(
            listOf(ModProblem.GAMESCOPE, ModProblem.WRONG_SIZE, ModProblem.FILES_MISSING),
            report.problems,
        )
    }

    @Test
    fun `game updated is carried into report`() {
        val report = report(gameUpdated = true, last = session(connected = true, durationMs = 120_000L))
        assertTrue(report.gameUpdated)
    }
}
