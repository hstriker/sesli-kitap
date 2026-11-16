@file:OptIn(ExperimentalMaterial3Api::class)

package com.seslikitap.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Translate
import com.seslikitap.container
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.seslikitap.data.Sozluk
import com.seslikitap.data.SozlukSonuc
import com.seslikitap.player.Chapter

/** Basılı tutulan cümle ve kelime. */
data class SentenceAction(val chapter: Chapter, val sentence: Int, val word: String)

private sealed interface Meaning {
    data object Idle : Meaning
    data object Loading : Meaning
    data class Found(val r: SozlukSonuc) : Meaning
    data class NotFound(val message: String) : Meaning
}

@Composable
fun SentenceActionsSheet(
    action: SentenceAction,
    onDismiss: () -> Unit,
    onQuote: () -> Unit,
    onPlayFrom: () -> Unit,
    foreign: Boolean = false,
) {
    var meaning by remember(action) { mutableStateOf<Meaning>(Meaning.Idle) }
    var lookup by remember(action) { mutableStateOf(false) }
    val player = androidx.compose.ui.platform.LocalContext.current.container.player
    var translating by remember(action) { mutableStateOf(false) }
    var translation by remember(action) { mutableStateOf<String?>(null) }

    LaunchedEffect(translating) {
        if (!translating) return@LaunchedEffect
        translation = try { player.translate(action.chapter, action.sentence) } catch (e: Exception) { e.message ?: "Çevrilemedi" }
        translating = false
    }

    LaunchedEffect(lookup) {
        if (!lookup) return@LaunchedEffect
        meaning = Meaning.Loading
        meaning = try {
            Sozluk.ara(action.word)?.let { Meaning.Found(it) }
                ?: Meaning.NotFound("\"${action.word}\" sözlükte bulunamadı.")
        } catch (e: Exception) {
            Meaning.NotFound("Sözlüğe ulaşılamadı (internet bağlantısını kontrol edin).")
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(
                "“${action.chapter.text(action.sentence)}”",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Alıntılara ekle") },
                leadingContent = { Icon(Icons.Default.FormatQuote, null) },
                modifier = Modifier.clickable(onClick = onQuote),
            )
            ListItem(
                headlineContent = { Text("Buradan dinle") },
                leadingContent = { Icon(Icons.Default.PlayArrow, null) },
                modifier = Modifier.clickable(onClick = onPlayFrom),
            )
            if (foreign) {
                ListItem(
                    headlineContent = { Text("Türkçesi") },
                    supportingContent = { Text("Cümlenin Türkçesini göster ve sesli oku") },
                    leadingContent = { Icon(Icons.Default.Translate, null) },
                    modifier = Modifier.clickable { if (translation == null) translating = true },
                )
                if (translating) CircularProgressIndicator(Modifier.padding(20.dp))
                translation?.let { t ->
                    Text(t, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    androidx.compose.material3.TextButton(
                        onClick = { player.speakAside(t) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) { Text("Sesli dinle") }
                }
            }
            if (action.word.length >= 2) {
                ListItem(
                    headlineContent = { Text("\"${action.word}\" ne demek?") },
                    supportingContent = { Text("TDK Güncel Türkçe Sözlük") },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                    modifier = Modifier.clickable { lookup = true },
                )
            }
            when (val m = meaning) {
                Meaning.Idle -> {}
                Meaning.Loading -> CircularProgressIndicator(Modifier.padding(20.dp))
                is Meaning.NotFound -> Text(m.message, modifier = Modifier.padding(20.dp))
                is Meaning.Found -> Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(m.r.madde, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    m.r.anlamlar.forEachIndexed { i, a ->
                        Text("${i + 1}. $a", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    }
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    var added by remember(m) { mutableStateOf(false) }
                    androidx.compose.material3.TextButton(enabled = !added, onClick = {
                        com.seslikitap.data.CalmStore.add(
                            ctx, com.seslikitap.data.CalmStore.VOCAB,
                            org.json.JSONObject().put("t", System.currentTimeMillis()).put("w", m.r.madde)
                                .put("m", m.r.anlamlar.firstOrNull() ?: "").put("b", player.state.value.bookTitle),
                        )
                        added = true
                    }) { Text(if (added) "Defterine eklendi ✓" else "Kelime defterime ekle") }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
