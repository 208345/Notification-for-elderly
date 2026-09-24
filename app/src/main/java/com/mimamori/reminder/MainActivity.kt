package com.mimamori.reminder

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        Speaker.init(this)
        setContent { MimamoriTheme { AppRoot() } }
    }

    override fun onResume() {
        super.onResume()
        Scheduler.rescheduleAll(this)
        SheetSync.flushAsync(this)
    }
}

/* ============================================================
 *  画面の切り替え
 * ========================================================== */

@Composable
private fun AppRoot() {
    var tab by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current

    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Surface(Modifier.fillMaxSize(), color = Paper) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> TodayScreen()
                    1 -> TasksScreen()
                    else -> SettingsScreen()
                }
            }
            BottomTabs(tab) { tab = it }
        }
    }
}

/** 画面下のタブ。■◎▲（ナビゲーションバー）に重ならないよう余白を自動で確保する */
@Composable
private fun BottomTabs(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("今日", "よてい", "設定")
    Column(Modifier.fillMaxWidth().background(Color.White)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            labels.forEachIndexed { i, label ->
                val on = i == selected
                Box(
                    Modifier
                        .weight(1f)
                        .height(66.dp)
                        .background(if (on) Green else Color.White)
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        fontSize = 21.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) Color.White else Muted,
                    )
                }
            }
        }
    }
}

/** 画面上の見出し。時刻表示やカメラ穴に重ならないよう余白を自動で確保する */
@Composable
private fun Header(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Green)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(20.dp, 16.dp)
    ) { content() }
}

/* ============================================================
 *  今日の画面
 * ========================================================== */

