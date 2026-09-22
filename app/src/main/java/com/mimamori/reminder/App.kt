package com.mimamori.reminder

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class App : Application() {

    companion object {
        const val CH_ALARM = "alarm_v2"
        const val CH_REMIND = "remind_v2"
    }

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        createChannels()
        // アプリが起動しなおされたときに備えて、予約を貼り直しておく
        Scheduler.rescheduleAll(this)
        SheetSync.flushAsync(this)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        // 音は自分で鳴らすので、チャンネル側の音は切っておく（二重に鳴るのを防ぐ）
        val alarm = NotificationChannel(CH_ALARM, "時間のお知らせ", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "設定した時刻に画面いっぱいでお知らせします"
            setSound(null, null)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 700, 400, 700, 400, 700)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val remind = NotificationChannel(CH_REMIND, "やり残しのお知らせ", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "完了ボタンが押されていないときに残る通知です"
            setSound(null, null)
        }

        nm.createNotificationChannel(alarm)
        nm.createNotificationChannel(remind)
    }
}
