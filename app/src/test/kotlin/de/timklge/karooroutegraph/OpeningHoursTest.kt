package de.timklge.karooroutegraph

import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpeningHoursTest {

    // ─── helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds a Calendar in the system default timezone for the given date/time
     * components and returns its epoch-millisecond value. This matches the
     * production code which uses Calendar.getInstance() (default timezone).
     */
    private fun ms(
        year: Int, month: Int, day: Int,
        hour: Int, minute: Int = 0
    ): Long = Calendar.getInstance().apply {
        set(year, month, day, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // April 2026 reference dates (current date context: 2026-04-09 = Thursday)
    // April 6  = Monday
    // April 9  = Thursday
    // April 10 = Friday
    // April 11 = Saturday
    // April 12 = Sunday

    // ─── isOpen: "Mo-Su 08:00-23:00" ─────────────────────────────────────────

    private val everyDayMorningToNight = "Mo-Su 08:00-23:00"

    @Test
    fun `isOpen - Mo-Su 08-23 - open during the day`() {
        // Monday 10:00
        assertTrue(isOpen(ms(2026, Calendar.APRIL, 6, 10, 0), everyDayMorningToNight))
    }

    @Test
    fun `isOpen - Mo-Su 08-23 - open at exact start`() {
        // Monday 08:00
        assertTrue(isOpen(ms(2026, Calendar.APRIL, 6, 8, 0), everyDayMorningToNight))
    }

    @Test
    fun `isOpen - Mo-Su 08-23 - closed before opening`() {
        // Monday 07:59
        assertFalse(isOpen(ms(2026, Calendar.APRIL, 6, 7, 59), everyDayMorningToNight))
    }

    @Test
    fun `isOpen - Mo-Su 08-23 - closed at exact end`() {
        // Monday 23:00 (end is exclusive)
        assertFalse(isOpen(ms(2026, Calendar.APRIL, 6, 23, 0), everyDayMorningToNight))
    }

    @Test
    fun `isOpen - Mo-Su 08-23 - closed after end`() {
        // Sunday 23:30
        assertFalse(isOpen(ms(2026, Calendar.APRIL, 12, 23, 30), everyDayMorningToNight))
    }

    @Test
    fun `isOpen - Mo-Su 08-23 - open on Sunday before end`() {
        // Sunday 22:59
        assertTrue(isOpen(ms(2026, Calendar.APRIL, 12, 22, 59), everyDayMorningToNight))
    }

    // ─── isOpen: "06:00-18:00" ────────────────────────────────────────────────

    private val timeOnlyMidDay = "06:00-18:00"

    @Test
    fun `isOpen - 06-18 - open at midday`() {
        assertTrue(isOpen(ms(2026, Calendar.APRIL, 9, 12, 0), timeOnlyMidDay))
    }

    @Test
    fun `isOpen - 06-18 - open at exact start`() {
        assertTrue(isOpen(ms(2026, Calendar.APRIL, 9, 6, 0), timeOnlyMidDay))
    }

    @Test
    fun `isOpen - 06-18 - closed before start`() {
        assertFalse(isOpen(ms(2026, Calendar.APRIL, 9, 5, 59), timeOnlyMidDay))
    }

    @Test
    fun `isOpen - 06-18 - closed at exact end`() {
        assertFalse(isOpen(ms(2026, Calendar.APRIL, 9, 18, 0), timeOnlyMidDay))
    }

    @Test
    fun `isOpen - 06-18 - closed at midnight`() {
        assertFalse(isOpen(ms(2026, Calendar.APRIL, 9, 0, 0), timeOnlyMidDay))
    }

    // ─── isOpen: "Apr-Sep: Sa 10:00-13:00" ───────────────────────────────────

    private val seasonalSaturday = "Apr-Sep: Sa 10:00-13:00"

    @Test
    fun `isOpen - Apr-Sep Sa 10-13 - open on Saturday in April`() {
        // April 11, 2026 = Saturday, 11:00
        assertTrue(isOpen(ms(2026, Calendar.APRIL, 11, 11, 0), seasonalSaturday))
    }

    @Test
    fun `isOpen - Apr-Sep Sa 10-13 - open on Saturday in September`() {
        // September 5, 2026 = Saturday, 12:00
        assertTrue(isOpen(ms(2026, Calendar.SEPTEMBER, 5, 12, 0), seasonalSaturday))
    }

    @Test
    fun `isOpen - Apr-Sep Sa 10-13 - closed on Saturday in October`() {
        // October 10, 2026 = Saturday, 11:00
        assertFalse(isOpen(ms(2026, Calendar.OCTOBER, 10, 11, 0), seasonalSaturday))
    }

    @Test
    fun `isOpen - Apr-Sep Sa 10-13 - closed on Saturday in March`() {
        // March 7, 2026 = Saturday, 11:00
        assertFalse(isOpen(ms(2026, Calendar.MARCH, 7, 11, 0), seasonalSaturday))
    }

    @Test
    fun `isOpen - Apr-Sep Sa 10-13 - closed on Sunday in April`() {
        // April 12, 2026 = Sunday, 11:00
        assertFalse(isOpen(ms(2026, Calendar.APRIL, 12, 11, 0), seasonalSaturday))
    }

    @Test
    fun `isOpen - Apr-Sep Sa 10-13 - closed before opening on Saturday in April`() {
        // April 11, 2026 = Saturday, 09:59
        assertFalse(isOpen(ms(2026, Calendar.APRIL, 11, 9, 59), seasonalSaturday))
    }

    @Test
    fun `isOpen - Apr-Sep Sa 10-13 - closed at exact end on Saturday`() {
        // April 11, 2026 = Saturday, 13:00
        assertFalse(isOpen(ms(2026, Calendar.APRIL, 11, 13, 0), seasonalSaturday))
    }

    // ─── isOpen: "Su-Th 11:00-03:00, Fr-Sa 11:00-05:00" (overnight) ──────────

    private val overnight = "Su-Th 11:00-03:00, Fr-Sa 11:00-05:00"

    @Test
    fun `isOpen - overnight - open on Monday at noon`() {
        // Monday 12:00 is within Su-Th 11-03
        assertTrue(isOpen(ms(2026, Calendar.APRIL, 6, 12, 0), overnight))
    }

    @Test
    fun `isOpen - overnight - open on Monday at 02h30 overnight from Sunday`() {
        // Monday 02:30 is still within Su-Th 11-03 overnight span
        assertTrue(isOpen(ms(2026, Calendar.APRIL, 6, 2, 30), overnight))
    }

    @Test
    fun `isOpen - overnight - closed on Monday at 03h00 end exclusive`() {
        // Monday 03:00 is the end boundary, exclusive
        assertFalse(isOpen(ms(2026, Calendar.APRIL, 6, 3, 0), overnight))
    }

    @Test
    fun `isOpen - overnight - closed on Monday at 03h30`() {
        assertFalse(isOpen(ms(2026, Calendar.APRIL, 6, 3, 30), overnight))
    }

    @Test
    fun `isOpen - overnight - closed on Sunday before opening`() {
        // Sunday 10:59
        assertFalse(isOpen(ms(2026, Calendar.APRIL, 12, 10, 59), overnight))
    }

    @Test
    fun `isOpen - overnight - open on Sunday at 11h00`() {
        assertTrue(isOpen(ms(2026, Calendar.APRIL, 12, 11, 0), overnight))
    }

    @Test
    fun `isOpen - overnight - open on Friday at 23h00`() {
        // Friday 23:00 within Fr-Sa 11-05
        assertTrue(isOpen(ms(2026, Calendar.APRIL, 10, 23, 0), overnight))
    }

    @Test
    fun `isOpen - overnight - open on Saturday at 04h30 overnight from Friday`() {
        // Saturday 04:30 still within Fr-Sa 11-05 overnight span
        assertTrue(isOpen(ms(2026, Calendar.APRIL, 11, 4, 30), overnight))
    }

    @Test
    fun `isOpen - overnight - closed on Saturday at 06h00`() {
        // Saturday 06:00 is after the Fr-Sa 11-05 window, and before Sa 11:00
        assertFalse(isOpen(ms(2026, Calendar.APRIL, 11, 6, 0), overnight))
    }

    @Test
    fun `isOpen - overnight - open on Saturday at 11h00`() {
        // Saturday itself is also in Fr-Sa
        assertTrue(isOpen(ms(2026, Calendar.APRIL, 11, 11, 0), overnight))
    }

    // ─── isOpen: invalid strings ──────────────────────────────────────────────

    @Test
    fun `isOpen - invalid format 0600-1800 - does not throw`() {
        // Should not crash; result is indeterminate but must not throw
        isOpen(ms(2026, Calendar.APRIL, 9, 12, 0), "0600-1800")
    }

    @Test
    fun `isOpen - invalid format with semicolon separator - does not throw`() {
        isOpen(ms(2026, Calendar.APRIL, 9, 12, 0), "07;00-2;00pm")
    }

    @Test
    fun `isOpen - invalid format with slash notation - does not throw`() {
        isOpen(ms(2026, Calendar.APRIL, 9, 12, 0), "10:00 - 13:30 / 17:00 - 20:30")
    }

    // ─── getTimeUntilNextChange: "Mo-Su 08:00-23:00" ─────────────────────────

    @Test
    fun `getTimeUntilNextChange - Mo-Su 08-23 - open at 10h00 returns time until 23h00`() {
        // Monday 10:00 -> open -> next change at 23:00 = 13 hours
        val timeMs = ms(2026, Calendar.APRIL, 6, 10, 0)
        val expected = 13L * 60 * 60 * 1000
        assertEquals(expected, getTimeUntilNextChange(timeMs, everyDayMorningToNight))
    }

    @Test
    fun `getTimeUntilNextChange - Mo-Su 08-23 - closed at 07h00 returns time until 08h00`() {
        // Monday 07:00 -> closed -> next change at 08:00 = 1 hour
        val timeMs = ms(2026, Calendar.APRIL, 6, 7, 0)
        val expected = 1L * 60 * 60 * 1000
        assertEquals(expected, getTimeUntilNextChange(timeMs, everyDayMorningToNight))
    }

    // ─── getTimeUntilNextChange: "06:00-18:00" ────────────────────────────────

    @Test
    fun `getTimeUntilNextChange - 06-18 - closed at 05h00 returns time until 06h00`() {
        // 05:00 -> closed -> next change at 06:00 = 1 hour
        val timeMs = ms(2026, Calendar.APRIL, 9, 5, 0)
        val expected = 1L * 60 * 60 * 1000
        assertEquals(expected, getTimeUntilNextChange(timeMs, timeOnlyMidDay))
    }

    @Test
    fun `getTimeUntilNextChange - 06-18 - open at 06h00 returns time until 18h00`() {
        // 06:00 -> open -> next change at 18:00 = 12 hours
        val timeMs = ms(2026, Calendar.APRIL, 9, 6, 0)
        val expected = 12L * 60 * 60 * 1000
        assertEquals(expected, getTimeUntilNextChange(timeMs, timeOnlyMidDay))
    }

    // ─── getTimeUntilNextChange: overnight ────────────────────────────────────

    @Test
    fun `getTimeUntilNextChange - overnight - open at Monday noon returns time until 03h00`() {
        // Monday 12:00 -> open -> next change at Tuesday 03:00 = 15 hours
        val timeMs = ms(2026, Calendar.APRIL, 6, 12, 0)
        val expected = 15L * 60 * 60 * 1000
        assertEquals(expected, getTimeUntilNextChange(timeMs, overnight))
    }

    @Test
    fun `getTimeUntilNextChange - overnight - closed at Monday 05h00 returns time until Monday 11h00`() {
        // Monday 05:00 -> closed -> next change at Monday 11:00 = 6 hours
        val timeMs = ms(2026, Calendar.APRIL, 6, 5, 0)
        val expected = 6L * 60 * 60 * 1000
        assertEquals(expected, getTimeUntilNextChange(timeMs, overnight))
    }

    // ─── getTimeUntilNextChange: invalid strings ──────────────────────────────

    @Test
    fun `getTimeUntilNextChange - invalid format - does not throw and returns positive`() {
        val timeMs = ms(2026, Calendar.APRIL, 9, 12, 0)
        val result = getTimeUntilNextChange(timeMs, "0600-1800")
        assertTrue(result > 0)
    }
}
