package com.mimamori.reminder

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.time.LocalDate

/** 完了処理をひとまとめにしたもの。どの画面・通知から押されても同じ動きになります。 */
object Actions {

    fun markDone(ctx: Context, taskId: Long, done: Boolean = true, date: LocalDate = LocalDate.now()) {
        Repo.init(ctx)
        if (taskId != Scheduler.TEST_TASK_ID) {
            Repo.setDone(taskId, date, done)
        }
        stopRinging(ctx)
        clearNotifications(ctx, taskId)
        val task = Repo.byId(taskId)
        if (task != null) {
            SheetSync.reportAsync(ctx, task, date, if (done) "done" else "undone")
        }
    }

    fun stopRinging(ctx: Context) {
        // 鳴っていないときに startService するとバックグラウンド制限に触れるので、鳴っているときだけ止める
        if (AlarmService.ringingTaskId == Long.MIN_VALUE) return
        runCatching {
            ctx.startService(Intent(ctx, AlarmService::class.java).setAction(AlarmService.ACTION_STOP))
        }
    }

    fun clearNotifications(ctx: Context, taskId: Long) {
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.cancel(AlarmService.remindNotifId(taskId))
            nm.cancel(1001)
        }
    }
}

/** 通知の「完了」ボタンから呼ばれる */
class ActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DONE = "com.mimamori.reminder.DONE"

        fun donePendingIntent(ctx: Context, taskId: Long): PendingIntent {
            val i = Intent(ctx, ActionReceiver::class.java).apply {
                action = ACTION_DONE
                data = android.net.Uri.parse("mimamori://done/$taskId")
                putExtra(AlarmService.EXTRA_TASK_ID, taskId)
            }
            return PendingIntent.getBroadcast(
                ctx, taskId.toInt() + 31, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_DONE) return
        val taskId = intent.getLongExtra(AlarmService.EXTRA_TASK_ID, Long.MIN_VALUE)
        Actions.markDone(ctx, taskId, true)
    }
}

/** 予約した時刻に system から呼ばれる */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        Repo.init(ctx)

        if (intent.action == "com.mimamori.reminder.SUMMARY") {
            SheetSync.sendDailySummaryAsync(ctx)
            Scheduler.scheduleDailySummary(ctx)
            return
        }

        val taskId = intent.getLongExtra(AlarmService.EXTRA_TASK_ID, Long.MIN_VALUE)

        // まず次回ぶんを予約しなおす（ここで失敗しても以降の処理は続ける）
        Repo.byId(taskId)?.let { runCatching { Scheduler.schedule(ctx, it) } }

        val svc = Intent(ctx, AlarmService::class.java).apply {
            putExtra(AlarmService.EXTRA_TASK_ID, taskId)
            putExtra(AlarmService.EXTRA_TITLE, intent.getStringExtra(AlarmService.EXTRA_TITLE))
            putExtra(AlarmService.EXTRA_SPEECH, intent.getStringExtra(AlarmService.EXTRA_SPEECH))
        }
        runCatching { ContextCompat.startForegroundService(ctx, svc) }
            .onFailure {
                // 前面サービスを起動できない状況でも、通知だけは必ず出す
                val title = Repo.byId(taskId)?.title ?: intent.getStringExtra(AlarmService.EXTRA_TITLE) ?: "お知らせ"
                postReminder(ctx, taskId, title)
            }
    }
}

/** 再起動・アプリ更新・時刻変更のあとに、予約を貼り直す */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Repo.init(ctx)
        Scheduler.rescheduleAll(ctx)
        SheetSync.flushAsync(ctx)
    }
}
