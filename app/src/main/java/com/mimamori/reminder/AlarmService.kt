package com.mimamori.reminder

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * 時間になったときに動く短時間のサービス。
 *  1. アラーム音を1回鳴らす
 *  2. タスク内容を音声で読み上げる（2回）
 *  3. 画面全体の通知（フルスクリーンインテント）で AlarmActivity を開く
 *  4. 鳴り終わったら「まだ完了していません」通知だけ残して自分は止まる
 */
class AlarmService : Service() {

    companion object {
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SPEECH = "speech"
        const val ACTION_STOP = "com.mimamori.reminder.STOP_SOUND"

        private const val FGS_NOTIF_ID = 1001
        /** 未完了として残す通知のID（タスクごとに変える） */
        fun remindNotifId(taskId: Long): Int = 2000 + ((taskId % 1000L).toInt().let { if (it < 0) -it else it })

        @Volatile
        var ringingTaskId: Long = Long.MIN_VALUE
            private set
    }

    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null
    private var currentTask: TaskItem? = null
    private var currentTitle = ""
    private var currentSpeech = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finishRinging(keepReminder = true)
            return START_NOT_STICKY
        }

        // 前のアラームがまだ残っていた場合に備えて、いったん片づける
        runCatching { player?.stop(); player?.release() }
        player = null
        spoken = false

        val taskId = intent?.getLongExtra(EXTRA_TASK_ID, Long.MIN_VALUE) ?: Long.MIN_VALUE
        Repo.init(this)
        val task = Repo.byId(taskId)
        currentTask = task
        currentTitle = task?.title ?: intent?.getStringExtra(EXTRA_TITLE) ?: "お知らせ"
        currentSpeech = task?.speechText() ?: intent?.getStringExtra(EXTRA_SPEECH) ?: currentTitle
        ringingTaskId = taskId

        startForegroundSafely(buildRingingNotification(taskId, currentTitle))
        acquireWakeLock()
        startSound()

        // 最大でも設定秒数で必ず止める
        val limit = (Repo.soundSeconds.coerceAtLeast(20)) * 1000L
        timeout?.let { handler.removeCallbacks(it) }
        timeout = Runnable { finishRinging(keepReminder = true) }
        handler.postDelayed(timeout!!, limit)

        return START_NOT_STICKY
    }

    private fun startForegroundSafely(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(FGS_NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(FGS_NOTIF_ID, n)
            }
        } catch (e: Exception) {
            // 前面サービスにできなくても、通知だけは出しておく
            getSystemService(NotificationManager::class.java).notify(FGS_NOTIF_ID, n)
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mimamori:alarm").apply {
                setReferenceCounted(false)
                acquire(3 * 60 * 1000L)
            }
        }
    }

    private fun startSound() {
        val seconds = Repo.soundSeconds
        if (seconds <= 0) { speakNow(); return }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (uri == null) { speakNow(); return }
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmService, uri)
                isLooping = false
                setOnCompletionListener { speakNow() }
                setOnErrorListener { _, _, _ -> speakNow(); true }
                prepare()
                start()
            }
            // 音が長すぎる着信音でも、8秒で切り上げて読み上げに移る
            handler.postDelayed({
                if (player?.isPlaying == true) { runCatching { player?.stop() }; speakNow() }
            }, 8000)
        }.onFailure { speakNow() }
    }

    private var spoken = false
    private fun speakNow() {
        if (spoken) return
        spoken = true
        Speaker.speak(this, currentSpeech, times = 2) {
            handler.post { finishRinging(keepReminder = true) }
        }
    }

    /** 鳴り終わり。完了していなければ「まだです」通知を残す */
    private fun finishRinging(keepReminder: Boolean) {
        timeout?.let { handler.removeCallbacks(it) }
        Speaker.stop()
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { wakeLock?.release() }
        wakeLock = null
        ringingTaskId = Long.MIN_VALUE

        val taskId = currentTask?.id
        if (keepReminder && taskId != null && !Repo.isDone(taskId, java.time.LocalDate.now())) {
            postReminder(this, taskId, currentTitle)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        timeout?.let { handler.removeCallbacks(it) }
        Speaker.stop()
        runCatching { player?.release() }
        runCatching { wakeLock?.release() }
        ringingTaskId = Long.MIN_VALUE
        super.onDestroy()
    }

    // ---------------- 通知の組み立て ----------------

    private fun buildRingingNotification(taskId: Long, title: String): Notification {
        val full = Intent(this, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_TITLE, title)
        }
        val fullPi = PendingIntent.getActivity(
            this, taskId.toInt(), full,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, App.CH_ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText("時間です。タップして「完了」を押してください")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullPi, true)
            .setContentIntent(fullPi)
            .addAction(0, "完了", ActionReceiver.donePendingIntent(this, taskId))
            .build()
    }
}

/** 鳴り終わったあとも残る「まだ完了していません」通知 */
fun postReminder(ctx: Context, taskId: Long, title: String) {
    val open = Intent(ctx, AlarmActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        putExtra(AlarmService.EXTRA_TASK_ID, taskId)
        putExtra(AlarmService.EXTRA_TITLE, title)
    }
    val pi = PendingIntent.getActivity(
        ctx, taskId.toInt() + 7, open,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val n = NotificationCompat.Builder(ctx, App.CH_REMIND)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText("まだ「完了」が押されていません")
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setAutoCancel(true)
        .setContentIntent(pi)
        .addAction(0, "完了", ActionReceiver.donePendingIntent(ctx, taskId))
        .build()
    runCatching {
        ctx.getSystemService(NotificationManager::class.java)
            .notify(AlarmService.remindNotifId(taskId), n)
    }
}
