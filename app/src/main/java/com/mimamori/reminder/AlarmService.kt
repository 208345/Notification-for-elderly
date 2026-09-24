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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * 時間になったときに動くサービス。
 *
 *  ・「完了」が押されるまで、アラーム音と読み上げをくり返します
 *  ・押されなくても、設定した長さ（既定60秒）で自動的に止まります
 *  ・画面いっぱいのお知らせ（AlarmActivity）を出します
 *  ・止まったあとは「まだ完了していません」通知だけを残します
 */
class AlarmService : Service() {

    companion object {
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SPEECH = "speech"
        const val ACTION_STOP = "com.mimamori.reminder.STOP_SOUND"

        private const val FGS_NOTIF_ID = 1001

        fun remindNotifId(taskId: Long): Int =
            2000 + ((taskId % 1000L).toInt().let { if (it < 0) -it else it })

        @Volatile
        var ringingTaskId: Long = Long.MIN_VALUE
            private set
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    private var currentTask: TaskItem? = null
    private var currentTitle = ""
    private var currentSpeech = ""

    /** 鳴らすのをやめる時刻 */
    private var deadline = 0L
    /** 何回目の鳴動セットか。古いコールバックを無視するために使う */
    private var generation = 0
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finishRinging(keepReminder = true)
            return START_NOT_STICKY
        }

        // 前の鳴動が残っていたら片づける
        generation++
        stopped = false
        stopSound()

        Repo.init(this)
        val taskId = intent?.getLongExtra(EXTRA_TASK_ID, Long.MIN_VALUE) ?: Long.MIN_VALUE
        val task = Repo.byId(taskId)
        currentTask = task
        currentTitle = task?.title ?: intent?.getStringExtra(EXTRA_TITLE) ?: "お知らせ"
        currentSpeech = task?.speechText() ?: intent?.getStringExtra(EXTRA_SPEECH) ?: currentTitle
        ringingTaskId = taskId

        val notif = buildRingingNotification(taskId, currentTitle)
        startForegroundSafely(notif)
        acquireWakeLock()

        // 読み上げエンジンの起動には1〜2秒かかるので、音を鳴らしている間に準備しておく
        Speaker.init(this)

        // スマホを使用中でも確実に全画面で出すため、重ねて表示の権限があれば直接ひらく
        openFullScreenIfPossible(taskId, currentTitle)

        deadline = System.currentTimeMillis() + Repo.soundSeconds.coerceIn(10, 300) * 1000L
        startVibration()
        cycle(generation)

        // 読み上げエンジンが応答しない等で止まらなくなるのを防ぐ最後の保険
        handler.postDelayed({ finishRinging(true) }, (deadline - System.currentTimeMillis()) + 3000L)

        return START_NOT_STICKY
    }

    /* ---------------- 鳴動のくり返し ---------------- */

    private fun cycle(gen: Int) {
        if (stopped || gen != generation) return
        if (System.currentTimeMillis() >= deadline) { finishRinging(true); return }

        playTone {
            if (stopped || gen != generation) return@playTone
            if (System.currentTimeMillis() >= deadline) { finishRinging(true); return@playTone }

            Speaker.speak(this, currentSpeech, times = 1) {
                handler.post {
                    if (stopped || gen != generation) return@post
                    if (System.currentTimeMillis() >= deadline) finishRinging(true)
                    else handler.postDelayed({ cycle(gen) }, 1500)
                }
            }
        }
    }

    /** アラーム音を1回鳴らす。長いアラーム音は2.5秒で切り上げて、すぐ読み上げに移る */
    private fun playTone(onDone: () -> Unit) {
        var fired = false
        val once = {
            if (!fired) { fired = true; onDone() }
        }

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (uri == null) { once(); return }

        runCatching {
            player?.release()
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmService, uri)
                isLooping = false
                setOnCompletionListener { once() }
                setOnErrorListener { _, _, _ -> once(); true }
                prepare()
                start()
            }
            handler.postDelayed({
                runCatching { if (player?.isPlaying == true) player?.stop() }
                once()
            }, 2500)
        }.onFailure { once() }
    }

    private fun startVibration() {
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            // 短く2回 → 少し休む、をくり返す（強すぎない控えめなパターン）
            val pattern = longArrayOf(0, 400, 250, 400, 2200)
            v.vibrate(
                VibrationEffect.createWaveform(pattern, 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            )
            vibrator = v
        }
    }

    /* ---------------- 全画面表示 ---------------- */

    private fun fullScreenIntent(taskId: Long, title: String): Intent =
        Intent(this, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_TITLE, title)
        }

    private fun openFullScreenIfPossible(taskId: Long, title: String) {
        val allowed = runCatching { Settings.canDrawOverlays(this) }.getOrDefault(false)
        if (!allowed) return
        runCatching { startActivity(fullScreenIntent(taskId, title)) }
    }

    /* ---------------- 後始末 ---------------- */

    private fun stopSound() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        Speaker.stop()
    }

    private fun finishRinging(keepReminder: Boolean) {
        if (stopped) return
        stopped = true
        generation++
        handler.removeCallbacksAndMessages(null)
        stopSound()
        runCatching { wakeLock?.release() }
        wakeLock = null
        ringingTaskId = Long.MIN_VALUE

        val taskId = currentTask?.id
        if (keepReminder && taskId != null && !Repo.isDone(taskId, java.time.LocalDate.now())) {
            postReminder(this, taskId, currentTitle)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        stopSound()
        runCatching { wakeLock?.release() }
        ringingTaskId = Long.MIN_VALUE
        super.onDestroy()
    }

    private fun startForegroundSafely(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(FGS_NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(FGS_NOTIF_ID, n)
            }
        } catch (e: Exception) {
            runCatching { getSystemService(NotificationManager::class.java).notify(FGS_NOTIF_ID, n) }
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mimamori:alarm").apply {
                setReferenceCounted(false)
                acquire(5 * 60 * 1000L)
            }
        }
    }

    private fun buildRingingNotification(taskId: Long, title: String): Notification {
        val fullPi = PendingIntent.getActivity(
            this, taskId.toInt(), fullScreenIntent(taskId, title),
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
