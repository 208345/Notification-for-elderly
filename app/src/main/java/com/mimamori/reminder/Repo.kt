package com.mimamori.reminder

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 保存はすべて SharedPreferences ＋ JSON。
 * データベースを使わないぶん依存が減り、ビルドが壊れにくくなっています。
 */
object Repo {

    private const val PREF = "mimamori"
    private const val K_TASKS = "tasks"
    private const val K_DONE = "done"
    private const val K_QUEUE = "queue"
    private const val K_SHEET = "sheet_url"
    private const val K_PERSON = "person_name"
    private const val K_SOUND_SEC = "sound_sec"

    private lateinit var prefs: SharedPreferences

    private val _tasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val tasks: StateFlow<List<TaskItem>> = _tasks

    /** キーは "taskId|yyyy-MM-dd"、値は完了した時刻(ミリ秒) */
    private val _done = MutableStateFlow<Map<String, Long>>(emptyMap())
    val done: StateFlow<Map<String, Long>> = _done

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        _tasks.value = TaskItem.listFromJson(prefs.getString(K_TASKS, null))
        _done.value = readDone()
        pruneDone()
    }

    // ---------------- タスク ----------------

    fun upsert(task: TaskItem) {
        val list = _tasks.value.toMutableList()
        val i = list.indexOfFirst { it.id == task.id }
        if (i >= 0) list[i] = task else list.add(task)
        list.sortWith(compareBy({ it.hour }, { it.minute }, { it.id }))
        _tasks.value = list
        prefs.edit().putString(K_TASKS, TaskItem.listToJson(list)).apply()
    }

    fun delete(id: Long) {
        val list = _tasks.value.filterNot { it.id == id }
        _tasks.value = list
        prefs.edit().putString(K_TASKS, TaskItem.listToJson(list)).apply()
    }

    fun byId(id: Long): TaskItem? = _tasks.value.firstOrNull { it.id == id }

    fun tasksOn(date: LocalDate): List<TaskItem> =
        _tasks.value.filter { it.enabled && Days.includes(it.days, date) }

    fun newId(): Long = System.currentTimeMillis()

    // ---------------- 完了記録 ----------------

    fun doneKey(taskId: Long, date: LocalDate) = "$taskId|$date"

    fun isDone(taskId: Long, date: LocalDate): Boolean = _done.value.containsKey(doneKey(taskId, date))

    fun doneAt(taskId: Long, date: LocalDate): Long? = _done.value[doneKey(taskId, date)]

    fun setDone(taskId: Long, date: LocalDate, done: Boolean) {
        val m = _done.value.toMutableMap()
        if (done) m[doneKey(taskId, date)] = System.currentTimeMillis() else m.remove(doneKey(taskId, date))
        _done.value = m
        writeDone(m)
    }

    private fun readDone(): Map<String, Long> {
        val s = prefs.getString(K_DONE, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(s)
            val m = HashMap<String, Long>()
            o.keys().forEach { k -> m[k] = o.optLong(k) }
            m as Map<String, Long>
        }.getOrDefault(emptyMap())
    }

    private fun writeDone(m: Map<String, Long>) {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, v) }
        prefs.edit().putString(K_DONE, o.toString()).apply()
    }

    /** 60日より古い記録は捨てる（端末を軽く保つため） */
    private fun pruneDone() {
        val limit = LocalDate.now().minusDays(60).toString()
        val m = _done.value.filterKeys { key ->
            val d = key.substringAfter('|', "")
            d.isEmpty() || d >= limit
        }
        if (m.size != _done.value.size) {
            _done.value = m
            writeDone(m)
        }
    }

    // ---------------- 設定 ----------------

    var sheetUrl: String
        get() = prefs.getString(K_SHEET, "") ?: ""
        set(v) { prefs.edit().putString(K_SHEET, v.trim()).apply() }

    var personName: String
        get() = prefs.getString(K_PERSON, "") ?: ""
        set(v) { prefs.edit().putString(K_PERSON, v.trim()).apply() }

    /** アラームを鳴らし続ける秒数（0で音なし、既定60秒） */
    var soundSeconds: Int
        get() = prefs.getInt(K_SOUND_SEC, 60)
        set(v) { prefs.edit().putInt(K_SOUND_SEC, v.coerceIn(0, 300)).apply() }

    // ---------------- 送信待ちキュー ----------------

    fun enqueue(payload: JSONObject) {
        val arr = runCatching { JSONArray(prefs.getString(K_QUEUE, "[]")) }.getOrDefault(JSONArray())
        // 溜まりすぎ防止
        val trimmed = JSONArray()
        val start = maxOf(0, arr.length() - 199)
        for (i in start until arr.length()) trimmed.put(arr.get(i))
        trimmed.put(payload)
        prefs.edit().putString(K_QUEUE, trimmed.toString()).apply()
    }

    fun takeQueue(): List<JSONObject> {
        val arr = runCatching { JSONArray(prefs.getString(K_QUEUE, "[]")) }.getOrDefault(JSONArray())
        prefs.edit().putString(K_QUEUE, "[]").apply()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    fun queueSize(): Int =
        runCatching { JSONArray(prefs.getString(K_QUEUE, "[]")).length() }.getOrDefault(0)
}
