package com.mimamori.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.time.ZoneId
import java.time.ZonedDateTime

object Scheduler {

    const val TEST_TASK_ID = -100L
    private const val SUMMARY_REQ = 999001
    private const val TEST_REQ = 999002

    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am(ctx).canScheduleExactAlarms() else true

    private fun reqCode(taskId: Long): Int = (taskId % 100000L).toInt().let { if (it < 0) -it else it } + 1000

    private fun alarmIntent(ctx: Context, taskId: Long): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).apply {
            action = "com.mimamori.reminder.FIRE"
            data = android.net.Uri.parse("mimamori://task/$taskId")
            putExtra(AlarmService.EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            ctx, reqCode(taskId), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** 1件ぶんのアラームを次の発火時刻に予約する */
    fun schedule(ctx: Context, task: TaskItem) {
        if (!task.enabled) { cancel(ctx, task.id); return }
        val next = Schedule.nextTrigger(task.hour, task.minute, task.days, ZonedDateTime.now(ZoneId.systemDefault()))
        setAt(ctx, next.toInstant().toEpochMilli(), alarmIntent(ctx, task.id))
        Log.i("Mimamori", "予約: ${task.title} -> $next")
    }

    fun cancel(ctx: Context, taskId: Long) {
        am(ctx).cancel(alarmIntent(ctx, taskId))
    }

    fun rescheduleAll(ctx: Context) {
        Repo.tasks.value.forEach { schedule(ctx, it) }
        scheduleDailySummary(ctx)
    }

    /** 毎日23:50に、その日の実施状況をまとめてスプレッドシートへ送る */
    fun scheduleDailySummary(ctx: Context) {
        val i = Intent(ctx, AlarmReceiver::class.java).apply {
            action = "com.mimamori.reminder.SUMMARY"
            data = android.net.Uri.parse("mimamori://summary")
        }
        val pi = PendingIntent.getBroadcast(ctx, SUMMARY_REQ, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val next = Schedule.nextTrigger(23, 50, Days.EVERY, ZonedDateTime.now(ZoneId.systemDefault()))
        setAt(ctx, next.toInstant().toEpochMilli(), pi)
    }

    /** 設定画面の「テスト」ボタン用。指定秒後に本番とまったく同じ流れで鳴らす */
    fun scheduleTest(ctx: Context, afterSeconds: Int) {
        val i = Intent(ctx, AlarmReceiver::class.java).apply {
            action = "com.mimamori.reminder.FIRE"
            data = android.net.Uri.parse("mimamori://test")
            putExtra(AlarmService.EXTRA_TASK_ID, TEST_TASK_ID)
            putExtra(AlarmService.EXTRA_TITLE, "テストのお知らせ")
            putExtra(AlarmService.EXTRA_SPEECH, "これはテストです。うまく聞こえていれば設定は完了です")
        }
        val pi = PendingIntent.getBroadcast(ctx, TEST_REQ, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        setAt(ctx, System.currentTimeMillis() + afterSeconds * 1000L, pi)
    }

    private fun setAt(ctx: Context, atMillis: Long, pi: PendingIntent) {
        val manager = am(ctx)
        try {
            if (canScheduleExact(ctx)) {
                // setAlarmClock は端末が省電力に入っていても確実に鳴る、いちばん強い予約方法
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, showIntent(ctx)), pi)
            } else {
                // 「正確なアラーム」が許可されていない場合の保険（数分ずれる可能性あり）
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }
        } catch (e: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }
}
