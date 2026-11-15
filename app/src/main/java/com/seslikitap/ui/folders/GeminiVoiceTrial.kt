package com.seslikitap.ui.folders

import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.seslikitap.container
import com.seslikitap.player.GeminiTts
import kotlinx.coroutines.launch
import java.io.File

private const val ORNEK_METIN =
    "Kapı yavaşça açıldığında içerisi karanlıktı. Ayşe bir an durdu, sonra fısıldadı: \"Burada biri var mı?\" " +
        "Cevap gelmedi. Yalnızca uzaktan, 19. yüzyıldan kalma saat kulesinin çanları duyuluyordu."

/** Ayarlar → "Gemini sesiyle dene": bir paragrafı Gemini sesiyle dinletir (deneme). */
@Composable
fun GeminiVoiceTrialSection() {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()

    fun bookSample(): String? {
        val ch = container.player.chapter.value ?: return null
        if (ch.sentences.isEmpty()) return null
        val start = container.player.state.value.sentenceIndex.coerceIn(0, ch.sentences.size - 1)
        val sb = StringBuilder()
        var i = start
        while (i < ch.sentences.size && sb.length < 600) { sb.append(ch.text(i)).append(' '); i++ }
        return sb.toString().trim().ifBlank { null }
    }

    var text by remember { mutableStateOf(bookSample() ?: ORNEK_METIN) }
    val prefs = container.prefs
    var voice by remember { mutableStateOf(prefs.geminiVoice) }
    var narrator by remember { mutableStateOf(prefs.geminiNarrator) }
    var enabled by remember { mutableStateOf(container.player.usingGeminiVoice) }
    var menuOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val mp = remember { arrayOfNulls<MediaPlayer>(1) }

    fun stopAudio() {
        mp[0]?.runCatching { stop(); release() }
        mp[0] = null
    }
    DisposableEffect(Unit) { onDispose { stopAudio() } }

    Column {
        Text("Gemini sesi", style = MaterialTheme.typography.titleSmall)
        Text(
            "Kitaplar Gemini'nin doğal sesiyle okunur (yukarıdaki anahtarla, ücretsiz). Ücretsiz kotanın günlük sınırı " +
                "dolunca telefon sesine geçilir; ertesi gün Gemini sesi kendiliğinden geri gelir. Dinlenen kısımlar " +
                "telefonda saklanır, tekrar dinlemek kotadan yemez.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().clickable { enabled = !enabled; container.player.setGeminiVoice(enabled) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Kitapları Gemini sesiyle oku", modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it; container.player.setGeminiVoice(it) })
        }
        val (todayChars, todayReqs) = remember(status, enabled) { prefs.geminiToday() }
        val blockedUntil = remember(status, enabled) { prefs.geminiBlockedUntil }
        Text(
            buildString {
                append("Bugün: ${todayReqs} istek, ~${todayChars / 900} dk ses")
                if (blockedUntil > System.currentTimeMillis()) {
                    append(" · Sınır doldu, ")
                    append(java.text.SimpleDateFormat("HH:mm", java.util.Locale("tr")).format(java.util.Date(blockedUntil)))
                    append("'dan sonra yenilenir")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Dene", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { bookSample()?.let { text = it } ?: run { text = ORNEK_METIN } }) { Text("Kitaptan al") }
            TextButton(onClick = { text = ORNEK_METIN }) { Text("Örnek metin") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ses:")
            Column {
                TextButton(onClick = { menuOpen = true }) {
                    Text(GeminiTts.VOICES.firstOrNull { it.first == voice }?.second ?: voice)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    GeminiTts.VOICES.forEach { (name, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            voice = name; menuOpen = false
                            prefs.geminiVoice = name; container.player.geminiSettingsChanged()
                        })
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = narrator, onCheckedChange = {
                narrator = it; prefs.geminiNarrator = it; container.player.geminiSettingsChanged()
            })
            Text("Anlatıcı tonu (sakin, sıcak)")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !busy && text.isNotBlank(),
                onClick = {
                    val key = container.prefs.geminiKey
                    if (key.isNullOrBlank()) { status = "Önce yukarıya Gemini anahtarını kaydet."; return@Button }
                    busy = true
                    status = "Hazırlanıyor…"
                    stopAudio()
                    if (container.player.state.value.playing) container.player.pause()
                    scope.launch {
                        try {
                            val out = File(context.cacheDir, "gemini-deneme.wav")
                            val r = GeminiTts.synthesize(key, text.trim(), voice, narrator, out)
                            prefs.addGeminiUsage(text.trim().length.toLong())
                            val p = MediaPlayer()
                            p.setDataSource(r.file.absolutePath)
                            p.setOnCompletionListener { status = "Bitti. (${text.trim().length} karakter, hazırlanma ${r.millis / 1000.0} sn, model: ${r.model})" }
                            p.prepare()
                            p.start()
                            mp[0] = p
                            status = "Çalıyor… (hazırlanma ${r.millis / 1000.0} sn)"
                        } catch (e: Exception) {
                            status = e.message ?: "Hata"
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Hazırlanıyor…" else "Dinle") }
            OutlinedButton(onClick = { stopAudio(); status = null }, modifier = Modifier.padding(start = 8.dp)) { Text("Durdur") }
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
