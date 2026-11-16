@file:OptIn(ExperimentalLayoutApi::class)

package com.seslikitap.ui.huzur

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.seslikitap.container
import com.seslikitap.data.CalmStore
import com.seslikitap.player.GentleWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sesle yazdırma (Android'in konuşma tanıma ekranı, Türkçe). */
@Composable
fun rememberDictation(onText: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(onText)
    }
    return {
        try {
            launcher.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Konuş, yazıya dökeyim…")
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Bu telefonda konuşma tanıma bulunamadı.", Toast.LENGTH_LONG).show()
        }
    }
}

private val dayFmt = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("tr"))

// ------------------------------------------------------------------ Akşam notu (ses kaydı)

@Composable
fun EveningNotePage() {
    val context = LocalContext.current
    val dir = remember { CalmStore.notesDir(context) }
    var files by remember { mutableStateOf(dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var current by remember { mutableStateOf<File?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf<File?>(null) }

    fun refresh() { files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList() }
    fun startRec() {
        val f = File(dir, SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date()) + ".m4a")
        try {
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(64_000)
            r.setOutputFile(f.path)
            r.prepare()
            r.start()
            recorder = r; current = f
        } catch (e: Exception) {
            Toast.makeText(context, "Kayıt başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    fun stopRec() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null; current = null
        refresh()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startRec() }
    DisposableEffect(Unit) { onDispose { recorder?.let { runCatching { it.stop() }; it.release() }; player?.release() } }

    PageColumn {
        Text(
            "Günün sonunda bir dakikalığına içinden geçenleri anlat. Kayıtlar yalnızca bu telefonda durur, kimseye gönderilmez.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (recorder == null) Button(onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRec()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        }, modifier = Modifier.padding(vertical = 12.dp)) { Text("🎙️ Kaydı başlat") }
        else Button(onClick = { stopRec() }, modifier = Modifier.padding(vertical = 12.dp)) { Text("⏹ Kaydı bitir") }
        files.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dayFmt.format(Date(f.lastModified())), modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    player?.release(); player = null
                    if (playing == f) { playing = null } else {
                        val p = MediaPlayer()
                        try {
                            p.setDataSource(f.path); p.prepare(); p.start()
                            p.setOnCompletionListener { playing = null }
                            player = p; playing = f
                        } catch (_: Exception) { p.release() }
                    }
                }) { Icon(if (playing == f) Icons.Default.Stop else Icons.Default.PlayArrow, "Dinle") }
                IconButton(onClick = { f.delete(); refresh() }) { Icon(Icons.Default.Delete, "Sil") }
            }
        }
    }
}

// ------------------------------------------------------------------ Geleceğe mektup

