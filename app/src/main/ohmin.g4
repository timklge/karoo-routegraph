grammar ohmin;

// Basic elements
// Trivial stuff, prefixed with 'c' and should never change during grammar development
// Note: case SENSITIVE
// Note: order is important (hold "Ctrl" to inspect how input steam was tolenized in IDEA)

c247string     : '24/7';

//HH_MM            : NUMBERS ':' NUMBERS;
//TIMERANGE        : HH_MM '-' HH_MM;

//TODO: not sure how to include negative integer separately or why it is impossible
//NEGATIVE_INTEGER : '-' (('1'..'9') | ('1'..'9')('0'..'9')+);
NUMBERS : '0' | '00' | '0'? ('1'..'9') | ('1'..'9')('0'..'9')+;

/* How to make them live together with rules above?
YEAR4LETTER      : ('19' | '20') '0'..'9' '0'..'9';
MINUTE           : '0'? '1'..'9' | ('1'..'5' '0'..'9') | '60';
HOUR             : '0'? '1'..'9' | ('1'..'2' '0'..'9') | '21' | '22' | '23' | '24';
ZERO             : '0';
*/

//chour          : DIGIT | '10' | '11' | '12' | '13' | '14' | '15' | '16' | '17' | '18' | '19' | '20' | '21' | '22' | '23' | '24';

/* daynum */
//cday           : FIRSTTENWITHLEADINGZEROS | FIRSTTENWITHOUTLEADINGZEROS | '10' | '11' | '12' | '13' | '14' | '15' | '16' | '17' | '18' | '19' | '20' | '21' | '22' | '23' | '24' | '25' | '26' | '27' | '28' | '29' | '30' | '31';

/* wday */
cdayoftheweek     : cworkdays | cweekend; // Unnecessary overcomplication, mainly for richer stats

cworkdays         : cworkdays2letters | cworkdays3letters;
cworkdays2letters : 'Mo'  | 'Tu'  | 'We'  | 'Th'  | 'Fr';
cworkdays3letters : 'Mon' | 'Tue' | 'Wed' | 'Thu' | 'Fri';

cweekend         : cweekend2letters | cweekend3letters;
cweekend2letters : 'Sa'  | 'Su';
cweekend3letters : 'Sat' | 'Sun';

//cweeknum       : FIRSTTENWITHLEADINGZEROS | FIRSTTENWITHOUTLEADINGZEROS | '10' | '11' | '12' | '13' | '14' | '15' | '16' | '17' | '18' | '19' | '20' | '21' | '22' | '23' | '24' | '25' | '26' | '27' | '28' | '29' | '30' | '31' | '32' | '33' | '34' | '35' | '36' | '37' | '38' | '39' | '40' | '41' | '42' | '43' | '44' | '45' | '46' | '47' | '48' | '49' | '50' | '51' | '52' | '53';

// Is it possible to allow numeric monthss?
cmonth         : 'Jan' | 'Feb' | 'Mar' | 'Apr' | 'May' | 'Jun' | 'Jul' | 'Aug' | 'Sep' | 'Oct' | 'Nov' | 'Dec';

/* event */
// TODO: review moveable_holidays
// TODO: review variable_time
csunlightevent : 'dawn' | 'sunrise' | 'sunset' | 'dusk';

/* extended hour */
// TODO test against values in database, adjust
//cwrappinghour  : '25' | '26' | '27' | '28' | '29' | '30' | '31' | '32' | '33' | '34' | '35' | '36' | '37' | '38' | '39' | '40' | '41' | '42' | '43' | '44' | '45' | '46' | '47' | '48';

/* plus_or_minus */
coffsetsymbols: '+' | '-';

//TODO: refactor everything above first, have fun
cminute        : NUMBERS;
chour          : NUMBERS;
cday           : NUMBERS;
cweeknum       : NUMBERS;
cwrappinghour  : NUMBERS;

//
// Non-trivial rules below
//
positive_integer : NUMBERS;
negative_integer : '-' positive_integer; //TODO: see above about NEGATIVE_INTEGER

//integer          : negative_integer | positive_integer;

hh_mm         : chour ':' cminute;

/* extended_hour_minutes */
//wrapping_hh_mm : cwrappinghour ':' cminute;


COMMENT             : '"' ~('"')+? '"';
comment             :
                      COMMENT #Nonemptycomment
                    | '""'    #Emptycomment
                    ;