@Composable
private fun TodayScreen() {
    val ctx = LocalContext.current
    val tasks by Repo.tasks.collectAsState()
    val done by Repo.done.collectAsState()
    val today = remember { LocalDate.now() }
    val list = remember(tasks, today) { Repo.tasksOn(today) }
    val doneCount = list.count { done.containsKey(Repo.doneKey(it.id, today)) }

    Column(Modifier.fillMaxSize()) {
        Header {
            Text(
                "${today.monthValue}月${today.dayOfMonth}日（${Days.labels[today.dayOfWeek.value % 7]}）",
                fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White,
            )
            Text(
                if (list.isEmpty()) "今日の予定はありません"
                else "ぜんぶで ${list.size} 件 ／ 終わったのは ${doneCount} 件",
                fontSize = 18.sp, color = Color(0xFFD7EBD9),
            )
        }

        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "「よてい」から予定を\n追加してください",
                    fontSize = 24.sp, color = Muted, textAlign = TextAlign.Center, lineHeight = 36.sp,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp)) {
                items(list, key = { it.id }) { task ->
                    val isDone = done.containsKey(Repo.doneKey(task.id, today))
                    TodayRow(task, isDone) {
                        if (isDone) Repo.setDone(task.id, today, false)
                        else Actions.markDone(ctx, task.id, true, today)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun TodayRow(task: TaskItem, isDone: Boolean, onToggle: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (isDone) Color(0xFFEDF5EE) else Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDone) 0.dp else 2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp, 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    task.timeLabel,
                    fontSize = 34.sp, fontWeight = FontWeight.Black,
                    color = if (isDone) Muted else GreenDark,
                )
                Text(
                    task.title,
                    fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    color = if (isDone) Muted else Ink, lineHeight = 32.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (isDone) {
                OutlinedButton(
                    onClick = onToggle,
                    modifier = Modifier.height(72.dp).width(110.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("済", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Green) }
            } else {
                Button(
                    onClick = onToggle,
                    modifier = Modifier.height(72.dp).width(110.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                ) { Text("完了", fontSize = 26.sp, fontWeight = FontWeight.Black) }
            }
        }
    }
}

/* ============================================================
 *  よてい
 * ========================================================== */

@Composable
private fun TasksScreen() {
    val ctx = LocalContext.current
    val tasks by Repo.tasks.collectAsState()
    var editing by remember { mutableStateOf<TaskItem?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Header {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("よてい", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White, modifier = Modifier.weight(1f))
                Button(
                    onClick = {
                        editing = TaskItem(id = Repo.newId(), title = "", hour = 8, minute = 0)
                        showEditor = true
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = GreenDark),
                ) { Text("＋ 追加", fontSize = 19.sp, fontWeight = FontWeight.Bold) }
            }
        }

        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("「＋ 追加」から\n予定をつくってください", fontSize = 22.sp, color = Muted, textAlign = TextAlign.Center, lineHeight = 34.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp)) {
                items(tasks, key = { it.id }) { t ->
                    Card(
                        Modifier.fillMaxWidth().clickable { editing = t; showEditor = true },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                    ) {
                        Row(Modifier.padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { TaskSummary(t) }
                            Switch(
                                checked = t.enabled,
                                onCheckedChange = { on ->
                                    val u = t.copy(enabled = on)
                                    Repo.upsert(u)
                                    Scheduler.schedule(ctx, u)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }

    if (showEditor) {
        val t = editing
        if (t != null) {
            TaskEditor(
                initial = t,
                isNew = tasks.none { it.id == t.id },
                onSave = { saved ->
                    Repo.upsert(saved)
                    Scheduler.schedule(ctx, saved)
                    showEditor = false
                },
                onDelete = {
                    Scheduler.cancel(ctx, t.id)
                    Repo.delete(t.id)
                    showEditor = false
                },
                onCancel = { showEditor = false },
            )
        }
    }
}

@Composable
private fun TaskSummary(task: TaskItem) {
    Text(
        task.timeLabel,
        fontSize = 28.sp, fontWeight = FontWeight.Black,
        color = if (task.enabled) GreenDark else Muted,
    )
    Text(
        task.title.ifBlank { "（名前なし）" },
        fontSize = 21.sp, fontWeight = FontWeight.Bold,
        color = if (task.enabled) Ink else Muted,
    )
    Text(Days.label(task.days), fontSize = 16.sp, color = Muted)
}

@Composable
private fun TaskEditor(
    initial: TaskItem,
    isNew: Boolean,
    onSave: (TaskItem) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var title by remember(initial.id) { mutableStateOf(initial.title) }
    var hour by remember(initial.id) { mutableIntStateOf(initial.hour) }
    var minute by remember(initial.id) { mutableIntStateOf(initial.minute) }
    var days by remember(initial.id) { mutableIntStateOf(initial.days) }
    var speech by remember(initial.id) { mutableStateOf(initial.speech) }
    var pickTime by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    initial.copy(
                        title = title.trim().ifBlank { "予定" },
                        hour = hour,
                        minute = minute,
                        days = if (days == 0) Days.EVERY else days,
                        speech = speech.trim(),
                    )
                )
            }) { Text("保存", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text("削除", fontSize = 20.sp, color = Red) }
                TextButton(onClick = onCancel) { Text("やめる", fontSize = 20.sp) }
            }
        },
        title = { Text(if (isNew) "予定をつくる" else "予定をなおす", fontSize = 24.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("なにをする？（例：あさの おくすり）", fontSize = 15.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(18.dp))
                Text("じかん", fontSize = 17.sp, color = Muted, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFE8F1E9), RoundedCornerShape(16.dp))
                        .clickable { pickTime = true }
                        .padding(18.dp, 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        String.format("%d:%02d", hour, minute),
                        fontSize = 44.sp, fontWeight = FontWeight.Black, color = GreenDark,
                    )
                    Spacer(Modifier.width(14.dp))
                    Text("タップして\n時計で えらぶ", fontSize = 14.sp, color = Muted, lineHeight = 19.sp)
                }

                Spacer(Modifier.height(18.dp))
                Text("ようび", fontSize = 17.sp, color = Muted, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..6).forEach { i ->
                        val on = days and (1 shl i) != 0
                        Box(
                            Modifier
                                .size(40.dp)
                                .background(if (on) Green else Color(0xFFEFEFEF), CircleShape)
                                .clickable { days = days xor (1 shl i) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(Days.labels[i], fontSize = 17.sp, color = if (on) Color.White else Muted, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = speech,
                    onValueChange = { speech = it },
                    label = { Text("読み上げる言葉（空なら上と同じ）", fontSize = 15.sp) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )

    if (pickTime) {
        ClockTimeDialog(
            initialHour = hour,
            initialMinute = minute,
            onConfirm = { h, m -> hour = h; minute = m; pickTime = false },
            onCancel = { pickTime = false },
        )
    }
}

/** Android標準と同じ「時計の文字盤」で時刻を選ぶダイアログ */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClockTimeDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (Int, Int) -> Unit,
    onCancel: () -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initialHour, initialMinute = initialMinute, is24Hour = true)
    var keyboard by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) {
                Text("決定", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { keyboard = !keyboard }) {
                    Text(if (keyboard) "時計で選ぶ" else "数字で入力", fontSize = 17.sp)
                }
                TextButton(onClick = onCancel) { Text("やめる", fontSize = 20.sp) }
            }
        },
        title = { Text("じかんを えらぶ", fontSize = 22.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (keyboard) TimeInput(state = state) else TimePicker(state = state)
            }
        },
    )
}

/* ============================================================
 *  設定
 * ========================================================== */

@Composable
private fun SettingsScreen() {
    val ctx = LocalContext.current
    var person by remember { mutableStateOf(Repo.personName) }
    var sheet by remember { mutableStateOf(Repo.sheetUrl) }
    var soundSec by remember { mutableIntStateOf(Repo.soundSeconds) }
    var testMsg by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var ttsMsg by remember { mutableStateOf("") }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    // 読み上げエンジンの準備には少し時間がかかるので、少し待ってから状態を見直す
    LaunchedEffect(Unit) {
        Speaker.init(ctx)
        kotlinx.coroutines.delay(1800)
        refresh++
    }

    Column(Modifier.fillMaxSize()) {
        Header { Text("設定", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color.White) }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp)
        ) {
            SectionTitle("① 端末の設定（いちばん大事）")
            Text(
                "ここが全部「✅」になっていないと、アプリを閉じているときに鳴りません。",
                fontSize = 15.sp, color = Muted, lineHeight = 22.sp,
            )
            Spacer(Modifier.height(10.dp))

            key(refresh) {
                PermRow(
                    "通知を出す",
                    ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    else true,
                ) {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        )
                    }
                    refresh++
                }

                PermRow("時間ちょうどに鳴らす", ok = Scheduler.canScheduleExact(ctx)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
                        }
                    }
                    refresh++
                }

                PermRow("画面いっぱいに出す", ok = canFullScreen(ctx)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}"))
                            )
                        }
                    }
                    refresh++
                }

                PermRow("他のアプリの上に重ねて表示", ok = canOverlay(ctx)) {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                        )
                    }
                    refresh++
                }

                PermRow("電池の節約から外す", ok = ignoringBattery(ctx)) {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                        )
                    }.onFailure {
                        runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }
                    refresh++
                }

                PermRow("日本語の音声が使える", ok = Speaker.isJapaneseAvailable) {
                    runCatching { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                        .onFailure {
                            runCatching {
                                ctx.startActivity(Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
                            }
                        }
                    refresh++
                }
            }

            Text(
                "「他のアプリの上に重ねて表示」は、スマホを操作している最中でも画面いっぱいに出すために使います。",
                fontSize = 13.sp, color = Muted, lineHeight = 19.sp,
            )

            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { refresh++ },
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) { Text("状態を確認しなおす", fontSize = 17.sp) }

            Spacer(Modifier.height(26.dp))
            SectionTitle("② 動作テスト")
            Text("10秒後に、本番と同じようにお知らせを出します。画面を消してから待ってみてください。", fontSize = 15.sp, color = Muted, lineHeight = 22.sp)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    Scheduler.scheduleTest(ctx, 10)
                    Toast.makeText(ctx, "10秒後に鳴ります", Toast.LENGTH_LONG).show()
                },
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("10秒後にテストする", fontSize = 20.sp, fontWeight = FontWeight.Bold) }

            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    ttsMsg = "準備しています…"
                    Speaker.speak(ctx, "読み上げのテストです。この声が聞こえていれば大丈夫です。", times = 1) {
                        ttsMsg = Speaker.statusText()
                        refresh++
                    }
                    mainHandler.postDelayed({
                        if (ttsMsg == "準備しています…") ttsMsg = Speaker.statusText()
                    }, 3000)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("読み上げだけ試す", fontSize = 17.sp) }
            Text(
                "声が出ないときは、下に出る文を教えてください。原因がわかります。",
                fontSize = 13.sp, color = Muted, lineHeight = 19.sp,
            )
            if (ttsMsg.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("読み上げ：$ttsMsg", fontSize = 15.sp, color = Ink, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(26.dp))
            SectionTitle("③ 鳴らし続ける長さ")
            Text(
                "「完了」を押すまで鳴り続けます。押されなかった場合も、この時間で自動的に止まります。",
                fontSize = 15.sp, color = Muted, lineHeight = 22.sp,
            )
            Spacer(Modifier.height(8.dp))
            Stepper(value = soundSec, suffix = "秒", step = 30) {
                soundSec = it.coerceIn(30, 300)
                Repo.soundSeconds = soundSec
            }
            Text("止まったあとも「まだ完了していません」の通知は残ります。", fontSize = 13.sp, color = Muted, lineHeight = 19.sp)

            Spacer(Modifier.height(26.dp))
            SectionTitle("④ ご家族への共有（スプレッドシート）")
            OutlinedTextField(
                value = person,
                onValueChange = { person = it; Repo.personName = it },
                label = { Text("お名前（シートに記録されます）", fontSize = 15.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = sheet,
                onValueChange = { sheet = it; Repo.sheetUrl = it },
                label = { Text("Apps Script のURL", fontSize = 15.sp) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    testMsg = "送信中…"
                    val u = sheet.trim()
                    val p = person.trim()
                    val main = android.os.Handler(android.os.Looper.getMainLooper())
                    Thread {
                        val (ok, msg) = SheetSync.testConnection(u, p)
                        main.post { testMsg = (if (ok) "✅ " else "⚠️ ") + msg }
                    }.start()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = sheet.isNotBlank(),
            ) { Text("接続テスト", fontSize = 17.sp) }
            if (testMsg.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(testMsg, fontSize = 15.sp, color = Ink, lineHeight = 22.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text("未送信: ${Repo.queueSize()} 件", fontSize = 14.sp, color = Muted)

            Spacer(Modifier.height(36.dp))
            Text(
                "このアプリは飲み忘れを減らすための補助です。命にかかわるお薬の管理を、これだけに頼らないようにしてください。",
                fontSize = 14.sp, color = Amber, lineHeight = 21.sp,
            )
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 21.sp, fontWeight = FontWeight.Black, color = GreenDark)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun PermRow(label: String, ok: Boolean, onFix: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (ok) "✅" else "⚠️", fontSize = 22.sp)
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 18.sp, color = Ink, modifier = Modifier.weight(1f))
        if (!ok) {
            Button(onClick = onFix, shape = RoundedCornerShape(10.dp)) { Text("設定する", fontSize = 15.sp) }
        }
    }
}

@Composable
private fun Stepper(value: Int, suffix: String, step: Int = 1, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundBtn("−") { onChange(value - step) }
        Box(Modifier.width(88.dp), contentAlignment = Alignment.Center) {
            Text(value.toString(), fontSize = 34.sp, fontWeight = FontWeight.Black, color = Ink)
        }
        RoundBtn("＋") { onChange(value + step) }
        Text(suffix, fontSize = 19.sp, color = Muted, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun RoundBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .background(Color(0xFFE8F1E9), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 28.sp, fontWeight = FontWeight.Black, color = GreenDark) }
}

/* ---------------- 権限の確認 ---------------- */

private fun canFullScreen(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
    return runCatching {
        ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }.getOrDefault(true)
}

private fun canOverlay(ctx: Context): Boolean =
    runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)

private fun ignoringBattery(ctx: Context): Boolean = runCatching {
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
}.getOrDefault(true)
