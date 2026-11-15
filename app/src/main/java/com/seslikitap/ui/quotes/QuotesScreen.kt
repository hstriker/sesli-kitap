@file:OptIn(ExperimentalMaterial3Api::class)

package com.seslikitap.ui.quotes

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seslikitap.container
import com.seslikitap.data.QuoteEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun QuotesScreen(onBack: () -> Unit, onOpenBook: (Long) -> Unit) {
    val context = LocalContext.current
    val repo = context.container.library
    val quotes by repo.quotes.collectAsStateWithLifecycle(initialValue = emptyList())
    val fmt = SimpleDateFormat("d MMMM yyyy", Locale("tr"))

    fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Alıntıyı paylaş"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Alıntılarım (${quotes.size})") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") } },
                actions = {
                    if (quotes.isNotEmpty()) {
                        IconButton(onClick = { share(quotes.joinToString("\n\n") { q -> format(q) }) }) {
                            Icon(Icons.Default.Share, "Hepsini paylaş")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (quotes.isEmpty()) {
            Text(
                "Henüz alıntı yok.\nOkurken bir cümleye basılı tutup \"Alıntılara ekle\"yi seçin.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            )
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(quotes, key = { it.id }) { q ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("“${q.text}”", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                TextButton(onClick = { onOpenBook(q.bookId) }) { Text("— ${q.bookTitle}") }
                                Text(
                                    (if (q.author.isNotBlank()) "${q.author} · " else "") + fmt.format(Date(q.createdAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                            IconButton(onClick = { share(format(q)) }) { Icon(Icons.Default.Share, "Paylaş") }
                            IconButton(onClick = { repo.deleteQuote(q.id) }) { Icon(Icons.Default.Delete, "Sil") }
                        }
                    }
                }
            }
        }
    }
}

private fun format(q: QuoteEntity) =
    "“${q.text}”\n— ${q.bookTitle}" + if (q.author.isNotBlank()) ", ${q.author}" else ""