nth_entry           : negative_integer | positive_integer | positive_integer'-'positive_integer;

WS : [' ' | '\t' | '\n']+ -> channel(HIDDEN);

//
// TOP-level rule and actual grammar below
//
// https://github.com/antlr/antlr4/blob/master/doc/
// https://github.com/antlr/antlr4/blob/master/doc/parser-rules.md
//
// status: WIP
// week 4-16 We 00:00-24:00; week 38-42 Sa 00:00-24:00; PH off
// week 2-52/2 We 00:00-24:00; week 1-53/2 Sa 00:00-24:00; PH off
// Jan 23-Feb 11 00:00-24:00; PH off
// Mo-Fr 08:00-12:00, We 14:00-18:00; Su,PH off
// 2012 easter -2 days-2012 easter +2 days: open "Around easter"; PH off
// Mo-Fr 12:00-21:00/03:00
// 2013,2015,2050-2053,2055/2,2020-2029/3,2060+ Jan 1
// 2013,2015,2050-2053,2055/2,2020-2029/3,2060+ Jan 1-14
//
// TODO: incorrectly processed input below
// Jan 23-Feb 11,Feb 12 00:00-24:00; PH off
//
// Anything else?


opening_hours : rule_sequence (rule_separator rule_sequence)* EOF;
rule_sequence :
                selector_sequence /* If no rule_modifier is specified, then the rule_sequence is interpreted as open. */
//              | rule_modifier // TODO: is this possible?
              | selector_sequence rule_modifier;

    rule_separator :
                     ';'    #rule_separator_normal
                   | ' || ' #rule_separator_fallback;

    //
    // Rule modifiers
    // status: untested
    rule_modifier             :
                                rule_modifier_open
                              | rule_modifier_closed
                              | rule_modifier_unknown
                              | rule_modifier_comment
                              ;
    rule_modifier_open        : ('open') (comment)?;              // TODO 'opened'
    rule_modifier_closed      : ('closed' | 'off') (comment)?;    // TODO 'closed'
    rule_modifier_unknown     : ('unknown') (comment)?;           // TODO 'unknown'
    rule_modifier_comment     : comment;                              // TODO defaults to 'unknown'


//
// Selectors
// status: untested
selector_sequence     :
                        c247string
                      | small_range_selectors
                      | wide_range_selectors
                      | wide_range_selectors small_range_selectors;
small_range_selectors :
                        weekday_selector
                      | weekday_selector time_selector  (',' weekday_selector time_selector)? // spec says both are required (only this case), but this is not true in practice
                      | time_selector
                      ;

    //
    // Weekday selector (Red)
    // status: untested
    weekday_selector     :
                           weekday_sequence
                         | holiday_sequence
                         | holiday_sequence ',' weekday_sequence
                         | weekday_sequence ',' holiday_sequence
                         ; // any semantic difference between ', ' and ' '?
    weekday_sequence     : weekday_ranges (',' weekday_ranges)*;

    //TODO: refactor "weekday_ranges" names
    weekday_ranges        : weekday_ranges_single | weekday_ranges_range | weekday_ranges_range_nth | weekday_ranges_range_nth_offset;
    weekday_ranges_single : cdayoftheweek;
    weekday_ranges_range  : cdayoftheweek '-' cdayoftheweek;

    /**
     * Su represents all Sundays,
     * Su[1]  - first Sunday of a month,
     * Su[-1] -  last Sunday of a month.
     */
    weekday_ranges_range_nth        : cdayoftheweek '[' nth_entry (',' nth_entry)* ']';
    weekday_ranges_range_nth_offset : cdayoftheweek '[' nth_entry (',' nth_entry)* ']' day_offset;

    holiday_sequence     : holiday (',' holiday)*;
    holiday              :                                          // https://github.com/opening-hours/opening_hours.js#holidays
                           'PH' (day_offset)? #singular_day_holiday // Only a day shift around one day (± 1 day) is currently defined.
                         | 'SH'               #plural_day_holiday
                         ;

    day_offset           : (( '+' positive_integer) | (negative_integer)) ('day' 's'?);


    //
    // Time selector (Blue)
    // status: untested
    time_selector            : timespan (',' timespan)*;

    timespan                 :
