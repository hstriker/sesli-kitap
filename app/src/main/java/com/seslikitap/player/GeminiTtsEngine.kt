package com.seslikitap.player

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import com.seslikitap.Kayit
import com.seslikitap.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Gemini sesi (ücretsiz AI Studio anahtarıyla).
 *
 * - Ücretsiz kotada günlük istek sayısı az olduğu için cümleler tek tek değil, uzun parçalar
 *   (birkaç paragraf) hâlinde seslendirilir; parçanın içindeki cümle vurgusu süreye göre tahmin edilir.
 * - Seslendirilen her parça telefonda saklanır: tekrar dinlemek, duraklatıp devam etmek kotadan yemez.
 * - Kota dolunca (ya da internet yoksa) parça telefon sesiyle cümle cümle okunur; okuma durmaz.
 *   Günlük kota yenilenince (Türkiye saatiyle sabah 10-11 arası) Gemini sesi kendiliğinden geri gelir.
 */
class GeminiTtsEngine(
    private val context: Context,
    private val prefs: Prefs,
) : GroupSpeechEngine {

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Ready)
    override val status: StateFlow<EngineStatus> = _status
    override var listener: SpeechListener? = null

    /** Kullanıcıya bir kez gösterilecek bilgi. */
    val notice = MutableStateFlow<String?>(null)

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private class Group(val items: List<Pair<String, String>>, val startAt: Int, val audio: Deferred<File?>)

    private var groups = Channel<Group>(Channel.UNLIMITED)
    private var loopJob: Job? = null
    private var player: MediaPlayer? = null

    @Volatile private var rate = 1f
    @Volatile private var pitch = 1f
    @Volatile private var volume = 1f
    @Volatile private var voice = prefs.geminiVoice

    /** Aynı parça için süren istek: duraklat/devam et aynı isteği bekler, yeni istek açmaz. */
    private val inflight = ConcurrentHashMap<String, Deferred<File?>>()

    // ------------------------------------------------------------------ SpeechEngine

    override fun speak(id: String, text: String, flush: Boolean, alternate: Boolean) =
        speakGroup(listOf(id to text), 0, flush)

    override fun speakGroup(items: List<Pair<String, String>>, startAt: Int, flush: Boolean) {
        if (items.isEmpty()) return
        if (flush) stop()
        val text = items.joinToString(" ") { it.second }
        groups.trySend(Group(items, startAt.coerceIn(0, items.size - 1), audioFor(text)))
        ensureLoop()
    }

    override fun stop() {
        loopJob?.cancel()
        loopJob = null
        val old = groups
        groups = Channel(Channel.UNLIMITED)
        old.close()
        // Süren Gemini istekleri iptal edilmez: sonuç önbelleğe yazılır, kota boşa gitmez.
        player?.let { mp -> runCatching { mp.stop() }; mp.release() }
        player = null
        fallbackWait?.cancel()
        fallbackWait = null
        local?.stop()
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        playScope.launch { runCatching { player?.setVolume(volume, volume) } }
        local?.setVolume(volume)
    }

    override fun setRate(rate: Float) {
        this.rate = rate
        local?.setRate(rate)
        playScope.launch { player?.let { applyParams(it) } }
    }

    override fun setPitch(pitch: Float) {
        this.pitch = pitch
        local?.setPitch(pitch)
        playScope.launch { player?.let { applyParams(it) } }
    }

    override fun voices(): List<VoiceInfo> = GeminiTts.VOICES.map { (name, label) -> VoiceInfo(name, label, true, 500) }

    override fun setVoice(name: String?) {
        voice = name?.takeIf { n -> GeminiTts.VOICES.any { it.first == n } } ?: prefs.geminiVoice
    }

    override fun shutdown() {
        stop()
        ioScope.cancel()
        playScope.cancel()
        local?.shutdown()
        local = null
    }

    // ------------------------------------------------------------------ Seslendirme

    /** Parçanın sesi: önbellekte varsa o, yoksa Gemini'den; olmazsa null (telefon sesiyle okunur). */
    private fun audioFor(text: String): Deferred<File?> {
        val v = voice
        val narrator = prefs.geminiNarrator
        GeminiAudio.cached(context, text, v, narrator)?.let { return CompletableDeferred(it) }
        val key = GeminiAudio.key(text, v, narrator)
        inflight[key]?.let { return it }
        val d = ioScope.async {
            try {
                request(text, v, narrator)
            } finally {
                inflight.remove(key)
            }
        }
        inflight[key] = d
        return d
    }

    private suspend fun request(text: String, voice: String, narrator: Boolean): File? = try {
        GeminiAudio.synthesize(context, prefs, text, voice, narrator, ::onSwitch)
    } catch (e: GeminiAudio.NoKeyException) {
        notice.value = "Gemini anahtarı yok; telefon sesiyle okunuyor. Ayarlar → Yapay zekâ özeti'nden ekleyin."
        null
    } catch (e: GeminiAudio.BlockedException) {
        null
    } catch (e: GeminiAudio.TruncatedException) {
        notice.value = "Gemini bu parçanın sonunu kesti; parçalar kısaltıldı. Bu kısım telefon sesiyle okunuyor."
        null
    } catch (e: GeminiTts.QuotaException) {
        val until = GeminiTts.recordQuota(prefs, e)
        notice.value = if (until != null) {
            val local = until.withZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
            "Gemini'nin bugünkü ücretsiz sınırı doldu; telefon sesine geçildi. Saat $local'dan sonra Gemini sesi kendiliğinden geri gelir."
        } else {
            "Gemini kısa bir süre bekletiyor; bu arada telefon sesiyle okunuyor."
        }
        null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Kayit.error("Gemini sesi alınamadı", e)
        notice.value = "Gemini sesi alınamadı (${e.message}); telefon sesiyle devam ediliyor."
        null
    }

    private fun onSwitch(from: String, to: String) {
        notice.value = "Gemini ${GeminiTts.label(from)} sesinin bugünkü sınırı doldu; ${GeminiTts.label(to)} ile devam ediliyor."
    }

    // ------------------------------------------------------------------ Çalma

    private fun ensureLoop() {
        if (loopJob?.isActive == true) return
        val channel = groups
        loopJob = playScope.launch {
            for (g in channel) {
                val file = try { g.audio.await() } catch (e: Exception) { null }
                if (file != null && playGroup(file, g)) continue
                playLocal(g)
            }
        }
    }

    private fun applyParams(mp: MediaPlayer) {
        runCatching {
            if (mp.isPlaying) mp.playbackParams = PlaybackParams().setSpeed(rate).setPitch(pitch)
        }
    }

    /** Parçayı çalar; cümle başlangıçlarını karakter oranına göre süreye yayar. Çalamazsa false. */
    private suspend fun playGroup(file: File, g: Group): Boolean {
        val mp = MediaPlayer()
        player = mp
        val done = CompletableDeferred<Boolean>()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.path)
            mp.setOnCompletionListener { done.complete(true) }
            mp.setOnErrorListener { _, what, extra ->
                Kayit.log("Gemini sesi çalınamadı ($what/$extra)")
                done.complete(false)
                true
            }
            mp.prepare()
        } catch (e: Exception) {
            Kayit.error("Gemini sesi çalınamadı", e)
            mp.release()
            if (player === mp) player = null
            file.delete()
            return false
        }
        try {
            val duration = mp.duration.coerceAtLeast(1)
            val lens = g.items.map { it.second.length + 1 }
            val total = lens.sum().toDouble()
            val offsets = IntArray(g.items.size)
            var acc = 0
            for (i in g.items.indices) { offsets[i] = (duration * (acc / total)).toInt(); acc += lens[i] }

            var k = g.startAt
            if (offsets[k] > 0) mp.seekTo(offsets[k])
            mp.setVolume(volume, volume)
            mp.start()
            applyParams(mp)
            listener?.onStart(g.items[k].first)
            while (!done.isCompleted) {
                delay(120)
                val pos = runCatching { mp.currentPosition }.getOrDefault(0)
                while (k + 1 < g.items.size && pos >= offsets[k + 1]) {
                    listener?.onDone(g.items[k].first)
                    k++
                    listener?.onStart(g.items[k].first)
                }
            }
            val ok = done.await()
            if (!ok) return if (k > g.startAt) finishRest(g, k) else false
            // Kalan cümleleri (süre tahmini kaydıysa) sırayla bitir
            listener?.onDone(g.items[k].first)
            while (k + 1 < g.items.size) {
                k++
                listener?.onStart(g.items[k].first)
                listener?.onDone(g.items[k].first)
            }
            return true
        } finally {
            runCatching { mp.stop() }
            runCatching { mp.release() }
            if (player === mp) player = null
        }
    }

    /** Çalma yarıda bozulduysa kalan cümleleri telefon sesiyle oku. */
    private suspend fun finishRest(g: Group, from: Int): Boolean {
        listener?.onDone(g.items[from].first)
        if (from + 1 < g.items.size) playLocal(Group(g.items, from + 1, CompletableDeferred(null)))
        return true
    }

    // ------------------------------------------------------------------ Telefon sesine geri düşme

    private var local: AndroidTtsEngine? = null
    private val localWaits = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var fallbackWait: CompletableDeferred<Unit>? = null

    private fun localEngine(): AndroidTtsEngine {
        local?.let { return it }
        val e = AndroidTtsEngine(context, prefs.ttsEngine)
        e.listener = object : SpeechListener {
            override fun onStart(id: String) { listener?.onStart(id) }
            override fun onDone(id: String) { listener?.onDone(id); localWaits.remove(id)?.complete(Unit) }
            override fun onError(id: String, code: Int) { listener?.onError(id, code); localWaits.remove(id)?.complete(Unit) }
            override fun onRange(id: String, start: Int, end: Int) { listener?.onRange(id, start, end) }
        }
        local = e
        return e
    }

    private suspend fun playLocal(g: Group) {
        val e = localEngine()
        if (withTimeoutOrNull(10_000) { e.status.first { it != EngineStatus.Initializing } } != EngineStatus.Ready) {
            g.items.drop(g.startAt).forEach { listener?.onError(it.first, -3) }
            return
        }
        e.setRate(rate)
        e.setPitch(pitch)
        e.setVolume(volume)
        prefs.voiceName?.let { e.setVoice(it) }
        val last = g.items.last().first
        val wait = CompletableDeferred<Unit>()
        localWaits[last] = wait
        fallbackWait = wait
        for (i in g.startAt until g.items.size) e.speak(g.items[i].first, g.items[i].second, false)
        wait.await()
    }
}
