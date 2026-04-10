package de.timklge.karooroutegraph.ohparser;// Generated from karoo-routegraph/app/src/main/ohmin.g4 by ANTLR 4.13.2

import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link ohminParser}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface ohminVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by {@link ohminParser#c247string}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitC247string(ohminParser.C247stringContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#cdayoftheweek}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCdayoftheweek(ohminParser.CdayoftheweekContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#cworkdays}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCworkdays(ohminParser.CworkdaysContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#cworkdays2letters}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCworkdays2letters(ohminParser.Cworkdays2lettersContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#cworkdays3letters}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCworkdays3letters(ohminParser.Cworkdays3lettersContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#cweekend}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCweekend(ohminParser.CweekendContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#cweekend2letters}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCweekend2letters(ohminParser.Cweekend2lettersContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#cweekend3letters}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCweekend3letters(ohminParser.Cweekend3lettersContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#cmonth}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCmonth(ohminParser.CmonthContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#csunlightevent}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCsunlightevent(ohminParser.CsunlighteventContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#coffsetsymbols}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCoffsetsymbols(ohminParser.CoffsetsymbolsContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#cminute}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCminute(ohminParser.CminuteContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#chour}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitChour(ohminParser.ChourContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#cday}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCday(ohminParser.CdayContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#cweeknum}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCweeknum(ohminParser.CweeknumContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#cwrappinghour}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCwrappinghour(ohminParser.CwrappinghourContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#positive_integer}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPositive_integer(ohminParser.Positive_integerContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#negative_integer}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNegative_integer(ohminParser.Negative_integerContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#hh_mm}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHh_mm(ohminParser.Hh_mmContext ctx);
	/**
	 * Visit a parse tree produced by the {@code Nonemptycomment}
	 * labeled alternative in {@link ohminParser#comment}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNonemptycomment(ohminParser.NonemptycommentContext ctx);
	/**
	 * Visit a parse tree produced by the {@code Emptycomment}
	 * labeled alternative in {@link ohminParser#comment}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEmptycomment(ohminParser.EmptycommentContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#nth_entry}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNth_entry(ohminParser.Nth_entryContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#opening_hours}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOpening_hours(ohminParser.Opening_hoursContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#rule_sequence}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRule_sequence(ohminParser.Rule_sequenceContext ctx);
	/**
	 * Visit a parse tree produced by the {@code rule_separator_normal}
	 * labeled alternative in {@link ohminParser#rule_separator}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRule_separator_normal(ohminParser.Rule_separator_normalContext ctx);
	/**
	 * Visit a parse tree produced by the {@code rule_separator_fallback}
	 * labeled alternative in {@link ohminParser#rule_separator}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRule_separator_fallback(ohminParser.Rule_separator_fallbackContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#rule_modifier}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRule_modifier(ohminParser.Rule_modifierContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#rule_modifier_open}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRule_modifier_open(ohminParser.Rule_modifier_openContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#rule_modifier_closed}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRule_modifier_closed(ohminParser.Rule_modifier_closedContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#rule_modifier_unknown}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRule_modifier_unknown(ohminParser.Rule_modifier_unknownContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#rule_modifier_comment}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRule_modifier_comment(ohminParser.Rule_modifier_commentContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#selector_sequence}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSelector_sequence(ohminParser.Selector_sequenceContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#small_range_selectors}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSmall_range_selectors(ohminParser.Small_range_selectorsContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#weekday_selector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWeekday_selector(ohminParser.Weekday_selectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#weekday_sequence}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWeekday_sequence(ohminParser.Weekday_sequenceContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#weekday_ranges}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWeekday_ranges(ohminParser.Weekday_rangesContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#weekday_ranges_single}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWeekday_ranges_single(ohminParser.Weekday_ranges_singleContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#weekday_ranges_range}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWeekday_ranges_range(ohminParser.Weekday_ranges_rangeContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#weekday_ranges_range_nth}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWeekday_ranges_range_nth(ohminParser.Weekday_ranges_range_nthContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#weekday_ranges_range_nth_offset}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWeekday_ranges_range_nth_offset(ohminParser.Weekday_ranges_range_nth_offsetContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#holiday_sequence}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHoliday_sequence(ohminParser.Holiday_sequenceContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singular_day_holiday}
	 * labeled alternative in {@link ohminParser#holiday}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingular_day_holiday(ohminParser.Singular_day_holidayContext ctx);
	/**
	 * Visit a parse tree produced by the {@code plural_day_holiday}
	 * labeled alternative in {@link ohminParser#holiday}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPlural_day_holiday(ohminParser.Plural_day_holidayContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#day_offset}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDay_offset(ohminParser.Day_offsetContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#time_selector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTime_selector(ohminParser.Time_selectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#timespan}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTimespan(ohminParser.TimespanContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#timespan_range}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTimespan_range(ohminParser.Timespan_rangeContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#timespan_range_openended}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTimespan_range_openended(ohminParser.Timespan_range_openendedContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#timespan_range_cron}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTimespan_range_cron(ohminParser.Timespan_range_cronContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#timespan_openended}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTimespan_openended(ohminParser.Timespan_openendedContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#time}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTime(ohminParser.TimeContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#variable_time}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVariable_time(ohminParser.Variable_timeContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#wide_range_selectors}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWide_range_selectors(ohminParser.Wide_range_selectorsContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#year_sel}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitYear_sel(ohminParser.Year_selContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#year_selector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitYear_selector(ohminParser.Year_selectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#year_selector_range}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitYear_selector_range(ohminParser.Year_selector_rangeContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#year_selector_range_cron}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitYear_selector_range_cron(ohminParser.Year_selector_range_cronContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#year_selector_single}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitYear_selector_single(ohminParser.Year_selector_singleContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#year_selector_single_cron}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitYear_selector_single_cron(ohminParser.Year_selector_single_cronContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#year_selector_single_openended}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitYear_selector_single_openended(ohminParser.Year_selector_single_openendedContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#calendarmonth_selector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCalendarmonth_selector(ohminParser.Calendarmonth_selectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#calendarmonth_range}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCalendarmonth_range(ohminParser.Calendarmonth_rangeContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#calendarmonth_range_single}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCalendarmonth_range_single(ohminParser.Calendarmonth_range_singleContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#calendarmonth_range_range}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCalendarmonth_range_range(ohminParser.Calendarmonth_range_rangeContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#calendarmonth_range_cron}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCalendarmonth_range_cron(ohminParser.Calendarmonth_range_cronContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#calendarmonth_range_from_openended}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCalendarmonth_range_from_openended(ohminParser.Calendarmonth_range_from_openendedContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#calendarmonth_range_from_to}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCalendarmonth_range_from_to(ohminParser.Calendarmonth_range_from_toContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#date_offset}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDate_offset(ohminParser.Date_offsetContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#date_from}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDate_from(ohminParser.Date_fromContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#date_to}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDate_to(ohminParser.Date_toContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#moveable_holidays}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMoveable_holidays(ohminParser.Moveable_holidaysContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#week_selector}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWeek_selector(ohminParser.Week_selectorContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#week}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWeek(ohminParser.WeekContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#week_single}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWeek_single(ohminParser.Week_singleContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#week_range}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWeek_range(ohminParser.Week_rangeContext ctx);
	/**
	 * Visit a parse tree produced by {@link ohminParser#week_range_cron}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWeek_range_cron(ohminParser.Week_range_cronContext ctx);
}