//                             timespan_simple | // This is only valid in point in time mode (tags like collection_times=*)
                             timespan_openended
                             | timespan_range
                             | timespan_range_openended
                             | timespan_range_cron
//                             | timespan_case_everyNminutes
//                             | timespan_case_everyPeriod
                             ;
//    timespan_simple          : time;

    // Ambiguities in '-12' as number or '-'hour
    timespan_range           : /*TIMERANGE*/ time '-' time; //wrapping_hh_mm
    timespan_range_openended : /*TIMERANGE*/ timespan_range '+';
    timespan_range_cron      : /*TIMERANGE*/ time '-' time '/' time; //wrapping_hh_mm

    timespan_openended       : time '+';
    /**
    This notation describes a repeated event:

    10:00-16:00/90 and 10:00-16:00/1:30 are evaluated as "from ten am to four pm every 1½ hours".
    Especially departure times can be written very concise and compact using this notation.
    The interval time following the "/" is valid but ignored for opening_hours.

    This is only valid in point in time mode (tags like collection_times=*.
    */
//    timespan_case_everyNminutes : time '-' (time | wrapping_hh_mm) '/' cminute;
//    timespan_case_everyPeriod   : time '-' (time | wrapping_hh_mm) '/' hh_mm;

    time                     : hh_mm | variable_time;
//    extended_time            : time | wrapping_hh_mm;
    variable_time            : csunlightevent | csunlightevent coffsetsymbols hh_mm;

wide_range_selectors  :
    ( //TODO: review combinations below
      year_sel
    | year_sel date_from
    | year_sel calendarmonth_range //
    | calendarmonth_selector
    | week_selector
    | year_sel calendarmonth_selector
    | year_sel calendarmonth_selector week_selector
    | year_sel week_selector
    | calendarmonth_selector week_selector
    ) ':'? |
    comment (':')?
    ;

    //
    // Year selector (Orange)
    // status: untested
    //TODO: ideally, we should have rules for "single year" and "multiple years". Out of ideas how to refactor all rules below.
    year_sel                       : year_selector (',' year_selector)*;
    year_selector                  :
                                     year_selector_single
                                   | year_selector_single_cron
                                   | year_selector_range
                                   | year_selector_single_openended
                                   | year_selector_range_cron
                                   ;
    year_selector_range            : year_selector_single '-' year_selector_single;
    year_selector_range_cron       : year_selector_range '/' positive_integer;

    /* year */
    year_selector_single           : NUMBERS; // YEAR4LETTER;
    year_selector_single_cron      : year_selector_single '/' positive_integer;
    year_selector_single_openended : year_selector_single '+';

    //
    // Month selector (Green)
    // status: untested
    calendarmonth_selector        : calendarmonth_range (',' calendarmonth_range)*;

    calendarmonth_range           :
                                    calendarmonth_range_single
                                  | calendarmonth_range_range
                                  | calendarmonth_range_cron
//                                  | calendarmonth_range_from
                                  | calendarmonth_range_from_openended
                                  | calendarmonth_range_from_to
                                  ;
    //year_selector_single?
    calendarmonth_range_single         : cmonth;
    calendarmonth_range_range          : cmonth '-' cmonth;
    calendarmonth_range_cron           : cmonth '-' cmonth  '/' positive_integer;
//    calendarmonth_range_from           : date_from;
    calendarmonth_range_from_openended : date_from date_offset? '+';
    calendarmonth_range_from_to        : date_from date_offset? '-' date_to date_offset?;


						  /**
                            *  Given any calendar day:
                            * +Su - selects the first Sunday after this calendar day
                            * -Su - selects the last Sunday before this calendar day.
                           */
    date_offset          :
                           coffsetsymbols cdayoftheweek
                         | day_offset
                         ;

    date_from            :
                           year_selector_single? cmonth cday
                         | year_selector_single? moveable_holidays
                         ;
    date_to              : date_from | cday;

    /* See https://en.wikipedia.org/wiki/Category:Moveable_holidays */
    /* variable_date */
    moveable_holidays    : 'easter';

    //
    // Calendar week selector (Yellow)
    // status: untested
    week_selector        : 'week ' week (',' week)*;
    week                 : week_single | week_range | week_range_cron;
    week_single          : cweeknum;
    week_range           : cweeknum '-' cweeknum;
    week_range_cron      : cweeknum '-' cweeknum '/' positive_integer;
    
