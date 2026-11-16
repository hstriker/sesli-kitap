package com.seslikitap.player

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.seslikitap.Kayit
import com.seslikitap.container
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Kitap dışı sesli içerik (uyku hikâyesi, nefes yönergesi, masal, merak köşesi…). Metin Gemini sesiyle
 * hazırlanıp telefonda saklanır (aynı metin tekrar hak yemez); anahtar/hak yoksa telefon sesiyle okunur.
 * Başlarken kitap duraklatılır.
 */
object AudioSession {

    data class State(
        val title: String? = null,
        val preparing: Boolean = false,
        val playing: Boolean = false,
        val paused: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var mp: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = CompletableDeferred<Boolean>()
    private val ttsWaits = HashMap<String, CompletableDeferred<Unit>>()

    /** [text]'i baştan sona okur; [useGemini] false ise doğrudan telefon sesi (kısa yönergeler için). */
    fun play(context: Context, title: String, text: String, useGemini: Boolean = true, onDone: (() -> Unit)? = null) {
        stop()
        val app = context.applicationContext
        app.container.player.pause()
        _state.value = State(title, preparing = true, playing = true)
        job = scope.launch {
            try {
                for (part in split(text, app.container.prefs.geminiGroupMax.coerceAtMost(6000))) {
                    val file = if (useGemini) prepare(app, part) else null
                    _state.value = _state.value.copy(preparing = false)
                    if (file != null) playFile(file) else speakLocal(app, part)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Kayit.error("Sesli içerik çalınamadı", e)
            }
            _state.value = State()
            onDone?.invoke()
        }
    }

    /** Kısa bir yönergeyi (ör. "nefes al") telefon sesiyle hemen söyler; süren içeriği durdurmaz. */
    fun say(context: Context, text: String) {
        val app = context.applicationContext
        scope.launch {
            val t = ensureTts(app)
            if (withTimeoutOrNull(5000) { ttsReady.await() } == true) t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kisa")
        }
    }

    fun togglePause() {
        val p = mp ?: return
        if (p.isPlaying) { p.pause(); _state.value = _state.value.copy(paused = true) }
        else { p.start(); _state.value = _state.value.copy(paused = false) }
    }

    fun stop() {
        job?.cancel()
        job = null
        mp?.let { runCatching { it.stop() }; runCatching { it.release() } }
        mp = null
        tts?.stop()
        ttsWaits.values.forEach { it.complete(Unit) }
        ttsWaits.clear()
        _state.value = State()
    }

    /** Ses seviyesini yavaşça kısarak durdurur (uyku hikâyesinin sonunda). */
    fun fadeOutAndStop(ms: Long = 20_000) {
        scope.launch {
            val p = mp
            if (p != null) {
                for (i in 10 downTo 1) { runCatching { p.setVolume(i / 10f, i / 10f) }; delay(ms / 10) }
            }
            stop()
        }
    }

    private suspend fun prepare(context: Context, text: String): File? {
        val prefs = context.container.prefs
        if (prefs.geminiKey.isNullOrBlank()) return null
        val voice = prefs.geminiVoice
        GeminiAudio.cached(context, text, voice, true)?.let { return it }
        return try {
            withContext(Dispatchers.IO) { GeminiAudio.synthesize(context, prefs, text, voice, true) }
        } catch (e: GeminiTts.QuotaException) {
            GeminiTts.recordQuota(prefs, e)
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Kayit.log("Sesli içerik Gemini'siz okunuyor: ${e.message}")
            null
        }
    }

    private suspend fun playFile(file: File) = suspendCancellableCoroutine<Unit> { cont ->
        val p = MediaPlayer()
        mp = p
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            p.setDataSource(file.path)
            p.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
            p.setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resume(Unit); true }
            p.prepare()
            p.start()
        } catch (e: Exception) {
            Kayit.error("Sesli içerik dosyası çalınamadı", e)
            if (cont.isActive) cont.resume(Unit)
        }
        cont.invokeOnCancellation { runCatching { p.stop() } }
    }.also {
        mp?.let { runCatching { it.release() } }
        mp = null
    }

    private fun ensureTts(context: Context): TextToSpeech {
        tts?.let { return it }
        ttsReady = CompletableDeferred()
        val t = TextToSpeech(context, { code ->
            if (code == TextToSpeech.SUCCESS) tts?.language = Locale("tr", "TR")
            ttsReady.complete(code == TextToSpeech.SUCCESS)
        }, context.container.prefs.ttsEngine)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) { synchronized(ttsWaits) { ttsWaits.remove(utteranceId) }?.complete(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { synchronized(ttsWaits) { ttsWaits.remove(utteranceId) }?.complete(Unit) }
        })
        tts = t
        return t
    }

    private suspend fun speakLocal(context: Context, text: String) {
        val t = ensureTts(context)
        if (withTimeoutOrNull(10_000) { ttsReady.await() } != true) return
        val id = "oturum-${System.nanoTime()}"
        val wait = CompletableDeferred<Unit>()
        synchronized(ttsWaits) { ttsWaits[id] = wait }
        // Uzun metin: TextToSpeech'in giriş sınırı için paragraflara böl
        val pieces = text.split(Regex("\\n+")).flatMap { it.chunked(3500) }.filter { it.isNotBlank() }
        pieces.forEachIndexed { i, p ->
            t.speak(p, TextToSpeech.QUEUE_ADD, Bundle(), if (i == pieces.lastIndex) id else "$id-$i")
        }
        if (pieces.isEmpty()) return
        wait.await()
    }

    /** Uzun metni paragraf sınırlarında Gemini parça boyuna böler. */
    private fun split(text: String, max: Int): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (para in text.split(Regex("\\n\\s*\\n|\\n")).map { it.trim() }.filter { it.isNotEmpty() }) {
            if (sb.isNotEmpty() && sb.length + para.length > max) { out.add(sb.toString()); sb.clear() }
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(para)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}
