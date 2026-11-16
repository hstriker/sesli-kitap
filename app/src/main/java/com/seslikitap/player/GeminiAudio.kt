package com.seslikitap.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.seslikitap.Kayit
import com.seslikitap.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Gemini sesinin telefondaki deposu ve parça mantığı. Oynatıcı ([GeminiTtsEngine]) ile
 * önceden hazırlayan iş ([GeminiBankWorker]) aynı parçaları, aynı anahtarlarla kullanır:
 * bir parça bir kez seslendirilir, günlük haktan bir kez düşer.
 */
object GeminiAudio {

    /** Parça boyu sınırları (karakter); asıl boy [Prefs.geminiGroupMax], modelin ses tavanına göre ayarlanır. */
    const val MIN_GROUP = 3000
    const val MAX_GROUP = 12_000
    /** Ses jetonu tavanı 16.384; %88'i hedeflenir (okuma hızı parçadan parçaya değişir). */
    private const val TOKEN_BUDGET = 14_400.0
    /** Sıkıştırılmış (AAC) sesle ~70 saat. */
    private const val MAX_CACHE_BYTES = 1_500L * 1024 * 1024

    /** Dakikalık sınırlar (3 istek, 10 bin jeton) için istekler sırayla gider. */
    private val lock = Mutex()

    class NoKeyException : IOException("Gemini anahtarı yok")
    /** Kota dolu olduğu bilindiği için istek gönderilmedi. */
    class BlockedException : IOException("Gemini şu an bekletiyor")
    /** Gemini metnin sonunu kesti; parça boyu küçültüldü. */
    class TruncatedException : IOException("Gemini sesi kesik")

    fun dir(context: Context) = File(context.filesDir, "ses-gemini").apply { mkdirs() }

