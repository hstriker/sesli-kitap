package com.seslikitap.player

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import com.seslikitap.Kayit
import com.seslikitap.Prefs
import com.seslikitap.drive.DriveAuth
import com.seslikitap.drive.DriveAuthException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.math.ln

/**
 * Doğal ses: Google Cloud Text-to-Speech (WaveNet).
 *
 * - Cümleler sıraya girer girmez arka planda seslendirilir (önceden hazırlama), sonra sırayla çalınır.
 * - Seslendirilen her cümle telefonda saklanır: tekrar dinlemek kotadan yemez.
 * - Aylık karakter sınırı dolduysa, internet yoksa ya da Google hata verirse o cümle
 *   telefondaki ücretsiz sesle hazırlanır ve aynı sırada çalınır; okuma durmaz.
 */
class CloudTtsEngine(
    private val context: Context,
    private val auth: DriveAuth,
    private val prefs: Prefs,
) : SpeechEngine {

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Ready)
    override val status: StateFlow<EngineStatus> = _status
    override var listener: SpeechListener? = null

    /** Kullanıcıya bir kez gösterilecek bilgi (ör. "kota doldu, telefon sesine geçildi"). */
    val notice = MutableStateFlow<String?>(null)

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private class Item(val id: String, val audio: Deferred<File?>)

    private var items = Channel<Item>(Channel.UNLIMITED)
    private var loopJob: Job? = null
    private var player: MediaPlayer? = null

    @Volatile private var rate = 1f
    @Volatile private var pitch = 1f
    @Volatile private var voice = prefs.cloudVoice

    private val synth = CloudSynth(context, auth, prefs)
    private val tmpDir = File(context.cacheDir, "ses-gecici").apply { mkdirs() }

    // ------------------------------------------------------------------ SpeechEngine

    @Volatile private var dialogVoice: String? = null

    override fun setDialogVoice(name: String?) {
        dialogVoice = name?.takeIf { n -> VOICES.any { it.first == n } }
    }

    override fun speak(id: String, text: String, flush: Boolean, alternate: Boolean) {
        if (flush) stop()
        val r = rate; val p = pitch
        val v = if (alternate) dialogVoice ?: voice else voice
        val audio = ioScope.async { synthesize(text, v, r, p) }
        items.trySend(Item(id, audio))
        ensureLoop()
    }

    override fun stop() {
        loopJob?.cancel()
        loopJob = null
        val old = items
        items = Channel(Channel.UNLIMITED)
        while (true) {
            val it = old.tryReceive().getOrNull() ?: break
            it.audio.cancel()
        }
        old.close()
        player?.let { mp -> runCatching { mp.stop() }; mp.release() }
        player = null
    }

    @Volatile private var volume = 1f

    override fun setVolume(volume: Float) {
        this.volume = volume
        playScope.launch { runCatching { player?.setVolume(volume, volume) } }
    }

    override fun setRate(rate: Float) { this.rate = rate }
    override fun setPitch(pitch: Float) { this.pitch = pitch }

    override fun voices(): List<VoiceInfo> = VOICES.map { (name, label) -> VoiceInfo(name, label, true, 400) }

    override fun setVoice(name: String?) {
        voice = name?.takeIf { n -> VOICES.any { it.first == n } } ?: DEFAULT_VOICE
    }

    override fun shutdown() {
        stop()
        ioScope.cancel()
        playScope.cancel()
        localTts?.shutdown()
        localTts = null
    }

    // ------------------------------------------------------------------ Çalma

    private fun ensureLoop() {
        if (loopJob?.isActive == true) return
        val channel = items
        loopJob = playScope.launch {
            for (item in channel) {
                val file = try { item.audio.await() } catch (e: Exception) { null }
                if (file == null) {
                    listener?.onError(item.id, -1)
                    continue
                }
                listener?.onStart(item.id)
                val ok = playFile(file)
                if (ok) listener?.onDone(item.id) else listener?.onError(item.id, -2)
            }
        }
    }

    private suspend fun playFile(file: File): Boolean = suspendCancellableCoroutine { cont ->
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.path)
            mp.setOnCompletionListener {
                it.release()
                if (player === it) player = null
                if (cont.isActive) cont.resume(true)
            }
            mp.setOnErrorListener { p, what, extra ->
                Kayit.log("Ses çalınamadı ($what/$extra): ${file.name}")
                p.release()
                if (player === p) player = null
                if (cont.isActive) cont.resume(false)
                true
            }
            mp.prepare()
            mp.setVolume(volume, volume)
            mp.start()
        } catch (e: Exception) {
            Kayit.error("Ses çalınamadı", e)
            mp.release()
            if (player === mp) player = null
            file.delete() // bozuk dosya: bir dahaki sefere yeniden hazırlansın
            if (cont.isActive) cont.resume(false)
        }
        cont.invokeOnCancellation {
            playScope.launch { runCatching { mp.stop() }; mp.release() }
        }
    }

    // ------------------------------------------------------------------ Seslendirme

    private suspend fun synthesize(text: String, voice: String, rate: Float, pitch: Float): File? {
        synth.cached(text, voice, rate, pitch)?.let { return it }
        if (!synth.hasQuotaFor(text)) {
            notice.value = "Bu ayın doğal ses kotası doldu; ay sonuna kadar telefon sesi kullanılıyor."
            return synthesizeLocal(text, rate, pitch)
        }
        return try {
            synth.synthesize(text, voice, rate, pitch)
        } catch (e: Exception) {
            Kayit.error("Doğal ses alınamadı", e)
            notice.value = when (e) {
                is DriveAuthException -> e.message
                else -> "Doğal ses alınamadı (${e.message}); telefon sesiyle devam ediliyor."
            }
            synthesizeLocal(text, rate, pitch)
        }
    }

    // ------------------------------------------------------------------ Telefon sesine geri düşme

    private var localTts: TextToSpeech? = null
    private val localReady = CompletableDeferred<Boolean>()
    private val localJobs = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private fun local(): TextToSpeech {
        localTts?.let { return it }
        // Başlatma bildirimi daha sonra gelir; o zamana kadar localTts atanmış olur.
        val t = TextToSpeech(context.applicationContext, { code ->
            if (code == TextToSpeech.SUCCESS) localTts?.setLanguage(Locale("tr", "TR"))
            localReady.complete(code == TextToSpeech.SUCCESS)
        }, prefs.ttsEngine)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) { localJobs.remove(utteranceId)?.complete(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { localJobs.remove(utteranceId)?.complete(false) }
            override fun onError(utteranceId: String, errorCode: Int) { localJobs.remove(utteranceId)?.complete(false) }
        })
        localTts = t
        return t
    }

    private suspend fun synthesizeLocal(text: String, rate: Float, pitch: Float): File? {
        val t = local()
        if (withTimeoutOrNull(10_000) { localReady.await() } != true) return null
        val file = File(tmpDir, "yerel-${System.nanoTime()}.wav")
        val uid = file.name
        val done = CompletableDeferred<Boolean>()
        localJobs[uid] = done
        t.setSpeechRate(rate)
        t.setPitch(pitch)
        if (t.synthesizeToFile(text, Bundle(), file, uid) != TextToSpeech.SUCCESS) {
            localJobs.remove(uid)
            return null
        }
        val ok = withTimeoutOrNull(60_000) { done.await() } == true
        // Eski geçici dosyaları temizle
        tmpDir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 10 * 60_000L }?.forEach { it.delete() }
        return if (ok && file.exists() && file.length() > 44) file else null
    }

    companion object {
        const val PROJECT_ID = CloudSynth.PROJECT_ID
        const val DEFAULT_VOICE = "tr-TR-Wavenet-E"

        /** Google'ın Türkçe WaveNet sesleri. */
        val VOICES = listOf(
            "tr-TR-Wavenet-A" to "WaveNet A",
            "tr-TR-Wavenet-B" to "WaveNet B",
            "tr-TR-Wavenet-C" to "WaveNet C",
            "tr-TR-Wavenet-D" to "WaveNet D",
            "tr-TR-Wavenet-E" to "WaveNet E",
        )
    }
}
