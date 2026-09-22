package com.mimamori.reminder

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime

/** 曜日のビット: 日=1, 月=2, 火=4, 水=8, 木=16, 金=32, 土=64 */
object Days {
    const val EVERY = 0b1111111

    /** java.time の DayOfWeek（月=1〜日=7）を、日曜=bit0 のビットに直す */
    fun bitOf(d: DayOfWeek): Int = 1 shl (d.value % 7)

    val labels = listOf("日", "月", "火", "水", "木", "金", "土")

    fun label(mask: Int): String = when {
        mask == EVERY || mask == 0 -> "毎日"
        mask == 0b0111110 -> "平日"
        mask == 0b1000001 -> "土日"
        else -> (0..6).filter { mask and (1 shl it) != 0 }.joinToString("・") { labels[it] }
    }

    fun includes(mask: Int, date: LocalDate): Boolean =
        mask == 0 || (mask and bitOf(date.dayOfWeek)) != 0
}

data class TaskItem(
    val id: Long,
    val title: String,
    val hour: Int,
    val minute: Int,
    val days: Int = Days.EVERY,
    /** 読み上げ文。空なら title から自動で作る */
    val speech: String = "",
    val enabled: Boolean = true,
) {
    val timeLabel: String get() = String.format("%d:%02d", hour, minute)

    /** 実際に読み上げる文章。「読み上げる言葉」が空のときは題名から自動で組み立てる */
    fun speechText(): String {
        val time = if (minute == 0) "${hour}時" else "${hour}時${minute}分"
        return if (speech.isNotBlank()) "${time}です。$speech"
        else "${time}です。${title}の時間です。"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("hour", hour)
        put("minute", minute)
        put("days", days)
        put("speech", speech)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject) = TaskItem(
            id = o.optLong("id"),
            title = o.optString("title"),
            hour = o.optInt("hour").coerceIn(0, 23),
            minute = o.optInt("minute").coerceIn(0, 59),
            days = o.optInt("days", Days.EVERY),
            speech = o.optString("speech", ""),
            enabled = o.optBoolean("enabled", true),
        )

        fun listFromJson(text: String?): List<TaskItem> {
            if (text.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(text)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }

        fun listToJson(list: List<TaskItem>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}

/**
 * 次にそのタスクが来る時刻を求める。曜日指定に対応。
 * 副作用がないので単体テストできる。
 */
object Schedule {
    fun nextTrigger(hour: Int, minute: Int, days: Int, from: ZonedDateTime): ZonedDateTime {
        var c = from.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!c.isAfter(from)) c = c.plusDays(1)
        if (days == 0 || days == Days.EVERY) return c
        var guard = 0
        while (days and Days.bitOf(c.dayOfWeek) == 0 && guard < 8) {
            c = c.plusDays(1)
            guard++
        }
        return c
    }
}
