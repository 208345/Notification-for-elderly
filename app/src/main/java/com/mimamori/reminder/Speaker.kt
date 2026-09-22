package com.mimamori.reminder

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 端末に入っている音声合成でタスクを読み上げる。
 * 初期化が非同期なので、準備ができるまで喋る内容を保留しておきます。
 */
object Speaker {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var japaneseOk = true
    private var pending: (() -> Unit)? = null
    private var onAllDone: (() -> Unit)? = null

    val isJapaneseAvailable: Boolean get() = japaneseOk

    fun init(ctx: Context, then: (() -> Unit)? = null) {
        if (ready) { then?.invoke(); return }
        if (tts != null) { pending = then; return }
        pending = then
        val app = ctx.applicationContext
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts
                val r = engine?.setLanguage(Locale.JAPANESE)
                japaneseOk = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                engine?.setSpeechRate(0.88f)   // 高齢者向けに少しゆっくり
                engine?.setPitch(1.0f)
                engine?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == "last") onAllDone?.invoke()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == "last") onAllDone?.invoke()
                    }
                })
                ready = true
                pending?.invoke()
                pending = null
            }
        }
    }

    /**
     * text を times 回くり返して読み上げる。
     * すべて読み終わったら onDone が呼ばれます（読み上げできなかった場合もすぐ呼ばれます）。
     */
    fun speak(ctx: Context, text: String, times: Int = 2, onDone: () -> Unit = {}) {
        onAllDone = onDone
        init(ctx) {
            val engine = tts
            if (engine == null) { onDone(); return@init }
            engine.stop()
            var ok = true
            for (i in 0 until times) {
                val id = if (i == times - 1) "last" else "u$i"
                val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val r = engine.speak(text, mode, Bundle(), id)
                if (r != TextToSpeech.SUCCESS) ok = false
                if (i < times - 1) engine.playSilentUtterance(900, TextToSpeech.QUEUE_ADD, "s$i")
            }
            if (!ok) onDone()
        }
        // 音声エンジンが用意できないまま固まるのを防ぐ保険
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!ready) { onAllDone?.invoke(); onAllDone = null }
        }, 6000)
    }

    fun stop() {
        onAllDone = null
        runCatching { tts?.stop() }
    }
}
