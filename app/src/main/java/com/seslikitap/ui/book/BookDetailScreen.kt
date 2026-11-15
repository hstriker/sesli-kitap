@file:OptIn(ExperimentalMaterial3Api::class)

package com.seslikitap.ui.book

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seslikitap.data.BookStatus
import com.seslikitap.ui.BookCover
import com.seslikitap.ui.appViewModel
import com.seslikitap.ui.formatSize

@Composable
fun BookDetailScreen(bookId: Long, onBack: () -> Unit) {
    val vm = appViewModel(key = "book-$bookId") { BookDetailViewModel(it, bookId) }
    val entity by vm.entity.collectAsStateWithLifecycle()
    val book by vm.book.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(entity?.title ?: "", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
                },
            )
        },
    ) { padding ->
        val e = entity
        if (e == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Row(Modifier.padding(16.dp)) {
                    Surface(shape = RoundedCornerShape(6.dp), shadowElevation = 3.dp) {
                        BookCover(
                            e.coverPath, e.title, e.author,
                            Modifier.width(110.dp).height(160.dp).clip(RoundedCornerShape(6.dp)),
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = MaterialTheme.typography.titleLarge)
                        if (e.author.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(e.author, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(8.dp))
                        val info = buildList {
                            if (e.chapterCount > 0) add("${e.chapterCount} bölüm")
                            add(formatSize(e.sizeBytes))
                            e.language?.let { add("Dil: $it") }
                        }.joinToString(" · ")
                        Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            e.relativePath + e.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Icon(Icons.Default.Headphones, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Dinle (bir sonraki sürümde)")
                }
            }
            val err = loadError ?: if (e.status == BookStatus.DRM) e.errorMessage else null
            if (err != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Bu kitapta sorun var", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(err)
                        }
                    }
                }
            }
            val b = book
            if (b == null && err == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            if (b != null) {
                b.description?.let { d ->
                    item {
                        Text(
                            d, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp), maxLines = 8,
                        )
                    }
                }
                item {
                    Text(
                        "İçindekiler",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                    Text(
                        "Bir bölüme dokunarak seslendirilecek metnin önizlemesini görebilirsiniz.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                items(b.toc) { t ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clickable { vm.showPreview(t.spineIndex, t.title) }
                            .padding(start = (16 + t.depth * 16).dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
                    ) {
                        Text(t.title, style = if (t.depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider()
                }
            }
        }

        preview?.let { p ->
            ModalBottomSheet(
                onDismissRequest = { vm.closePreview() },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(p.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            p.error != null -> p.error
                            p.sentences.isEmpty() -> "Yükleniyor…"
                            else -> "${p.sentences.size} cümle"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                LazyColumn(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(p.sentences) { i, s ->
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                "${i + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(36.dp),
                            )
                            Text(s, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    item { Spacer(Modifier.size(32.dp)) }
                }
            }
        }
    }
}
