package com.mimamori.reminder

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * 端末に入っている音声合成でタスクを読み上げる。
 *
 *  ・初期化は非同期なので、準備ができるまで喋る内容を保留しておく
 *  ・初期化に失敗したら、次に喋るときにもう一度やり直す
 *  ・標準のエンジンが日本語に対応していなければ、Googleの読み上げエンジンに切り替える
 *  ・いまの状態を statusText() で画面に出せる（原因の切り分け用）
 */
object Speaker {

    private const val TAG = "MimamoriTTS"
    private const val GOOGLE_TTS = "com.google.android.tts"

    enum class State { IDLE, INIT, READY, FAILED }

    @Volatile var state: State = State.IDLE
        private set
    @Volatile var engineName: String = ""
        private set
    @Volatile var langResult: Int = TextToSpeech.LANG_NOT_SUPPORTED
        private set
    @Volatile var lastError: String = ""
        private set

    private var tts: TextToSpeech? = null
    private var usingEngine: String? = null
    private var triedGoogle = false
    private val pending = mutableListOf<() -> Unit>()
    private var onAllDone: (() -> Unit)? = null
    private var token = 0
    private val main = Handler(Looper.getMainLooper())

    val isJapaneseAvailable: Boolean
        get() = state == State.READY &&
            langResult != TextToSpeech.LANG_MISSING_DATA &&
            langResult != TextToSpeech.LANG_NOT_SUPPORTED

    /** 設定画面に出す、いまの読み上げの状態 */
    fun statusText(): String = when (state) {
        State.IDLE -> "まだ準備していません"
        State.INIT -> "準備しています…"
        State.FAILED -> "読み上げエンジンが使えません（$lastError）"
        State.READY -> when (langResult) {
            TextToSpeech.LANG_MISSING_DATA -> "日本語の音声データが入っていません（$engineName）"
            TextToSpeech.LANG_NOT_SUPPORTED -> "この読み上げエンジンは日本語に対応していません（$engineName）"
            else -> if (lastError.isNotEmpty()) "使えますが、直前に失敗がありました（$lastError / $engineName）"
                    else "使えます（$engineName）"
        }
    }

    fun init(ctx: Context, then: (() -> Unit)? = null) {
        when (state) {
            State.READY -> then?.invoke()
            State.INIT -> if (then != null) pending.add(then)
            State.IDLE, State.FAILED -> {
                if (then != null) pending.add(then)
                create(ctx.applicationContext, if (triedGoogle) GOOGLE_TTS else null)
            }
        }
    }

    private fun create(app: Context, engine: String?) {
        state = State.INIT
        runCatching { tts?.shutdown() }
        tts = null
        usingEngine = engine
        // 端末によっては、コンストラクタの中から同期的に結果が返ってくることがある。
        // そのときに tts がまだ null でも困らないよう、結果は必ず次のループで処理する。
        val listener = TextToSpeech.OnInitListener { status -> main.post { onInit(app, status) } }
        tts = try {
            if (engine != null) TextToSpeech(app, listener, engine) else TextToSpeech(app, listener)
        } catch (e: Exception) {
            Log.w(TAG, "create failed", e)
            lastError = e.javaClass.simpleName
            state = State.FAILED
            flushPending()
            null
        }
    }

    private fun hasGoogle(engine: TextToSpeech?): Boolean =
        runCatching { engine?.engines?.any { it.name == GOOGLE_TTS } == true }.getOrDefault(false)

    private fun onInit(app: Context, status: Int) {
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            lastError = "初期化エラー $status"
            Log.w(TAG, "init failed: $status engine=$usingEngine")
            if (!triedGoogle && usingEngine != GOOGLE_TTS && hasGoogle(engine)) {
                triedGoogle = true
                create(app, GOOGLE_TTS)
                return
            }
            state = State.FAILED
            flushPending()
            return
        }

        engineName = usingEngine ?: runCatching { engine.defaultEngine }.getOrNull() ?: ""
        langResult = runCatching { engine.setLanguage(Locale.JAPAN) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)

        // 標準エンジンが日本語非対応なら、Googleの読み上げエンジンで試し直す
        val noJapanese = langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED
        if (noJapanese && !triedGoogle && usingEngine != GOOGLE_TTS && hasGoogle(engine)) {
            triedGoogle = true
            create(app, GOOGLE_TTS)
            return
        }

        runCatching {
            engine.setSpeechRate(0.9f)   // 高齢者向けに少しゆっくり
            engine.setPitch(1.0f)
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "last") main.post { fireDone() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                lastError = "読み上げ中のエラー"
                if (utteranceId == "last") main.post { fireDone() }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                lastError = "読み上げ中のエラー $errorCode"
                if (utteranceId == "last") main.post { fireDone() }
            }
        })
        lastError = ""
        state = State.READY
        Log.i(TAG, "ready engine=$engineName lang=$langResult")
        flushPending()
    }

    private fun flushPending() {
        val list = pending.toList()
        pending.clear()
        list.forEach { runCatching { it() } }
    }

    private fun fireDone() {
        val cb = onAllDone
        onAllDone = null
        cb?.invoke()
    }

    /**
     * text を times 回くり返して読み上げる。
     * 読み終わったら onDone が呼ばれる（読み上げできなかったときも、必ず呼ばれる）。
     */
    fun speak(ctx: Context, text: String, times: Int = 2, onDone: () -> Unit = {}) {
        val my = ++token
        onAllDone = onDone
        init(ctx) {
            if (my != token) return@init
            val engine = tts
            if (state != State.READY || engine == null) { fireDone(); return@init }
            runCatching { engine.stop() }
            var ok = true
            for (i in 0 until times) {
                val id = if (i == times - 1) "last" else "u$i"
                val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
                val r = runCatching { engine.speak(text, mode, params, id) }.getOrDefault(TextToSpeech.ERROR)
                if (r != TextToSpeech.SUCCESS) ok = false
                if (i < times - 1) runCatching { engine.playSilentUtterance(800, TextToSpeech.QUEUE_ADD, "s$i") }
            }
            if (!ok) {
                lastError = "speak() が失敗しました"
                // 次回はエンジンを作り直す
                state = State.FAILED
                fireDone()
            }
        }
        // 保険：エンジンが何も言わずに固まっても、先に進めるようにする
        val estimate = 4000L + text.length * 220L * times
        main.postDelayed({ if (my == token) fireDone() }, estimate)
    }

    fun stop() {
        token++
        onAllDone = null
        runCatching { tts?.stop() }
    }
}
