package com.seslikitap.ui.book

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import com.seslikitap.container
import com.seslikitap.data.Journal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Kitap sayfası: okuma listesine ekle + kitap günlüğü (tarihler, puan, not). */
@Composable
fun JournalSection(bookId: Long) {
    val context = LocalContext.current
    val prefs = context.container.prefs
    var inList by remember { mutableStateOf(bookId in prefs.readingList) }
    var entry by remember { mutableStateOf(Journal.Entry()) }
    var note by remember { mutableStateOf("") }
    var noteSaved by remember { mutableStateOf(true) }
    LaunchedEffect(bookId) {
        entry = withContext(Dispatchers.IO) { Journal.get(context, bookId) }
        note = entry.note
    }
    val fmt = remember { java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale("tr")) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedButton(onClick = {
            inList = !inList
            prefs.readingList = if (inList) prefs.readingList + bookId else prefs.readingList - bookId
        }) {
            Icon(if (inList) Icons.Default.PlaylistAddCheck else Icons.AutoMirrored.Filled.PlaylistAdd, null)
            Text(if (inList) "  Okuma listende" else "  Okuma listesine ekle")
        }
        Text("Kitap günlüğü", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        val dates = listOfNotNull(
            entry.started?.let { "Başladın: ${fmt.format(java.util.Date(it))}" },
            entry.finished?.let { "Bitirdin: ${fmt.format(java.util.Date(it))}" },
        )
        if (dates.isNotEmpty()) Text(dates.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            (1..5).forEach { n ->
                IconButton(onClick = {
                    val r = if (entry.rating == n) 0 else n
                    entry = entry.copy(rating = r)
                    Journal.update(context, bookId) { it.copy(rating = r) }
                }) {
                    Icon(if (n <= entry.rating) Icons.Default.Star else Icons.Default.StarBorder, "$n yıldız", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it; noteSaved = false },
            placeholder = { Text("Bu kitap sana ne hissettirdi?") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        if (!noteSaved) TextButton(onClick = {
            Journal.update(context, bookId) { it.copy(note = note.trim()) }
            noteSaved = true
        }) { Text("Notu kaydet") }
    }
}