@Composable
fun LettersPage() {
    val context = LocalContext.current
    var letters by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var months by remember { mutableIntStateOf(6) }
    var open by remember { mutableStateOf<String?>(null) }
    val dictate = rememberDictation { text = if (text.isBlank()) it else "$text $it" }
    LaunchedEffect(Unit) { letters = withContext(Dispatchers.IO) { CalmStore.list(context, CalmStore.LETTERS) } }
    val fmt = remember { SimpleDateFormat("d MMMM yyyy", Locale("tr")) }
    val now = System.currentTimeMillis()

    fun save(list: List<JSONObject>) { letters = list; CalmStore.save(context, CalmStore.LETTERS, list) }

    PageColumn {
        Text("Bugünkü sana değil, ileriki sana yaz. Mektup seçtiğin güne kadar mühürlü kalır.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("Sevgili gelecekteki ben…") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 4)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 3, 6, 12).forEach { m -> FilterChip(selected = months == m, onClick = { months = m }, label = { Text(if (m == 12) "1 yıl" else "$m ay") }) }
            TextButton(onClick = dictate) { Text("🎤 Sesle yaz") }
        }
        Button(enabled = text.isNotBlank(), onClick = {
            val openAt = java.time.LocalDate.now().plusMonths(months.toLong()).atTime(9, 0)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            save(letters + JSONObject().put("id", System.nanoTime().toString()).put("t", now).put("open", openAt).put("text", text.trim()).put("opened", false))
            text = ""
            Toast.makeText(context, "Mektubun mühürlendi 💌", Toast.LENGTH_SHORT).show()
        }) { Text("Mühürle") }

        val due = letters.filter { it.optLong("open") <= now }
        val sealed = letters.filter { it.optLong("open") > now }
        if (sealed.isNotEmpty()) {
            Text("Mühürlü mektuplar", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            sealed.sortedBy { it.optLong("open") }.forEach {
                Text("💌 ${fmt.format(Date(it.optLong("t")))} tarihinde yazıldı · ${fmt.format(Date(it.optLong("open")))} tarihinde açılacak", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (due.isNotEmpty()) {
            Text("Açılmaya hazır", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            due.sortedByDescending { it.optLong("open") }.forEach { l ->
                val id = l.optString("id")
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("${fmt.format(Date(l.optLong("t")))} tarihinden bir mektup", style = MaterialTheme.typography.labelLarge)
                        if (open == id || l.optBoolean("opened")) Text(l.optString("text"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
                        else OutlinedButton(onClick = {
                            open = id
                            save(letters.map { o -> if (o.optString("id") == id) JSONObject(o.toString()).put("opened", true) else o })
                        }) { Text("Aç") }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Kapanış kelimeleri

@Composable
fun ClosingWordsPage() {
    val context = LocalContext.current
    var words by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    LaunchedEffect(Unit) { words = withContext(Dispatchers.IO) { CalmStore.list(context, CalmStore.WORDS) } }
    val year = java.time.Year.now().value
    val counts = remember(words) {
        words.filter { java.time.Instant.ofEpochMilli(it.optLong("t")).atZone(java.time.ZoneId.systemDefault()).year == year }
            .groupingBy { it.optString("w").trim().lowercase(Locale("tr")) }.eachCount()
            .filterKeys { it.isNotBlank() }.toList().sortedByDescending { it.second }
    }
    PageColumn {
        Text(
            "Dinlemeyi bitirip okuyucudan çıkarken \"bugün nasıl hissettirdi?\" sorusuna verdiğin tek kelimelik cevaplar. Büyük olanlar daha sık.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (counts.isEmpty()) Text("Henüz kelime yok.", modifier = Modifier.padding(top = 16.dp))
        val max = counts.maxOfOrNull { it.second } ?: 1
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 16.dp)) {
            counts.forEach { (w, n) ->
                Text(w, fontSize = (16 + 22 * n / max).sp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f + 0.5f * n / max))
            }
        }
    }
}

// ------------------------------------------------------------------ Nazik hatırlatıcı

@Composable
fun ReminderPage() {
    val context = LocalContext.current
    val prefs = context.container.prefs
    var on by remember { mutableStateOf(prefs.reminderOn) }
    var minute by remember { mutableIntStateOf(prefs.reminderMinute) }
    var every by remember { mutableIntStateOf(prefs.reminderEvery) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun apply() {
        prefs.reminderOn = on; prefs.reminderMinute = minute; prefs.reminderEvery = every
        GentleWorker.schedule(context, replace = true)
    }

    PageColumn {
        Text(
            "Seçtiğin saatte \"kendine biraz zaman ayırmak ister misin?\" diye sorar. O gün zaten dinlediysen hiç gelmez.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Text("Hatırlatıcı", modifier = Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = {
                on = it; apply()
                if (it && Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
            })
        }
        Text("Saat", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(18 * 60 + 30, 19 * 60 + 30, 20 * 60 + 30, 21 * 60 + 30, 22 * 60 + 30).forEach { m ->
                FilterChip(selected = minute == m, onClick = { minute = m; apply() }, label = { Text("%02d:%02d".format(m / 60, m % 60)) })
            }
        }
        Text("Sıklık", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "Her gün", 2 to "Gün aşırı", 7 to "Haftada bir").forEach { (n, l) ->
                FilterChip(selected = every == n, onClick = { every = n; apply() }, label = { Text(l) })
            }
        }
    }
}
