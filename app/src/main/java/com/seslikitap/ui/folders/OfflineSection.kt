package com.seslikitap.ui.folders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.seslikitap.container
import com.seslikitap.data.BookEntity
import com.seslikitap.data.BookStatus
import com.seslikitap.data.DriveUri
import com.seslikitap.player.GeminiAudio
import com.seslikitap.player.GeminiBankWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Ayarlar → Biriktirme ve tatil modu. */
@Composable
fun OfflineSection() {
    val context = LocalContext.current
    val container = context.container
    val prefs = container.prefs
    var bank by remember { mutableStateOf(prefs.geminiBank) }
    var offline by remember { mutableStateOf(prefs.offlineBooks) }
    var books by remember { mutableStateOf<List<BookEntity>>(emptyList()) }
    var picking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val cacheMb = remember(status) { GeminiAudio.cacheBytes(context) / (1024 * 1024) }

    LaunchedEffect(Unit) {
        books = withContext(Dispatchers.IO) {
            container.db.books().all().filter { it.status == BookStatus.OK }
                .sortedWith(compareByDescending<BookEntity> { it.lastOpenedAt ?: 0L }.thenBy { it.title })
        }
    }

    fun save(list: List<Long>) {
        offline = list
        prefs.offlineBooks = list
        // Listeden çıkan kitapların dosyası artık silinebilir
        val keep = list.mapNotNull { id -> books.firstOrNull { it.id == id } }
            .filter { DriveUri.isDrive(it.uri) }.map { DriveUri.id(it.uri) }.toSet()
        prefs.pinnedDriveIds = keep
    }

    Column(Modifier.fillMaxWidth()) {
        Text("Biriktirme ve tatil modu", style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.fillMaxWidth().clickable { bank = !bank; prefs.geminiBank = bank }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Kullanılmayan Gemini hakkını biriktir")
                Text(
                    "Her sabah hak yenilenmeden önce (Wi-Fi'dayken) o gün kullanılmayan haklarla dinlediğin kitabın " +
                        "sıradaki bölümleri hazırlanıp telefona kaydedilir. Yoğun günlerde sınırdan fazlasını dinlersin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = bank, onCheckedChange = { bank = it; prefs.geminiBank = it })
        }
        prefs.geminiBankLast?.let {
            Text("Son: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "Telefonda hazır Gemini sesi: $cacheMb MB",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Tatil listesi", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
        Text(
            "Bu kitapların dosyası telefonda tutulur ve Gemini sesi her gün biriken haklarla önceden hazırlanır; " +
                "internetsiz yerde de dinlersin.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        offline.forEach { id ->
            val b = books.firstOrNull { it.id == id }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(b?.title ?: "…", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { save(offline - id) }) { Icon(Icons.Default.Close, "Listeden çıkar") }
            }
        }
        Row {
            TextButton(onClick = { picking = true }) { Text("Kitap ekle") }
            if (offline.isNotEmpty()) {
                OutlinedButton(onClick = {
                    GeminiBankWorker.runNow(context)
                    status = "Hazırlanıyor… (bugünün kalan hakkıyla; birkaç dakika sürebilir)"
                }) { Text("Şimdi hazırla") }
            }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }

    if (picking) {
        var selected by remember { mutableStateOf(offline.toSet()) }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Tatil listesine ekle") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(books.take(200), key = { it.id }) { b ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                selected = if (b.id in selected) selected - b.id else selected + b.id
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = b.id in selected, onCheckedChange = null)
                            Text(b.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    save(offline.filter { it in selected } + selected.filter { it !in offline })
                    picking = false
                }) { Text("Tamam") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Vazgeç") } },
        )
    }
}
