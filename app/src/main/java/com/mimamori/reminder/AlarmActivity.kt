package com.mimamori.reminder

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 時間になったときに画面いっぱいで出る画面。ロック中でも表示されます。 */
class AlarmActivity : ComponentActivity() {

    private var taskId: Long = Long.MIN_VALUE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        showOverLockScreen()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        taskId = intent?.getLongExtra(AlarmService.EXTRA_TASK_ID, Long.MIN_VALUE) ?: Long.MIN_VALUE
        val task = Repo.byId(taskId)
        val title = task?.title ?: intent?.getStringExtra(AlarmService.EXTRA_TITLE) ?: "お知らせ"
        val timeText = task?.timeLabel ?: LocalTime.now().format(DateTimeFormatter.ofPattern("H:mm"))
        val isTest = taskId == Scheduler.TEST_TASK_ID

        setContent {
            MimamoriTheme {
                AlarmScreen(
                    title = title,
                    timeText = timeText,
                    isTest = isTest,
                    onDone = {
                        Actions.markDone(this, taskId, true)
                        finishAndRemoveTask()
                    },
                    onLater = {
                        Actions.stopRinging(this)
                        if (!isTest && task != null) postReminder(this, taskId, title)
                        finishAndRemoveTask()
                    },
                )
            }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            runCatching {
                getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)
            }
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ホームボタンなどで閉じられても音は止めない（「完了」か「あとで」を押すまで鳴らす）
}

@Composable
private fun AlarmScreen(
    title: String,
    timeText: String,
    isTest: Boolean,
    onDone: () -> Unit,
    onLater: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val today = remember { LocalDate.now() }
    val dateText = remember(today) {
        "${today.monthValue}月${today.dayOfMonth}日（${Days.labels[today.dayOfWeek.value % 7]}）"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(24.dp, 16.dp)
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(12.dp))
                Text(dateText, fontSize = 22.sp, color = Muted)
                Text(timeText, fontSize = 76.sp, fontWeight = FontWeight.Black, color = GreenDark)
            }

            Text(
                title,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = 56.sp,
                color = Ink,
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = { if (!pressed) { pressed = true; onDone() } },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.White),
                ) {
                    Text(if (isTest) "テスト完了" else "完了", fontSize = 48.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onLater, modifier = Modifier.height(54.dp)) {
                    Text("あとで（音だけ止める）", fontSize = 20.sp, color = Muted)
                }
            }
        }
    }
}
