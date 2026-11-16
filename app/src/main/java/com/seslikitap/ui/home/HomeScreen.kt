@file:OptIn(ExperimentalMaterial3Api::class)

package com.seslikitap.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seslikitap.data.BookEntity
import com.seslikitap.ui.BookCover
import com.seslikitap.ui.appViewModel
import java.time.LocalTime

@Composable
fun HomeScreen(
    onOpenBook: (Long) -> Unit,
    onOpenReader: (Long) -> Unit,
    onOpenLibraryFolder: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenQuotes: () -> Unit,
    onOpenTool: (String, String?) -> Unit = { _, _ -> },
) {
    val vm = appViewModel { HomeViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(greeting(), fontWeight = FontWeight.SemiBold) }) },
    ) { padding ->
        if (ui.loaded && !ui.hasBooks) {
            EmptyHome(Modifier.padding(padding), onOpenSettings)
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item { GoalCard(ui.todayMs, ui.goalMs, ui.streak, ui.yearFinished, ui.yearGoal, ui.weekMs) }
            ui.dayQuote?.let { q ->
                item(key = "gunun-alintisi") {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onOpenQuotes)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Günün alıntısı", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text("“${q.text}”", style = MaterialTheme.typography.bodyLarge, maxLines = 6, overflow = TextOverflow.Ellipsis)
                            Text("— ${q.bookTitle}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            item(key = "hava") { WeatherCard(onOpenTool) }
            item { MoodCard(ui.continueBook, onContinue = { id -> ui.continueBook?.let { vm.continueListening(it) }; onOpenReader(id) }, onTool = onOpenTool) }
            ui.neglected?.let { b ->
                item(key = "unutulan-${b.id}") {
                    NeglectedCard(
                        b,
                        onContinue = { onOpenReader(b.id) },
                        onShelve = { vm.shelveLater(b) },
                        onLater = { vm.snoozeNudge(b) },
                    )
                }
            }

            ui.continueBook?.let { b ->
                item {
                    ContinueCard(
                        b,
                        onPlay = { vm.continueListening(b); onOpenReader(b.id) },
                        onOpen = { onOpenBook(b.id) },
                    )
                }
            }
            if (ui.readingList.isNotEmpty()) {
                item { BookRow("Okuma listem 📋", ui.readingList, onOpenBook) }
            }
            if (ui.inProgress.isNotEmpty()) {
                item { BookRow("Yarım kalanlar", ui.inProgress, onOpenBook) }
            }
            item { ToolsRow(onOpenTool) }
            if (ui.favorites.isNotEmpty()) {
                item { BookRow("Favorilerim ❤️", ui.favorites, onOpenBook) }
            }
            ui.shelves.forEach { (name, books) ->
                item(key = "raf-$name") { BookRow("📚 $name", books, onOpenBook) }
            }
            ui.suggestion?.let { s ->
                item { SuggestionCard(s, onOpen = { onOpenBook(s.id) }, onAnother = { vm.nextSuggestion() }) }
            }
            if (ui.folders.size > 1) {
                item {
                    SectionTitle("Klasörlerin")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(ui.folders, key = { it.name }) { f ->
                            FolderCard(f, onClick = { vm.openFolder(f.name); onOpenLibraryFolder() })
                        }
                    }
                }
            }
            if (ui.recentlyAdded.isNotEmpty()) {
                item { BookRow("Son eklenenler", ui.recentlyAdded, onOpenBook) }
            }
            if (ui.finished.isNotEmpty()) {
                item { BookRow("Bitirdiklerin ✅", ui.finished, onOpenBook) }
            }
            if (ui.quotes.isNotEmpty()) {
                item {
                    SectionTitle("Alıntılarım ✍️") { TextButton(onClick = onOpenQuotes) { Text("Tümü") } }
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(ui.quotes, key = { it.id }) { q ->
                            Card(Modifier.width(260.dp).clickable(onClick = onOpenQuotes)) {
                                Column(Modifier.padding(14.dp)) {
                                    Text("“${q.text}”", style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "— ${q.bookTitle}", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun greeting(): String {
    val h = LocalTime.now().hour
    return when (h) {
        in 5..11 -> "Günaydın ☀️"
        in 12..17 -> "İyi günler"
        in 18..22 -> "İyi akşamlar 🌙"
        else -> "İyi geceler 🌙"
    }
}

fun formatMinutes(ms: Long): String {
    val min = ms / 60_000L
    return if (min < 60) "$min dk" else "${min / 60} sa ${min % 60} dk"
}

@Composable
private fun SectionTitle(text: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
private fun GoalCard(todayMs: Long, goalMs: Long, streak: Int, yearFinished: Int, yearGoal: Int, weekMs: Long = 0) {
    val progress = if (goalMs > 0) (todayMs.toFloat() / goalMs).coerceIn(0f, 1f) else 0f
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(64.dp),
                    strokeWidth = 7.dp,
                    trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                )
                Text("%${(progress * 100).toInt()}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                // Baskısız dil: kaçırılan hedef yok, kendine ayırdığın zaman var
                Text(
                    if (todayMs < 60_000L) "Bugün kendine biraz zaman ayırmaya ne dersin?"
                    else "Bugün kendine ${formatMinutes(todayMs)} ayırdın",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (progress >= 1f) "Günlük niyetini gerçekleştirdin 🌿"
                    else if (weekMs >= 60_000L) "Bu hafta kendine ${formatMinutes(weekMs)} ayırdın"
                    else "Niyetin: günde ${formatMinutes(goalMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "${java.time.Year.now().value}: $yearFinished kitapla yol arkadaşlığı ettin",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (streak > 0) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.LocalFireDepartment, null, tint = MaterialTheme.colorScheme.secondary)
                    Text("$streak gün", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(book: BookEntity, onPlay: () -> Unit, onOpen: () -> Unit) {
    Column {
        SectionTitle("Kaldığın yerden devam et")
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onOpen),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(6.dp), shadowElevation = 4.dp) {
                    BookCover(
                        book.coverPath, book.title, book.author,
                        Modifier.width(84.dp).height(122.dp).clip(RoundedCornerShape(6.dp)),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (book.author.isNotBlank()) {
                        Text(
                            book.author, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { book.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "%${(book.progress * 100).toInt()} tamamlandı" +
                            if (book.listenedMs > 60_000) " · ${formatMinutes(book.listenedMs)} dinlendi" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = onPlay, modifier = Modifier.size(56.dp), shape = CircleShape) {
                    Icon(Icons.Default.PlayArrow, "Devam et", modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

@Composable
private fun BookRow(title: String, books: List<BookEntity>, onOpenBook: (Long) -> Unit) {
    Column {
        SectionTitle(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(books, key = { it.id }) { b -> SmallBook(b, onClick = { onOpenBook(b.id) }) }
        }
    }
}

@Composable
private fun SmallBook(book: BookEntity, onClick: () -> Unit) {
    Column(Modifier.width(104.dp).clickable(onClick = onClick)) {
        Box {
            Surface(shape = RoundedCornerShape(6.dp), shadowElevation = 2.dp) {
                BookCover(
                    book.coverPath, book.title, book.author,
                    Modifier.width(104.dp).height(152.dp).clip(RoundedCornerShape(6.dp)),
                )
            }
            if (book.progress > 0f && !book.finished) {
                LinearProgressIndicator(
                    progress = { book.progress },
                    modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(book.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SuggestionCard(book: BookEntity, onOpen: () -> Unit, onAnother: () -> Unit) {
    Column {
        SectionTitle("Bugün ne dinlesem?") {
            TextButton(onClick = onAnother) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Başka öner")
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onOpen),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                BookCover(
                    book.coverPath, book.title, book.author,
                    Modifier.width(64.dp).height(94.dp).clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Henüz başlamadığın bir kitap", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodySmall)
                    if (book.relativePath.isNotBlank()) {
                        Text(
                            "📁 " + book.relativePath.trimEnd('/').replace("/", " › "),
                            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderCard(f: HomeFolder, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.width(140.dp).height(88.dp).clickable(onClick = onClick),
    ) {
        Box {
            if (f.coverPath != null) {
                BookCover(
                    f.coverPath, f.name, "",
                    Modifier.fillMaxSize().graphicsLayer { alpha = 0.22f },
                )
            }
            Column(Modifier.padding(12.dp)) {
                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Spacer(Modifier.height(4.dp))
                Text(
                    f.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    "${f.count} kitap", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun EmptyHome(modifier: Modifier, onOpenSettings: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("📚", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text("Sesli Kitap'a hoş geldin", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Başlamak için kitaplarının bulunduğu klasörü ekle: Google Drive'dan ya da telefondan.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onOpenSettings) { Text("Klasör ekle") }
    }
}
