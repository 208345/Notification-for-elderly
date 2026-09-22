package com.mimamori.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 「次にいつ鳴るか」の計算だけを取り出したテスト。
 * 端末がなくても ./gradlew test で実行できます。
 */
class ScheduleTest {

    private val tokyo: ZoneId = ZoneId.of("Asia/Tokyo")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, tokyo)

    @Test
    fun 毎日_まだ今日の時刻が来ていなければ今日() {
        val now = at(2026, 9, 23, 7, 0)            // 水曜 7:00
        val next = Schedule.nextTrigger(8, 30, Days.EVERY, now)
        assertEquals(at(2026, 9, 23, 8, 30), next)
    }

    @Test
    fun 毎日_今日の時刻を過ぎていれば明日() {
        val now = at(2026, 9, 23, 9, 0)
        val next = Schedule.nextTrigger(8, 30, Days.EVERY, now)
        assertEquals(at(2026, 9, 24, 8, 30), next)
    }

    @Test
    fun ちょうど同時刻なら次の日に回る() {
        val now = at(2026, 9, 23, 8, 30)
        val next = Schedule.nextTrigger(8, 30, Days.EVERY, now)
        assertEquals(at(2026, 9, 24, 8, 30), next)
    }

    @Test
    fun 曜日指定_月水金のうち次に来る日を選ぶ() {
        // 月=bit1, 水=bit3, 金=bit5
        val mask = (1 shl 1) or (1 shl 3) or (1 shl 5)
        val now = at(2026, 9, 23, 9, 0)           // 水曜の朝、8:30は過ぎている
        val next = Schedule.nextTrigger(8, 30, mask, now)
        assertEquals(DayOfWeek.FRIDAY, next.dayOfWeek)
        assertEquals(at(2026, 9, 25, 8, 30), next)
    }

    @Test
    fun 曜日指定_週をまたいで次の週へ() {
        val mondayOnly = 1 shl 1
        val now = at(2026, 9, 23, 9, 0)           // 水曜
        val next = Schedule.nextTrigger(8, 30, mondayOnly, now)
        assertEquals(DayOfWeek.MONDAY, next.dayOfWeek)
        assertEquals(at(2026, 9, 28, 8, 30), next)
    }

    @Test
    fun 日曜だけの指定も正しく効く() {
        val sundayOnly = 1 shl 0
        val now = at(2026, 9, 23, 9, 0)           // 水曜
        val next = Schedule.nextTrigger(7, 0, sundayOnly, now)
        assertEquals(DayOfWeek.SUNDAY, next.dayOfWeek)
        assertEquals(at(2026, 9, 27, 7, 0), next)
    }

    @Test
    fun 必ず未来の時刻になる() {
        val now = at(2026, 12, 31, 23, 59)
        listOf(Days.EVERY, 1 shl 0, 1 shl 4, 0b0111110).forEach { mask ->
            val next = Schedule.nextTrigger(0, 5, mask, now)
            assertTrue("mask=$mask のとき未来になっていない: $next", next.isAfter(now))
        }
    }

    @Test
    fun 曜日ビットの対応が日曜始まりになっている() {
        assertEquals(1 shl 0, Days.bitOf(DayOfWeek.SUNDAY))
        assertEquals(1 shl 1, Days.bitOf(DayOfWeek.MONDAY))
        assertEquals(1 shl 6, Days.bitOf(DayOfWeek.SATURDAY))
    }

    @Test
    fun 曜日のラベル表示() {
        assertEquals("毎日", Days.label(Days.EVERY))
        assertEquals("平日", Days.label(0b0111110))
        assertEquals("土日", Days.label(0b1000001))
        assertEquals("月・水", Days.label((1 shl 1) or (1 shl 3)))
    }

    @Test
    fun 読み上げ文が組み立てられる() {
        val t = TaskItem(id = 1, title = "あさのおくすり", hour = 8, minute = 30)
        assertEquals("8時30分です。あさのおくすりの時間です。", t.speechText())

        val seiji = TaskItem(id = 2, title = "おひるごはん", hour = 12, minute = 0)
        assertEquals("12時です。おひるごはんの時間です。", seiji.speechText())

        val custom = TaskItem(id = 3, title = "薬", hour = 9, minute = 5, speech = "青い袋のお薬を飲んでください")
        assertEquals("9時5分です。青い袋のお薬を飲んでください", custom.speechText())
    }
}
