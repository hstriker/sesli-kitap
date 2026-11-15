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

    private val cacheDir = File(context.filesDir, "ses-gemini").apply { mkdirs() }
    /** Aynı parça için süren istek: duraklat/devam et aynı isteği bekler, yeni istek açmaz. */
    private val inflight = ConcurrentHashMap<String, Deferred<File?>>()
    private val requestLock = Mutex()

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

    private fun keyFor(text: String, voice: String, narrator: Boolean): String =
        MessageDigest.getInstance("SHA-1").digest("$voice|${if (narrator) GeminiTts.PROMPT_VERSION else "duz"}|$text".toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Parçanın sesi: önbellekte varsa o, yoksa Gemini'den; olmazsa null (telefon sesiyle okunur). */
    private fun audioFor(text: String): Deferred<File?> {
        val v = voice
        val narrator = prefs.geminiNarrator
        val key = keyFor(text, v, narrator)
        val f = File(cacheDir, "$key.wav")
        if (f.exists() && f.length() > 44) {
            f.setLastModified(System.currentTimeMillis())
            return CompletableDeferred(f)
        }
        inflight[key]?.let { return it }
        val d = ioScope.async {
            try {
                request(text, v, narrator, f)
            } finally {
                inflight.remove(key)
            }
        }
        inflight[key] = d
        return d
    }

    private suspend fun request(text: String, voice: String, narrator: Boolean, out: File): File? {
        val key = prefs.geminiKey
        if (key.isNullOrBlank()) {
            notice.value = "Gemini anahtarı yok; telefon sesiyle okunuyor. Ayarlar → Yapay zekâ özeti'nden ekleyin."
            return null
        }
        if (System.currentTimeMillis() < prefs.geminiBlockedUntil) return null
        return try {
            val tmp = File(cacheDir, out.name + ".tmp")
            // Dakikalık sınırlar (3 istek, 10 bin token) için istekler sırayla gider;
            // kısa bekletmede bir kez bekleyip yeniden denenir.
            val r = requestLock.withLock {
                try {
                    GeminiTts.synthesize(key, text, voice, narrator, tmp, prefs, ::onSwitch)
                } catch (e: GeminiTts.QuotaException) {
                    if (e.daily || e.retrySeconds > 90) throw e
                    Kayit.log("Gemini dakikalık sınır; ${e.retrySeconds} sn bekleniyor")
                    delay((e.retrySeconds + 2) * 1000L)
                    GeminiTts.synthesize(key, text, voice, narrator, tmp, prefs, ::onSwitch)
                }
            }
            val secs = (tmp.length() - 44) / 48_000.0
            val cps = text.length / secs.coerceAtLeast(1.0)
            Kayit.log("Gemini parça (${r.model}): ${text.length} karakter, ${secs.toInt()} sn, ${r.tokens} jeton, ${"%.1f".format(cps)} karakter/sn, bitiş=${r.finish}")
            if (!adaptGroupSize(text.length, r, cps)) {
                // Ses kesik: metnin sonu okunmamış. Bu parça telefon sesiyle okunur, sonrakiler kısalır.
                tmp.delete()
                notice.value = "Gemini bu parçanın sonunu kesti; parçalar kısaltıldı. Bu kısım telefon sesiyle okunuyor."
                return null
            }
            tmp.renameTo(out)
            trimCache()
            out
        } catch (e: GeminiTts.QuotaException) {
            val until = GeminiTts.recordQuota(prefs, e)
            if (until != null) {
                val local = until.withZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
                notice.value = "Gemini'nin bugünkü ücretsiz sınırı doldu; telefon sesine geçildi. Saat $local'dan sonra Gemini sesi kendiliğinden geri gelir."
            } else {
                notice.value = "Gemini kısa bir süre bekletiyor; bu arada telefon sesiyle okunuyor."
            }
            null
        } catch (e: Exception) {
            Kayit.error("Gemini sesi alınamadı", e)
            notice.value = "Gemini sesi alınamadı (${e.message}); telefon sesiyle devam ediliyor."
            null
        }
    }

    /**
     * Parça boyunu modelin tek seferlik ses tavanına (16.384 jeton) göre ayarlar: ne kadar uzun parça,
     * o kadar az istek → günlük sınırdan o kadar çok dinleme. Ses kesikse false.
     */
    private fun adaptGroupSize(chars: Int, r: GeminiTts.Result, cps: Double): Boolean {
        val cur = prefs.geminiGroupMax
        val truncated = r.finish == "MAX_TOKENS" || cps > 25.0
        if (truncated) {
            prefs.geminiGroupMax = (chars * 0.75).toInt().coerceIn(MIN_GROUP, MAX_GROUP)
            Kayit.log("Gemini sesi kesik; parça boyu ${prefs.geminiGroupMax}")
            return false
        }
        if (r.tokens > 0 && chars > 1000) {
            val perChar = r.tokens.toDouble() / chars
            val safe = (TOKEN_BUDGET / perChar).toInt().coerceIn(MIN_GROUP, MAX_GROUP)
            if (kotlin.math.abs(safe - cur) > cur * 0.1) {
                prefs.geminiGroupMax = safe
                Kayit.log("Gemini parça boyu $cur → $safe karakter")
            }
        }
        return true
    }

    private fun onSwitch(from: String, to: String) {
        notice.value = "Gemini ${GeminiTts.label(from)} sesinin bugünkü sınırı doldu; ${GeminiTts.label(to)} ile devam ediliyor."
    }

    private fun trimCache() {
        val files = cacheDir.listFiles { f -> f.name.endsWith(".wav") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.delete()
            if (total <= MAX_CACHE_BYTES * 0.8) break
        }
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

    companion object {
        private const val MAX_CACHE_BYTES = 400L * 1024 * 1024
        /** Ses jetonu tavanı 16.384; %88'i hedeflenir (okuma hızı parçadan parçaya değişir). */
        private const val TOKEN_BUDGET = 14_400.0
        private const val MIN_GROUP = 3000
        private const val MAX_GROUP = 12_000
    }
}