    /** İki konuşmacılı metinde diyalog sesi anahtara girer; tek konuşmacılıda girmez (önbellek boşa gitmesin). */
    fun key(text: String, voice: String, narrator: Boolean, dialogVoice: String? = null): String {
        val v = if (dialogVoice != null && GeminiTts.isDialogText(text)) "$voice+$dialogVoice" else voice
        return MessageDigest.getInstance("SHA-1").digest("$v|${if (narrator) GeminiTts.PROMPT_VERSION else "duz"}|$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /** Önbellekteki ses (AAC ya da eski WAV), yoksa null. */
    fun cached(context: Context, text: String, voice: String, narrator: Boolean, dialogVoice: String? = null): File? {
        val k = key(text, voice, narrator, dialogVoice)
        val d = dir(context)
        for (f in listOf(File(d, "$k.m4a"), File(d, "$k.wav"))) {
            if (f.exists() && f.length() > 44) {
                f.setLastModified(System.currentTimeMillis())
                return f
            }
        }
        return null
    }

    fun cacheBytes(context: Context): Long =
        dir(context).listFiles { f -> f.name.endsWith(".m4a") || f.name.endsWith(".wav") }?.sumOf { it.length() } ?: 0L

    /**
     * Parçayı seslendirip depoya yazar. Hata durumunda: [NoKeyException], [BlockedException],
     * [TruncatedException], [GeminiTts.QuotaException] (bütün modellerin günlük sınırı doldu ya da uzun bekleme).
     */
    suspend fun synthesize(
        context: Context, prefs: Prefs, text: String, voice: String, narrator: Boolean,
        onSwitch: ((String, String) -> Unit)? = null, dialogVoice: String? = null,
    ): File {
        val apiKey = prefs.geminiKey
        if (apiKey.isNullOrBlank()) throw NoKeyException()
        return lock.withLock {
            cached(context, text, voice, narrator, dialogVoice)?.let { return@withLock it }
            if (System.currentTimeMillis() < prefs.geminiBlockedUntil) throw BlockedException()
            val k = key(text, voice, narrator, dialogVoice)
            val d = dir(context)
            val wav = File(d, "$k.wav.tmp")
            val r = try {
                GeminiTts.synthesize(apiKey, text, voice, narrator, wav, prefs, onSwitch, dialogVoice = dialogVoice)
            } catch (e: GeminiTts.QuotaException) {
                if (e.daily || e.retrySeconds > 90) throw e
                Kayit.log("Gemini dakikalık sınır; ${e.retrySeconds} sn bekleniyor")
                delay((e.retrySeconds + 2) * 1000L)
                GeminiTts.synthesize(apiKey, text, voice, narrator, wav, prefs, onSwitch, dialogVoice = dialogVoice)
            }
            val rate = wavRate(wav)
            val secs = (wav.length() - 44) / (rate * 2.0)
            val cps = text.length / secs.coerceAtLeast(1.0)
            Kayit.log("Gemini parça (${r.model}): ${text.length} karakter, ${secs.toInt()} sn, ${r.tokens} jeton, ${"%.1f".format(cps)} karakter/sn, bitiş=${r.finish}")
            if (!adaptGroupSize(prefs, text.length, r, cps)) {
                wav.delete()
                throw TruncatedException()
            }
            // Sıkıştır (WAV'ın ~onda biri); olmazsa WAV olarak sakla
            val out = File(d, "$k.m4a")
            val tmp = File(d, "$k.m4a.tmp")
            val result = if (AacEncoder.encode(wav, rate, tmp) && tmp.renameTo(out)) {
                wav.delete()
                out
            } else {
                tmp.delete()
                File(d, "$k.wav").also { wav.renameTo(it) }
            }
            trim(context)
            result
        }
    }

    /**
     * Parça boyunu modelin tek seferlik ses tavanına göre ayarlar: ne kadar uzun parça, o kadar az istek
     * → günlük sınırdan o kadar çok dinleme. Ses kesikse false.
     */
    private fun adaptGroupSize(prefs: Prefs, chars: Int, r: GeminiTts.Result, cps: Double): Boolean {
        val cur = prefs.geminiGroupMax
        if (r.finish == "MAX_TOKENS" || cps > 25.0) {
            prefs.geminiGroupMax = (chars * 0.75).toInt().coerceIn(MIN_GROUP, MAX_GROUP)
            Kayit.log("Gemini sesi kesik; parça boyu ${prefs.geminiGroupMax}")
            return false
        }
        if (r.tokens > 0 && chars > 1000) {
            val safe = (TOKEN_BUDGET / (r.tokens.toDouble() / chars)).toInt().coerceIn(MIN_GROUP, MAX_GROUP)
            if (kotlin.math.abs(safe - cur) > cur * 0.1) {
                prefs.geminiGroupMax = safe
                Kayit.log("Gemini parça boyu $cur → $safe karakter")
            }
        }
        return true
    }

    private fun trim(context: Context) {
        val files = dir(context).listFiles { f -> f.name.endsWith(".m4a") || f.name.endsWith(".wav") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.delete()
            if (total <= MAX_CACHE_BYTES * 0.8) break
        }
    }

    private fun wavRate(f: File): Int = try {
        RandomAccessFile(f, "r").use { r ->
            r.seek(24)
            val b = ByteArray(4)
            r.readFully(b)
            (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) or ((b[2].toInt() and 0xff) shl 16) or ((b[3].toInt() and 0xff) shl 24)
        }.takeIf { it in 8000..96000 } ?: 24000
    } catch (_: Exception) { 24000 }

    // ------------------------------------------------------------------ Parçalar

    /**
     * Bölümü eşit boylu parçalara böler (en fazla [max] karakter, mümkünse paragraf sonunda): bölüm sonunda
     * kısa bir artık parça kalıp günlük hak boşa gitmesin. Her parçanın son cümlesinin sırası döner.
     */
    fun groupEnds(ch: Chapter, max: Int): IntArray {
        if (ch.sentences.isEmpty()) return IntArray(0)
        val len = IntArray(ch.sentences.size) { ch.sentences[it].let { s -> s.end - s.start } + 1 }
        val total = len.sum()
        val n = ((total + max - 1) / max).coerceAtLeast(1)
        val target = total / n
        val ends = ArrayList<Int>()
        var acc = 0
        for (i in ch.sentences.indices) {
            acc += len[i]
            val last = i == ch.sentences.size - 1
            val paragraphEnd = last || ch.sentences[i + 1].blockIndex != ch.sentences[i].blockIndex
            val next = if (last) 0 else len[i + 1]
            if (last || acc + next > max || (acc >= target && paragraphEnd)) { ends.add(i); acc = 0 }
        }
        return ends.toIntArray()
    }

    /**
     * Parçanın seslendirilecek metni (oynatıcı ve biriktirme aynı metni üretmeli: önbellek anahtarı buna bağlı).
     * [dialog]: diyalog sesi açıksa ve parçada konuşma varsa, satırlar konuşmacı adlarıyla yazılır.
     */
    fun groupText(ch: Chapter, start: Int, end: Int, normalize: Boolean, dialog: Boolean = false): String {
        val hasDialog = dialog && (start..end).any { ch.dialog.getOrElse(it) { false } }
        if (!hasDialog) return (start..end).joinToString(" ") { spokenTextOf(ch, it, normalize) }
        val sb = StringBuilder()
        var current: Boolean? = null
        for (i in start..end) {
            val d = ch.dialog.getOrElse(i) { false }
            if (d != current) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(if (d) GeminiTts.SPEAKER_DIALOG else GeminiTts.SPEAKER_NARRATOR).append(": ")
                current = d
            } else sb.append(' ')
            sb.append(spokenTextOf(ch, i, normalize))
        }
        return sb.toString()
    }
}

/** WAV (16 bit tek kanal) → AAC (.m4a). Gemini sesi telefonda ~10 kat az yer kaplasın. */
object AacEncoder {
    fun encode(wav: File, rate: Int, out: File): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        return try {
            val pcm = wav.readBytes().let { it.copyOfRange(44.coerceAtMost(it.size), it.size) }
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 48_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec = c
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            val m = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            var track = -1
            var started = false
            var offset = 0
            var inputDone = false
            var ptsUs = 0L
            val info = MediaCodec.BufferInfo()
            var guard = 0
            while (guard++ < 2_000_000) {
                if (!inputDone) {
                    val idx = c.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = c.getInputBuffer(idx)!!
                        buf.clear()
                        val n = (minOf(buf.remaining(), pcm.size - offset) / 2) * 2
                        if (n <= 0) {
                            c.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(pcm, offset, n)
                            c.queueInputBuffer(idx, 0, n, ptsUs, 0)
                            offset += n
                            ptsUs += (n / 2) * 1_000_000L / rate
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = m.addTrack(c.outputFormat)
                    m.start()
                    started = true
                } else if (o >= 0) {
                    val ob = c.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        m.writeSampleData(track, ob, info)
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            c.stop()
            if (started) m.stop()
            started && out.length() > 0
        } catch (e: Exception) {
            Kayit.error("AAC'ye çevrilemedi", e)
            false
        } finally {
            runCatching { codec?.release() }
            runCatching { muxer?.release() }
        }
    }
}
