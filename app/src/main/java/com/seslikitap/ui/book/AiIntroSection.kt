package com.seslikitap.ui.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.seslikitap.container
import com.seslikitap.data.BookEntity
import com.seslikitap.player.BookAi
import kotlinx.coroutines.launch

/** Kitap sayfası: spoiler'sız sesli tanıtım ve yazar köşesi (Gemini; sonuç saklanır). */
@Composable
fun AiIntroSection(e: BookEntity) {
    val context = LocalContext.current
    val player = context.container.player
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun load(t: String, block: suspend () -> String) {
        title = t
        text = null
        busy = true
        scope.launch {
            text = try { block() } catch (ex: Exception) { ex.message ?: "Alınamadı" }
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { load("Spoiler'sız tanıtım") { BookAi.intro(context, e) } }, enabled = !busy) {
                Text("Kitabı tanıt")
            }
            if (e.author.isNotBlank()) {
                OutlinedButton(onClick = { load("Yazar köşesi: ${e.author}") { BookAi.author(context, e.author, e.title) } }, enabled = !busy) {
                    Text("Yazar hakkında")
                }
            }
        }
        if (title != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(title!!, style = MaterialTheme.typography.titleSmall)
                    if (busy) CircularProgressIndicator(Modifier.padding(12.dp))
                    text?.let { t ->
                        Text(t, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                        Row {
                            TextButton(onClick = { player.speakAside(t) }) { Text("Sesli dinle") }
                            TextButton(onClick = { title = null; text = null }) { Text("Kapat") }
                        }
                    }
                }
            }
        }
    }
}
