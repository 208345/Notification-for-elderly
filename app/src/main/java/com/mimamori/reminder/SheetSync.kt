package com.mimamori.reminder

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 完了・未完了を Google スプレッドシート（Apps Script のウェブアプリ）へ送ります。
 *
 * ・送る前にいったん端末に貯めるので、圏外でも記録は失われません
 * ・次に送信に成功したとき、まとめて送られます
 */
object SheetSync {

    private const val TAG = "MimamoriSync"
    private val stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @Volatile private var flushing = false

    /** 1件の出来事を記録して送信する */
    fun reportAsync(ctx: Context, task: TaskItem, date: LocalDate, status: String) {
        Repo.init(ctx)
        val o = JSONObject().apply {
            put("person", Repo.personName.ifBlank { "本人" })
            put("date", date.toString())
            put("taskId", task.id)
            put("title", task.title)
            put("scheduled", task.timeLabel)
            put("status", status) // done / undone / missed
            put("at", LocalDateTime.now().format(stamp))
        }
        Repo.enqueue(o)
        flushAsync(ctx)
    }

    /** その日ぶんの実施状況をまとめて送る（毎日23:50） */
    fun sendDailySummaryAsync(ctx: Context) {
        Repo.init(ctx)
        val today = LocalDate.now()
        Repo.tasksOn(today).forEach { t ->
            val doneAt = Repo.doneAt(t.id, today)
            val o = JSONObject().apply {
                put("person", Repo.personName.ifBlank { "本人" })
                put("date", today.toString())
                put("taskId", t.id)
                put("title", t.title)
                put("scheduled", t.timeLabel)
                put("status", if (doneAt != null) "done" else "missed")
                put("at", LocalDateTime.now().format(stamp))
                put("summary", true)
            }
            Repo.enqueue(o)
        }
        flushAsync(ctx)
    }

    /** 溜まっているぶんをまとめて送る */
    fun flushAsync(ctx: Context) {
        val url = Repo.sheetUrl
        if (url.isBlank()) return
        if (flushing) return
        flushing = true
        Thread {
            try {
                val items = Repo.takeQueue()
                if (items.isEmpty()) return@Thread
                val body = JSONObject().apply {
                    put("type", "events")
                    put("events", JSONArray().also { arr -> items.forEach { arr.put(it) } })
                }
                val ok = post(url, body.toString())
                if (!ok) {
                    // 送れなかったぶんは戻しておき、次の機会に再送する
                    items.forEach { Repo.enqueue(it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "送信に失敗: ${e.message}")
            } finally {
                flushing = false
            }
        }.start()
    }

    /** 設定画面の「接続テスト」用。成功したかどうかを返します。 */
    fun testConnection(url: String, person: String): Pair<Boolean, String> {
        return try {
            val body = JSONObject().apply {
                put("type", "events")
                put("events", JSONArray().put(JSONObject().apply {
                    put("person", person.ifBlank { "本人" })
                    put("date", LocalDate.now().toString())
                    put("taskId", 0)
                    put("title", "接続テスト")
                    put("scheduled", "--:--")
                    put("status", "test")
                    put("at", LocalDateTime.now().format(stamp))
                }))
            }
            if (post(url, body.toString())) true to "スプレッドシートに書き込めました"
            else false to "つながりましたが、書き込みに失敗しました。Apps Script の設定を確認してください"
        } catch (e: Exception) {
            false to "つながりませんでした: ${e.message}"
        }
    }

    /**
     * Apps Script のウェブアプリは 302 で別ドメインへ飛ばすため、
     * リダイレクトを自分でたどる必要があります。
     */
    private fun post(urlStr: String, json: String): Boolean {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                doOutput = true
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            var code = conn.responseCode
            var hops = 0
            while (code in 300..399 && hops < 5) {
                val loc = conn?.getHeaderField("Location") ?: break
                conn?.disconnect()
                conn = (URL(loc).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                    connectTimeout = 15000
                    readTimeout = 20000
                }
                code = conn.responseCode
                hops++
            }
            val ok = code in 200..299
            if (!ok) Log.w(TAG, "HTTP $code")
            return ok
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
