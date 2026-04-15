package de.timklge.karooroutegraph

import android.content.Context
import android.util.Log
import de.timklge.karooroutegraph.KarooRouteGraphExtension.Companion.TAG
import de.timklge.karooroutegraph.ohparser.ohminLexer
import de.timklge.karooroutegraph.ohparser.ohminParser
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import java.util.Calendar
import java.util.Date

private enum class OhStatus { OPEN, CLOSED, UNKNOWN }

/**
 * Determines if a location is currently open based on the provided opening hours string and the current time.
 *
 * @param currentTimeMs The current time in milliseconds since the epoch.
 * @param openingHours A string representing the opening hours, adhering to the OSM opening hours grammar
 * @return True if the location is currently open, false otherwise.
 */
fun isOpen(currentTimeMs: Long, openingHours: String): Boolean {
    val cal = Calendar.getInstance().apply { timeInMillis = currentTimeMs }

    return parseAndEvaluate(openingHours.trim(), cal) == OhStatus.OPEN
}

/**
 * Returns the time in milliseconds until the next change in open/closed status based on the provided opening hours string.
 *
 * @param currentTimeMs The current time in milliseconds since the epoch.
 * @param openingHours A string representing the opening hours, adhering to the OSM opening hours grammar
 * @return The time in milliseconds until the next change in open/closed status. If there are no more changes today, it returns the time until the next opening or closing event on the following day.
 */
fun getTimeUntilNextChange(currentTimeMs: Long, openingHours: String): Long {
    val currentOpen = isOpen(currentTimeMs, openingHours)
    val eventTimes = collectEventTimes(currentTimeMs, openingHours)

    for (eventMs in eventTimes) {
        if (eventMs > currentTimeMs) {
            val openAtEvent = isOpen(eventMs, openingHours)
            if (openAtEvent != currentOpen) {
                return eventMs - currentTimeMs
            }
        }
    }

    return 7L * 24 * 60 * 60 * 1000;
}

