package com.seslikitap.player

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** TTS motorunun hazır olma durumu. */
sealed interface EngineStatus {
    data object Initializing : EngineStatus
    data object Ready : EngineStatus
    /** Türkçe ses verisi yüklü değil. */
    data object MissingTurkish : EngineStatus
    data class Failed(val message: String) : EngineStatus
}

/**
 * Android TextToSpeech sarmalayıcısı. Konuşma işlerini kimliklerle (utteranceId) sıraya koyar,
 * başlama/bitme olaylarını dinleyiciye iletir. Ses motoru ileride Piper gibi başka bir motorla
 * değiştirilebilsin diye uygulamanın geri kalanı yalnızca [SpeechEngine] arayüzünü kullanır.
 */
interface SpeechEngine {
    val status: StateFlow<EngineStatus>
    var listener: SpeechListener?
    /** [alternate]: diyalog cümlesi; ayarlıysa ikinci sesle okunur. */
    fun speak(id: String, text: String, flush: Boolean, alternate: Boolean = false)
    /** Diyaloglar için ikinci ses (null: kapalı). */
    fun setDialogVoice(name: String?) {}
    fun stop()
    fun setRate(rate: Float)
    fun setPitch(pitch: Float)
    fun voices(): List<VoiceInfo>
    fun setVoice(name: String?)
    /** Ses seviyesi 0..1 (uyku zamanlayıcısında yavaşça kısmak için). */
    fun setVolume(volume: Float) {}
    /** Yüklü TTS uygulamaları: paket adı -> görünen ad. */
    fun engines(): List<Pair<String, String>> = emptyList()
    fun shutdown()
}

/** Cümleleri uzun parçalar hâlinde seslendiren motor (Gemini): her istek bir parça. */
interface GroupSpeechEngine : SpeechEngine {
    /** [items]: parçanın bütün cümleleri (kimlik, metin); [startAt]: çalmaya başlanacak cümlenin sırası. */
    fun speakGroup(items: List<Pair<String, String>>, startAt: Int, flush: Boolean)
}

interface SpeechListener {
    fun onStart(id: String)
    fun onDone(id: String)
    fun onError(id: String, code: Int)
    /** Okunan kelimenin cümle içindeki aralığı. */
    fun onRange(id: String, start: Int, end: Int) {}
}

data class VoiceInfo(val name: String, val label: String, val needsNetwork: Boolean, val quality: Int)

class AndroidTtsEngine(context: Context, enginePackage: String? = null) : SpeechEngine {

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Initializing)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()
    override var listener: SpeechListener? = null

    private val turkish = Locale("tr", "TR")
    private var tts: TextToSpeech? = null

    init {
        val onInit = TextToSpeech.OnInitListener { code ->
            val t = tts
            if (code != TextToSpeech.SUCCESS || t == null) {
                _status.value = EngineStatus.Failed("Seslendirme motoru başlatılamadı")
                return@OnInitListener
            }
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) { listener?.onStart(utteranceId) }
                override fun onDone(utteranceId: String) { listener?.onDone(utteranceId) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) { listener?.onError(utteranceId, -1) }
                override fun onError(utteranceId: String, errorCode: Int) { listener?.onError(utteranceId, errorCode) }
                override fun onStop(utteranceId: String, interrupted: Boolean) {}
                override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                    listener?.onRange(utteranceId, start, end)
                }
            })
            val r = t.setLanguage(turkish)
            _status.value = if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                EngineStatus.MissingTurkish
            } else {
                EngineStatus.Ready
            }
        }
        tts = if (enginePackage != null) TextToSpeech(context.applicationContext, onInit, enginePackage)
        else TextToSpeech(context.applicationContext, onInit)
    }

    @Volatile private var volume = 1f

    override fun setVolume(volume: Float) { this.volume = volume }

    private var mainVoiceName: String? = null
    private var dialogVoiceName: String? = null

    override fun setDialogVoice(name: String?) { dialogVoiceName = name }

    override fun speak(id: String, text: String, flush: Boolean, alternate: Boolean) {
        val t = tts ?: return
        // Ses, speak() çağrısı anında isteğe eklenir; diyalog için geçici olarak değiştirip geri alıyoruz.
        val wanted = if (alternate && dialogVoiceName != null) dialogVoiceName else mainVoiceName
        if (wanted != null) {
            if (t.voice?.name != wanted) {
                runCatching { t.voices?.firstOrNull { it.name == wanted } }.getOrNull()?.let { t.voice = it }
            }
        } else if (dialogVoiceName != null && t.voice?.name == dialogVoiceName) {
            // Ana ses "varsayılan Türkçe" ise diyalog sesinden varsayılana dön
            t.setLanguage(turkish)
        }
        val params = Bundle()
        if (volume < 1f) params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        val r = t.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, id)
        if (r != TextToSpeech.SUCCESS) {
            Log.w(TAG, "speak başarısız: $r")
            listener?.onError(id, r)
        }
    }

    override fun stop() {
        tts?.stop()
    }

    override fun setRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }

    override fun setPitch(pitch: Float) {
        tts?.setPitch(pitch)
    }

    override fun voices(): List<VoiceInfo> {
        val list: Set<Voice> = try { tts?.voices ?: emptySet() } catch (e: Exception) { emptySet() }
        return list.filter { it.locale.language == "tr" && !it.features.orEmpty().contains("notInstalled") }
            .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { -it.quality }, { it.name }))
            .mapIndexed { i, v ->
                VoiceInfo(
                    name = v.name,
                    label = "Ses ${i + 1}" + (if (v.isNetworkConnectionRequired) " (internet)" else "") +
                        when {
                            v.quality >= Voice.QUALITY_VERY_HIGH -> " · çok yüksek kalite"
                            v.quality >= Voice.QUALITY_HIGH -> " · yüksek kalite"
                            else -> ""
                        },
                    needsNetwork = v.isNetworkConnectionRequired,
                    quality = v.quality,
                )
            }
    }

    override fun setVoice(name: String?) {
        mainVoiceName = name
        val t = tts ?: return
        if (name == null) {
            t.setLanguage(turkish)
            return
        }
        val v = try { t.voices?.firstOrNull { it.name == name } } catch (e: Exception) { null }
        if (v != null) t.voice = v else t.setLanguage(turkish)
    }

    override fun engines(): List<Pair<String, String>> =
        try { tts?.engines?.map { it.name to it.label } ?: emptyList() } catch (e: Exception) { emptyList() }

    override fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val TAG = "AndroidTtsEngine"
    }
}
