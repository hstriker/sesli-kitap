@file:OptIn(ExperimentalMaterial3Api::class)

package com.seslikitap.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.seslikitap.container
import com.seslikitap.player.BookAi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Kitaba sor: yalnızca dinlenen kısma dayanan sorular + "bana bir düşünce sorusu sor". */
@Composable
fun AskSheet(onDismiss: () -> Unit) {
    val player = LocalContext.current.container.player
    val scope = rememberCoroutineScope()
    val history = remember { mutableStateListOf<Pair<String, String>>() }
    var question by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun send(q: String, thought: Boolean = false) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val a = if (thought) player.thoughtQuestion(fresh = history.any { it.first == THOUGHT })
                else player.askBook(q, history.toList())
                history.add((if (thought) THOUGHT else q) to a)
                question = ""
            } catch (e: Exception) {
                error = e.message ?: "Yanıt alınamadı"
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
            Text("Kitaba sor", style = MaterialTheme.typography.titleLarge)
            Text(
                "Yanıtlar yalnızca şu ana kadar dinlediğin kısma dayanır; ileriyi açık etmez.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            history.forEach { (q, a) ->
                if (q != THOUGHT) {
                    Text(q, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        if (q == THOUGHT) Text("Düşünmen için", style = MaterialTheme.typography.labelMedium)
                        Text(a, style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { player.speakAside(a) }) { Text("Sesli dinle") }
                    }
                }
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp))
                Text("Düşünüyorum…")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    placeholder = { Text("Ör. Bu karakter kimdi?") },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { if (question.isNotBlank()) send(question.trim()) }, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Sor")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { send("", thought = true) }, enabled = !busy) { Text("Bana bir düşünce sorusu sor") }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

private const val THOUGHT = "\u0000dusunce"

/** Karakter listesi (okunan yere kadar). */
@Composable
fun CharactersSheet(onDismiss: () -> Unit) {
    val player = LocalContext.current.container.player
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var fresh by remember { mutableStateOf(false) }

    LaunchedEffect(fresh) {
        text = null
        error = null
        try { text = player.characters(fresh) } catch (e: Exception) { error = e.message ?: "Liste alınamadı" }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
            Text("Karakterler", style = MaterialTheme.typography.titleLarge)
            Text(
                "Kaldığın yere kadar tanıdığın kişiler; ileride çıkacaklar yok.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                text == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(end = 12.dp))
                    Text("Karakterler toplanıyor…")
                }
                else -> {
                    text!!.lines().filter { it.isNotBlank() }.forEach { line ->
                        val name = line.substringBefore("—").substringBefore(" - ").trim().trimStart('*', '-', '•', ' ')
                        val desc = line.substringAfter("—", line.substringAfter(" - ", "")).trim()
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            if (desc.isNotBlank()) Text(desc, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    TextButton(onClick = { fresh = true }) { Text("Yeniden oluştur") }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

/** Tek kişilik kitap kulübü: kitap bitince Gemini 4 soru sorar, sohbet edilir, sohbet saklanır. */
@Composable
fun ClubSheet(bookId: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val player = context.container.player
    val scope = rememberCoroutineScope()
    val transcript = remember { mutableStateListOf<Pair<String, String>>() }
    var answer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var previous by remember { mutableStateOf<String?>(null) }

    fun next() {
        busy = true
        error = null
        scope.launch {
            try {
                var reply = player.clubReply(transcript.toList())
                if (reply.contains("[BİTTİ]") || reply.contains("[BITTI]")) {
                    reply = reply.replace("[BİTTİ]", "").replace("[BITTI]", "").trim()
                    done = true
                }
                transcript.add(GEMINI to reply)
                if (done) withContext(Dispatchers.IO) {
                    val date = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale("tr")).format(java.util.Date())
                    BookAi.clubFile(context, bookId).writeText(
                        "Kitap kulübü — $date\n\n" + transcript.joinToString("\n\n") { "${it.first}: ${it.second}" }
                    )
                }
            } catch (e: Exception) {
                error = e.message ?: "Yanıt alınamadı"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        previous = withContext(Dispatchers.IO) { BookAi.clubFile(context, bookId).takeIf { it.exists() }?.readText() }
        if (previous == null) next()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
            Text("Kitap kulübü", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            if (previous != null && transcript.isEmpty() && !busy) {
                Text(previous!!, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { previous = null; next() }) { Text("Yeni bir sohbet başlat") }
            }
            transcript.forEach { (who, msg) ->
                val mine = who == ME
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = if (mine) 40.dp else 0.dp, end = if (mine) 0.dp else 40.dp, bottom = 10.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(msg, style = MaterialTheme.typography.bodyLarge)
                        if (!mine) TextButton(onClick = { player.speakAside(msg) }) { Text("Sesli dinle") }
                    }
                }
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp))
                Text("…")
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { next() }) { Text("Tekrar dene") }
            }
            if (!done && transcript.isNotEmpty() && !busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = answer,
                        onValueChange = { answer = it },
                        placeholder = { Text("Cevabın…") },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = {
                        if (answer.isNotBlank()) {
                            transcript.add(ME to answer.trim())
                            answer = ""
                            next()
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.Send, "Gönder") }
                }
            }
            if (done) Text(
                "Sohbet bu kitabın notu olarak saklandı.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

private const val GEMINI = "Kulüp"
private const val ME = "Ben"