/** Collect candidate event times: midnight of each day + all time-span boundaries from the rules. */
private fun collectEventTimes(currentTimeMs: Long, openingHours: String): List<Long> {
    val boundaries = extractTimeBoundaries(openingHours)
    val events = mutableListOf<Long>()

    for (dayOffset in 0..8) {
        val dayCal = Calendar.getInstance().apply {
            timeInMillis = currentTimeMs
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        events.add(dayCal.timeInMillis)

        for (minutes in boundaries) {
            val eventCal = dayCal.clone() as Calendar
            eventCal.add(Calendar.MINUTE, minutes)
            events.add(eventCal.timeInMillis)
        }
    }

    return events.sorted().distinct()
}

private fun extractTimeBoundaries(openingHours: String): List<Int> {
    val boundaries = mutableListOf<Int>()
    try {
        val tree = parseOpeningHours(openingHours) ?: return emptyList()
        for (seq in tree.rule_sequence()) {
            val ss = seq.selector_sequence() ?: continue
            ss.small_range_selectors()?.let { srs ->
                for (ts in srs.time_selector()) {
                    for (timespan in ts.timespan()) {
                        extractTimespanBoundaries(timespan, boundaries)
                    }
                }
            }
        }
    } catch (e: Exception) { /* ignore */ }
    return boundaries.distinct()
}

private fun extractTimespanBoundaries(ctx: ohminParser.TimespanContext, boundaries: MutableList<Int>) {
    ctx.timespan_range()?.time()?.takeIf { it.size >= 2 }?.let { times ->
        boundaries.add(extractMinutes(times[0]))
        boundaries.add(extractMinutes(times[1]))
    }
    ctx.timespan_openended()?.time()?.let { boundaries.add(extractMinutes(it)) }
    ctx.timespan_range_openended()?.timespan_range()?.time()?.takeIf { it.size >= 2 }?.let { times ->
        boundaries.add(extractMinutes(times[0]))
        boundaries.add(extractMinutes(times[1]))
    }
    ctx.timespan_range_cron()?.time()?.takeIf { it.size >= 2 }?.let { times ->
        boundaries.add(extractMinutes(times[0]))
        boundaries.add(extractMinutes(times[1]))
    }
}

private fun extractMinutes(ctx: ohminParser.TimeContext): Int {
    ctx.hh_mm()?.let { hhmm ->
        val h = hhmm.chour().NUMBERS().text.toIntOrNull() ?: 0
        val m = hhmm.cminute().NUMBERS().text.toIntOrNull() ?: 0
        return h * 60 + m
    }
    ctx.variable_time()?.csunlightevent()?.let { event ->
        return when (event.start.type) {
            ohminParser.T__27 -> 6 * 60        // dawn ~6:00
            ohminParser.T__28 -> 6 * 60 + 30  // sunrise ~6:30
            ohminParser.T__29 -> 19 * 60 + 30 // sunset ~19:30
            ohminParser.T__30 -> 20 * 60       // dusk ~20:00
            else -> 0
        }
    }
    return 0
}

private fun parseOpeningHours(openingHours: String): ohminParser.Opening_hoursContext? {
    return try {
        val input = CharStreams.fromString(openingHours)
        val lexer = ohminLexer(input)
        lexer.removeErrorListeners()
        val tokens = CommonTokenStream(lexer)
        val parser = ohminParser(tokens)
        parser.removeErrorListeners()
        parser.opening_hours()
    } catch (e: Exception) {
        null
    }
}

private fun parseAndEvaluate(openingHours: String, cal: Calendar): OhStatus {
    val tree = parseOpeningHours(openingHours) ?: return OhStatus.UNKNOWN
    return evaluateOpeningHours(tree, cal)
}

private fun evaluateOpeningHours(ctx: ohminParser.Opening_hoursContext, cal: Calendar): OhStatus {
    val sequences = ctx.rule_sequence()
    val separators = ctx.rule_separator()

    var result = OhStatus.UNKNOWN

    for (i in sequences.indices) {
        val seqResult = evaluateRuleSequence(sequences[i], cal)

        if (i == 0) {
            if (seqResult != OhStatus.UNKNOWN) result = seqResult
        } else {
            val sep = separators[i - 1]
            if (sep is ohminParser.Rule_separator_fallbackContext) {
                // || : fallback – only use this rule if previous gave no definitive answer
                if (result == OhStatus.UNKNOWN && seqResult != OhStatus.UNKNOWN) {
                    result = seqResult
                }
            } else {
                // ; : last matching rule wins
                if (seqResult != OhStatus.UNKNOWN) {
                    result = seqResult
                }
            }
        }
    }

    return result
}

private fun evaluateRuleSequence(ctx: ohminParser.Rule_sequenceContext, cal: Calendar): OhStatus {
    val ss = ctx.selector_sequence() ?: return OhStatus.UNKNOWN
    if (!evaluateSelectorSequence(ss, cal)) return OhStatus.UNKNOWN

    val modifier = ctx.rule_modifier()
    return if (modifier == null) OhStatus.OPEN else evaluateRuleModifier(modifier)
}

private fun evaluateRuleModifier(ctx: ohminParser.Rule_modifierContext): OhStatus = when {
    ctx.rule_modifier_open() != null -> OhStatus.OPEN
    ctx.rule_modifier_closed() != null -> OhStatus.CLOSED
    ctx.rule_modifier_unknown() != null -> OhStatus.UNKNOWN
    else -> OhStatus.OPEN  // comment-only modifier is treated as open
}

private fun evaluateSelectorSequence(ctx: ohminParser.Selector_sequenceContext, cal: Calendar): Boolean {
    if (ctx.c247string() != null) return true

    val wrs = ctx.wide_range_selectors()
    if (wrs != null && !evaluateWideRangeSelectors(wrs, cal)) return false

    val srs = ctx.small_range_selectors()
    if (srs != null && !evaluateSmallRangeSelectors(srs, cal)) return false

    // Must have at least one selector type
    if (wrs == null && srs == null) return false

    return true
}

private fun evaluateWideRangeSelectors(ctx: ohminParser.Wide_range_selectorsContext, cal: Calendar): Boolean {
    // Comment-only wide range selector – treat as matching (no restriction)
    if (ctx.year_sel() == null && ctx.calendarmonth_selector() == null &&
        ctx.calendarmonth_range() == null && ctx.week_selector() == null &&
        ctx.date_from() == null) {
        return true
    }

    var matches = true

    ctx.year_sel()?.let { matches = matches && evaluateYearSel(it, cal) }
    ctx.calendarmonth_selector()?.let { matches = matches && evaluateCalendarMonthSelector(it, cal) }
    ctx.calendarmonth_range()?.let { matches = matches && evaluateCalendarMonthRange(it, cal) }
    ctx.date_from()?.let { matches = matches && evaluateDateFromAsExactDate(it, cal) }
    ctx.week_selector()?.let { matches = matches && evaluateWeekSelector(it, cal) }

    return matches
}

private fun evaluateDateFromAsExactDate(ctx: ohminParser.Date_fromContext, cal: Calendar): Boolean {
    val monthCtx = ctx.cmonth() ?: return true
    val dayCtx = ctx.cday() ?: return true
    val month = tokenTypeToMonth(monthCtx.start.type)
    val day = dayCtx.NUMBERS().text.toIntOrNull() ?: return true
    return cal.get(Calendar.MONTH) == month && cal.get(Calendar.DAY_OF_MONTH) == day
}

private fun evaluateYearSel(ctx: ohminParser.Year_selContext, cal: Calendar): Boolean {
    val currentYear = cal.get(Calendar.YEAR)
    return ctx.year_selector().any { evaluateYearSelector(it, currentYear) }
}

private fun evaluateYearSelector(ctx: ohminParser.Year_selectorContext, year: Int): Boolean {
    ctx.year_selector_single()?.let { single ->
        return single.NUMBERS().text.toIntOrNull() == year
    }
    ctx.year_selector_range()?.let { range ->
        val singles = range.year_selector_single()
        if (singles.size >= 2) {
            val start = singles[0].NUMBERS().text.toIntOrNull() ?: return false
            val end = singles[1].NUMBERS().text.toIntOrNull() ?: return false
            return year in start..end
        }
    }
    ctx.year_selector_single_openended()?.let { oe ->
        val start = oe.year_selector_single().NUMBERS().text.toIntOrNull() ?: return false
        return year >= start
    }
    ctx.year_selector_single_cron()?.let { cron ->
        val start = cron.year_selector_single().NUMBERS().text.toIntOrNull() ?: return false
        val step = cron.positive_integer().NUMBERS().text.toIntOrNull() ?: return false
        return year >= start && (year - start) % step == 0
    }
    ctx.year_selector_range_cron()?.let { rangeCron ->
        val singles = rangeCron.year_selector_range().year_selector_single()
        if (singles.size >= 2) {
            val start = singles[0].NUMBERS().text.toIntOrNull() ?: return false
            val end = singles[1].NUMBERS().text.toIntOrNull() ?: return false
            val step = rangeCron.positive_integer().NUMBERS().text.toIntOrNull() ?: return false
            if (year !in start..end) return false
            return (year - start) % step == 0
        }
    }
    return false
}

private fun evaluateCalendarMonthSelector(ctx: ohminParser.Calendarmonth_selectorContext, cal: Calendar): Boolean =
    ctx.calendarmonth_range().any { evaluateCalendarMonthRange(it, cal) }

private fun evaluateCalendarMonthRange(ctx: ohminParser.Calendarmonth_rangeContext, cal: Calendar): Boolean {
    val currentMonth = cal.get(Calendar.MONTH)

    ctx.calendarmonth_range_single()?.let { single ->
        return tokenTypeToMonth(single.cmonth().start.type) == currentMonth
    }
    ctx.calendarmonth_range_range()?.let { range ->
        val months = range.cmonth()
        if (months.size >= 2) {
            val start = tokenTypeToMonth(months[0].start.type)
            val end = tokenTypeToMonth(months[1].start.type)
            return if (start <= end) currentMonth in start..end
            else currentMonth >= start || currentMonth <= end
        }
    }
    ctx.calendarmonth_range_cron()?.let { cron ->
        val months = cron.cmonth()
        if (months.size >= 2) {
            val start = tokenTypeToMonth(months[0].start.type)
            val end = tokenTypeToMonth(months[1].start.type)
            val step = cron.positive_integer().NUMBERS().text.toIntOrNull() ?: return true
            val inRange = if (start <= end) currentMonth in start..end
                          else currentMonth >= start || currentMonth <= end
            if (!inRange) return false
            val offset = if (currentMonth >= start) currentMonth - start else currentMonth + 12 - start
            return offset % step == 0
        }
    }
    ctx.calendarmonth_range_from_openended()?.let { oe ->
        val dateFrom = oe.date_from() ?: return true
        val month = dateFrom.cmonth()?.let { tokenTypeToMonth(it.start.type) } ?: return true
        val day = dateFrom.cday()?.NUMBERS()?.text?.toIntOrNull() ?: 1
        val currentDay = cal.get(Calendar.DAY_OF_MONTH)
        return currentMonth > month || (currentMonth == month && currentDay >= day)
    }
    ctx.calendarmonth_range_from_to()?.let { ft ->
        return evaluateCalendarMonthRangeFromTo(ft, cal)
    }

    return true // unknown sub-pattern, treat as matching
}

private fun evaluateCalendarMonthRangeFromTo(ctx: ohminParser.Calendarmonth_range_from_toContext, cal: Calendar): Boolean {
    val dateFrom = ctx.date_from() ?: return true
    val dateTo = ctx.date_to() ?: return true

    val fromMonth = dateFrom.cmonth()?.let { tokenTypeToMonth(it.start.type) } ?: return true
    val fromDay = dateFrom.cday()?.NUMBERS()?.text?.toIntOrNull() ?: 1

    val toMonth: Int
    val toDay: Int
    val toDateFrom = dateTo.date_from()
    if (toDateFrom != null) {
        toMonth = toDateFrom.cmonth()?.let { tokenTypeToMonth(it.start.type) } ?: return true
        toDay = toDateFrom.cday()?.NUMBERS()?.text?.toIntOrNull() ?: 31
    } else {
        toMonth = fromMonth
        toDay = dateTo.cday()?.NUMBERS()?.text?.toIntOrNull() ?: 31
    }

    val currentMonth = cal.get(Calendar.MONTH)
    val currentDay = cal.get(Calendar.DAY_OF_MONTH)
    // Use month*31+day as a comparable ordinal (approximate, sufficient for most cases)
    val fromOrd = fromMonth * 31 + fromDay
    val toOrd = toMonth * 31 + toDay
    val curOrd = currentMonth * 31 + currentDay

    return if (fromOrd <= toOrd) curOrd in fromOrd..toOrd
    else curOrd >= fromOrd || curOrd <= toOrd
}

/** Convert a month token type to a Calendar.MONTH value (0 = January). */
private fun tokenTypeToMonth(tokenType: Int): Int =
    (tokenType - ohminParser.T__15).coerceIn(0, 11)

private fun evaluateWeekSelector(ctx: ohminParser.Week_selectorContext, cal: Calendar): Boolean {
    val currentWeek = cal.get(Calendar.WEEK_OF_YEAR)
    return ctx.week().any { evaluateWeek(it, currentWeek) }
}

private fun evaluateWeek(ctx: ohminParser.WeekContext, currentWeek: Int): Boolean {
    ctx.week_single()?.let { single ->
        return single.cweeknum().NUMBERS().text.toIntOrNull() == currentWeek
    }
    ctx.week_range()?.let { range ->
        val nums = range.cweeknum()
        if (nums.size >= 2) {
            val start = nums[0].NUMBERS().text.toIntOrNull() ?: return false
            val end = nums[1].NUMBERS().text.toIntOrNull() ?: return false
            return currentWeek in start..end
        }
    }
    ctx.week_range_cron()?.let { cron ->
        val nums = cron.cweeknum()
        if (nums.size >= 2) {
            val start = nums[0].NUMBERS().text.toIntOrNull() ?: return false
            val end = nums[1].NUMBERS().text.toIntOrNull() ?: return false
            val step = cron.positive_integer().NUMBERS().text.toIntOrNull() ?: return false
            return currentWeek in start..end && (currentWeek - start) % step == 0
        }
    }
    return false
}

private fun evaluateSmallRangeSelectors(ctx: ohminParser.Small_range_selectorsContext, cal: Calendar): Boolean {
    val weekdaySelectors = ctx.weekday_selector()
    val timeSelectors = ctx.time_selector()

    val currentDow = cal.get(Calendar.DAY_OF_WEEK)
    val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

    // Alt 1: weekday only (all times of day)
    if (weekdaySelectors.isNotEmpty() && timeSelectors.isEmpty()) {
        return weekdaySelectors.any { evaluateWeekdaySelector(it, currentDow) }
    }

    // Alt 3: time only (any day)
    if (weekdaySelectors.isEmpty() && timeSelectors.isNotEmpty()) {
        return timeSelectors.any { evaluateTimeSelector(it, currentMinutes) }
    }

    // Alt 2: paired weekday + time selectors
    if (weekdaySelectors.isNotEmpty() && timeSelectors.isNotEmpty()) {
        val pairCount = minOf(weekdaySelectors.size, timeSelectors.size)
        for (i in 0 until pairCount) {
            if (evaluateWeekdaySelector(weekdaySelectors[i], currentDow) &&
                evaluateTimeSelector(timeSelectors[i], currentMinutes)) {
                return true
            }
        }
        return false
    }

    return false
}

private fun evaluateWeekdaySelector(ctx: ohminParser.Weekday_selectorContext, currentDow: Int): Boolean {
    ctx.weekday_sequence()?.let { return evaluateWeekdaySequence(it, currentDow) }
    // holiday_sequence (PH/SH) – no holiday data available, skip
    return false
}

private fun evaluateWeekdaySequence(ctx: ohminParser.Weekday_sequenceContext, currentDow: Int): Boolean =
    ctx.weekday_ranges().any { evaluateWeekdayRanges(it, currentDow) }

private fun evaluateWeekdayRanges(ctx: ohminParser.Weekday_rangesContext, currentDow: Int): Boolean {
    ctx.weekday_ranges_single()?.let { single ->
        return tokenTypeToDayOfWeek(single.cdayoftheweek().start.type) == currentDow
    }
    ctx.weekday_ranges_range()?.let { range ->
        val days = range.cdayoftheweek()
        if (days.size >= 2) {
            val startDay = tokenTypeToDayOfWeek(days[0].start.type)
            val endDay = tokenTypeToDayOfWeek(days[1].start.type)
            return isDayInRange(currentDow, startDay, endDay)
        }
    }
    // weekday_ranges_range_nth / weekday_ranges_range_nth_offset – not supported, skip
    return false
}

private fun tokenTypeToDayOfWeek(tokenType: Int): Int = when (tokenType) {
    ohminParser.T__1,  ohminParser.T__6  -> Calendar.MONDAY    // Mo / Mon
    ohminParser.T__2,  ohminParser.T__7  -> Calendar.TUESDAY   // Tu / Tue
    ohminParser.T__3,  ohminParser.T__8  -> Calendar.WEDNESDAY // We / Wed
    ohminParser.T__4,  ohminParser.T__9  -> Calendar.THURSDAY  // Th / Thu
    ohminParser.T__5,  ohminParser.T__10 -> Calendar.FRIDAY    // Fr / Fri
    ohminParser.T__11, ohminParser.T__13 -> Calendar.SATURDAY  // Sa / Sat
    ohminParser.T__12, ohminParser.T__14 -> Calendar.SUNDAY    // Su / Sun
    else -> -1
}

/** Converts a Calendar.DAY_OF_WEEK value to the OSM Mon=1 … Sun=7 scale. */
private fun toOsmDay(calDay: Int): Int = when (calDay) {
    Calendar.MONDAY    -> 1
    Calendar.TUESDAY   -> 2
    Calendar.WEDNESDAY -> 3
    Calendar.THURSDAY  -> 4
    Calendar.FRIDAY    -> 5
    Calendar.SATURDAY  -> 6
    Calendar.SUNDAY    -> 7
    else -> -1
}

private fun isDayInRange(currentCalDay: Int, startCalDay: Int, endCalDay: Int): Boolean {
    val cur = toOsmDay(currentCalDay)
    val start = toOsmDay(startCalDay)
    val end = toOsmDay(endCalDay)
    if (cur < 0 || start < 0 || end < 0) return false
    return if (start <= end) cur in start..end
    else cur >= start || cur <= end  // wrap-around, e.g. Fr-Mo
}

private fun evaluateTimeSelector(ctx: ohminParser.Time_selectorContext, currentMinutes: Int): Boolean =
    ctx.timespan().any { evaluateTimespan(it, currentMinutes) }

private fun evaluateTimespan(ctx: ohminParser.TimespanContext, currentMinutes: Int): Boolean {
    ctx.timespan_range()?.let { return evaluateTimespanRange(it, currentMinutes) }
    ctx.timespan_openended()?.let { oe ->
        return currentMinutes >= extractMinutes(oe.time())
    }
    ctx.timespan_range_openended()?.let { roe ->
        roe.timespan_range()?.let { return evaluateTimespanRange(it, currentMinutes) }
    }
    ctx.timespan_range_cron()?.let { cron ->
        val times = cron.time()
        if (times.size >= 2) {
            return isTimeInRange(currentMinutes, extractMinutes(times[0]), extractMinutes(times[1]))
        }
    }
    return false
}

private fun evaluateTimespanRange(ctx: ohminParser.Timespan_rangeContext, currentMinutes: Int): Boolean {
    val times = ctx.time()
    if (times.size < 2) return false
    return isTimeInRange(currentMinutes, extractMinutes(times[0]), extractMinutes(times[1]))
}

private fun isTimeInRange(currentMinutes: Int, startMinutes: Int, endMinutes: Int): Boolean = when {
    endMinutes > startMinutes  -> currentMinutes in startMinutes until endMinutes
    endMinutes == startMinutes -> true  // full-day span (e.g. 00:00-00:00)
    else -> currentMinutes >= startMinutes || currentMinutes < endMinutes  // overnight
}

fun getOpeningHoursStatusLabel(eta: Long, openingHours: String?, context: Context): String? {
    val isOpenAtEta = openingHours?.let {
        try {
            isOpen(eta, openingHours)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse opening hours", e)
            null
        }
    }

    val timeUntilNextChange = openingHours?.let {
        try {
            val untilNextChange = getTimeUntilNextChange(eta, openingHours)

            if (untilNextChange < 60 * 60 * 1000) untilNextChange else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to determine if POI is closing soon", e)
            null
        }
    }

    val timeSinceLastChange = openingHours?.let {
        try {
            val sinceLastChange = 60 * 60 * 1000 - getTimeUntilNextChange(eta - 60 * 60 * 1000, openingHours)

            if (sinceLastChange < 0) sinceLastChange else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to determine if POI is opening soon", e)
            null
        }
    }

    return isOpenAtEta?.let {
        if (isOpenAtEta) {
            when {
                timeUntilNextChange != null -> context.getString(R.string.open_closing_soon, android.text.format.DateFormat.getTimeFormat(context).format(Date(eta + timeUntilNextChange)).toString())
                timeSinceLastChange != null -> context.getString(R.string.closed_since, android.text.format.DateFormat.getTimeFormat(context).format(Date(eta - timeSinceLastChange)).toString())
                else -> context.getString(R.string.open_at_eta)
            }
        } else {
            when {
                timeUntilNextChange != null -> context.getString(R.string.closed_opening_soon, android.text.format.DateFormat.getTimeFormat(context).format(Date(eta + timeUntilNextChange)).toString())
                timeSinceLastChange != null -> context.getString(R.string.open_since, android.text.format.DateFormat.getTimeFormat(context).format(Date(eta - timeSinceLastChange)).toString())
                else -> context.getString(R.string.closed_at_eta)
            }
        }
    }
}