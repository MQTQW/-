package com.example.myplan.quickadd

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class QuickAddParserTest {
    private val now = ZonedDateTime.of(2026, 10, 19, 15, 30, 20, 0, ZoneId.of("Asia/Shanghai"))
    private fun parse(s: String) = QuickAddParser.parse(s, now)
    private fun at(s: String) = LocalDateTime.parse(s).atZone(now.zone).toInstant().toEpochMilli()

    @Test fun chineseDateAndEveningCreateOneTime() {
        val d = parse("明天晚上六点取快递")
        assertEquals("取快递", d.title); assertEquals("ONCE", d.repeatMode)
        assertEquals(at("2026-10-20T18:00"), d.at); assertNull(d.issue)
    }
    @Test fun relativeMinutes() { assertEquals(at("2026-10-19T15:40:20"), parse("十分钟后喝水").at) }
    @Test fun halfHour() { assertEquals(at("2026-10-19T16:00:20"), parse("半小时后出发").at) }
    @Test fun relativeHours() { assertEquals(at("2026-10-19T17:30:20"), parse("两小时后收衣服").at) }
    @Test fun fullDateAndColon() { assertEquals(at("2026-10-22T09:05"), parse("2026-10-22 09:05 复诊").at) }
    @Test fun monthDate() { assertEquals(at("2026-10-23T16:20"), parse("10月23日下午四点二十分提交资料").at) }
    @Test fun nextWeek() { assertEquals(at("2026-10-27T18:00"), parse("下周二晚上六点跑步").at) }
    @Test fun tomorrowMorning() { assertEquals(at("2026-10-20T08:30"), parse("明早八点半买早餐").at) }
    @Test fun tomorrowEvening() { assertEquals(at("2026-10-20T19:00"), parse("明晚七点吃饭").at) }
    @Test fun reminderPrefixRemoved() { assertEquals("交材料", parse("提醒我明天15:30交材料").title) }
    @Test fun dailyIsExplicit() { val d = parse("每天晚上九点读书"); assertEquals("EVERY_DAY", d.repeatMode); assertEquals(21, d.hour); assertNull(d.issue); assertNull(d.at) }
    @Test fun weekdays() { assertEquals("WEEKDAYS", parse("工作日早上八点开会").repeatMode) }
    @Test fun weeklyRangeKeepsCleanTitle() { val d = parse("每周一至周五早上八点开会"); assertEquals("WEEKDAYS", d.repeatMode); assertEquals("开会", d.title); assertNull(d.issue) }
    @Test fun twentyFourHourWithPeriod() { assertEquals(at("2026-10-20T18:00"), parse("明天晚上18:00取件").at) }
    @Test fun incompleteWeekdayNeedsChoice() { assertNotNull(parse("每周18:00取件").issue) }
    @Test fun malformedLongMinuteNeedsChoice() { assertNotNull(parse("明天18:000取件").issue) }
    @Test fun customWeekdays() { val d = parse("每周一、三、五晚上七点锻炼"); assertEquals("1,3,5", d.customDays); assertEquals("CUSTOM", d.repeatMode); assertNull(d.issue) }
    @Test fun bareTimeDoesNotGuessMorningOrEvening() { val d = parse("明天六点买菜"); assertNull(d.at); assertNotNull(d.issue); assertEquals(2, d.alternatives.size) }
    @Test fun pastTimeDoesNotRollToTomorrow() { val d = parse("今天09:00取快递"); assertNull(d.at); assertTrue(d.issue!!.contains("已经过去")) }
    @Test fun missingTimeKeepsExplicitDate() { val d = parse("后天取快递"); assertEquals("2026-10-21", d.date); assertNull(d.at); assertNotNull(d.issue) }
    @Test fun emptyTitleDoesNotCreate() { assertNotNull(parse("明天18:00").issue) }
    @Test fun invalidHourDoesNotNormalize() { assertNotNull(parse("明天25:00取快递").issue) }
    @Test fun invalidMinuteDoesNotNormalize() { assertNotNull(parse("明天18:90取快递").issue) }
    @Test fun invalidDateDoesNotNormalize() { assertNotNull(parse("2026-02-30 18:00取快递").issue) }
    @Test fun unsupportedMonthlyDoesNotBecomeOneTime() { assertNotNull(parse("每月20日18:00还款").issue) }
    @Test fun weekendDoesNotBecomeToday() { assertNotNull(parse("周末18:00跑步").issue) }
    @Test fun recurringStartDateRequiresConfirmation() { assertNotNull(parse("明天开始每天18:00运动").issue) }
    @Test fun multipleTimesRequireConfirmation() { assertNotNull(parse("明天09:00和18:00喝水").issue) }
    @Test fun midnightEveningIsAmbiguous() { assertNotNull(parse("今晚十二点睡觉").issue) }
    @Test fun daylightSavingGapAndOverlapRequireConfirmation() {
        val zone = ZoneId.of("America/New_York")
        assertNull(QuickAddParser.resolve(LocalDate.of(2026, 3, 8), LocalTime.of(2, 30), zone))
        assertNull(QuickAddParser.resolve(LocalDate.of(2026, 11, 1), LocalTime.of(1, 30), zone))
    }
}
