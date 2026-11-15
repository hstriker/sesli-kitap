@file:OptIn(ExperimentalMaterial3Api::class)

package com.seslikitap.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GridView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import com.seslikitap.container
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seslikitap.data.BookEntity
import com.seslikitap.data.BookStatus
import com.seslikitap.ui.BookCover
import com.seslikitap.ui.appViewModel

@Composable
fun LibraryScreen(
    onOpenBook: (Long) -> Unit,
    onOpenReader: (Long) -> Unit,
    onOpenFolders: () -> Unit,
    onAddDrive: () -> Unit,
) {
    val vm = appViewModel { LibraryViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val player = LocalContext.current.container.player
    val playerState by player.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var sortMenu by remember { mutableStateOf(false) }

    val browseMode by vm.browseMode.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler(enabled = ui.browsing && ui.path.isNotEmpty()) { vm.up() }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addFolder(uri)
    }

    LaunchedEffect(scan.lastMessage) {
        scan.lastMessage?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Kitaplığım")
                        if (ui.totalCount > 0) {
                            Text(
                                "${ui.totalCount} kitap",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sırala")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            SortOrder.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label) },
                                    leadingIcon = { RadioButton(selected = s == sort, onClick = null) },
                                    onClick = { vm.setSort(s); sortMenu = false },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { vm.setBrowse(!browseMode) }) {
                        Icon(
                            if (browseMode) Icons.Default.GridView else Icons.Default.AccountTree,
                            contentDescription = if (browseMode) "Tüm kitapları göster" else "Klasörlere göre göster",
                        )
                    }
                    IconButton(onClick = { vm.rescan() }, enabled = !scan.running) {
                        Icon(Icons.Default.Refresh, contentDescription = "Yeniden tara")
                    }
                    IconButton(onClick = onOpenFolders) {
                        Icon(Icons.Default.Folder, contentDescription = "Klasörler")
                    }
                },
            )
        },
        bottomBar = {
            if (playerState.active) {
                MiniPlayer(
                    title = playerState.bookTitle,
                    subtitle = playerState.chapterTitle,
                    coverPath = playerState.coverPath,
                    author = playerState.author,
                    playing = playerState.playing,
                    onClick = { playerState.bookId?.let(onOpenReader) },
                    onToggle = { player.toggle() },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (scan.running) {
                ScanProgress(scan.phase, scan.processed, scan.total)
            }
            val noFolders = folders?.isEmpty() == true
            when {
                noFolders -> EmptyState(onPick = { pickFolder.launch(null) }, onDrive = onAddDrive)
                !ui.loaded -> {}
                else -> {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { vm.query.value = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        placeholder = { Text("Başlık, yazar ya da dosya adı ara") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Default.Clear, "Temizle") }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LibraryFilter.entries.forEach { f ->
                            if (f == LibraryFilter.PROBLEMS && ui.problemCount == 0) return@forEach
                            FilterChip(
                                selected = f == filter,
                                onClick = { vm.filter.value = f },
                                label = {
                                    Text(if (f == LibraryFilter.PROBLEMS) "${f.label} (${ui.problemCount})" else f.label)
                                },
                            )
                        }
                    }
                    if (ui.browsing && ui.path.isNotEmpty()) {
                        Breadcrumb(ui.path, onGo = { vm.goToLevel(it) })
                    }
                    if (ui.books.isEmpty() && ui.folders.isEmpty()) {
                        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (ui.totalCount == 0) {
                                    if (scan.running) "Kitaplar aranıyor…\n${scan.phase}" else "Seçilen klasörlerde EPUB bulunamadı."
                                } else "Aramanızla eşleşen kitap yok.",
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        BookGrid(ui.folders, ui.books, showPath = !ui.browsing, onOpenFolder = { vm.openFolder(it) }, onOpenBook = onOpenBook)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanProgress(phase: String, processed: Int, total: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (total > 0) {
            LinearProgressIndicator(
                progress = { (processed.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(4.dp))
        Text(phase, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyState(onPick: () -> Unit, onDrive: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.CreateNewFolder, null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("Kitaplığınız boş", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "EPUB kitaplarınızın bulunduğu klasörü seçin: Google Drive'da ya da telefonda. Alt klasörler de taranır.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onDrive) { Text("Google Drive'dan seç") }
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.OutlinedButton(onClick = onPick) { Text("Telefondan klasör seç") }
    }
}

@Composable
private fun Breadcrumb(path: List<String>, onGo: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.TextButton(onClick = { onGo(-1) }) { Text("Kitaplığım") }
        path.forEachIndexed { i, name ->
            Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(18.dp))
            androidx.compose.material3.TextButton(onClick = { onGo(i) }, enabled = i < path.size - 1) {
                Text(name, maxLines = 1)
            }
        }
    }
}

@Composable
private fun BookGrid(
    folders: List<FolderTile>,
    books: List<BookEntity>,
    showPath: Boolean,
    onOpenFolder: (String) -> Unit,
    onOpenBook: (Long) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(folders, key = { "d:" + it.name }) { f ->
            FolderTileView(f, onClick = { onOpenFolder(f.name) })
        }
        items(books, key = { "b:" + it.id }) { book ->
            BookTile(book, showPath, onClick = { onOpenBook(book.id) })
        }
    }
}

@Composable
private fun FolderTileView(f: FolderTile, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth().aspectRatio(0.68f),
        ) {
            Box {
                if (f.coverPath != null) {
                    // Klasördeki bir kitabın kapağı, soluk arka plan olarak
                    BookCover(
                        f.coverPath, f.name, "",
                        Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                            .graphicsLayer { alpha = 0.35f },
                    )
                }
                Column(
                    Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.Folder, null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${f.bookCount} kitap",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BookTile(book: BookEntity, showPath: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box {
            Surface(
                shape = RoundedCornerShape(6.dp),
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.68f),
            ) {
                BookCover(
                    book.coverPath, book.title, book.author,
                    Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
                )
            }
            if (book.status != BookStatus.OK) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(bottomStart = 6.dp, topEnd = 6.dp),
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(
                        if (book.status == BookStatus.DRM) Icons.Default.Lock else Icons.Default.Warning,
                        contentDescription = "Sorunlu kitap",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(4.dp).size(16.dp),
                    )
                }
            }
            if (book.progress > 0f) {
                LinearProgressIndicator(
                    progress = { book.progress },
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).height(3.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (book.author.isNotBlank()) {
            Text(
                book.author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showPath && book.relativePath.isNotBlank()) {
            Text(
                "📁 " + book.relativePath.trimEnd('/').replace("/", " › "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MiniPlayer(
    title: String,
    subtitle: String,
    coverPath: String?,
    author: String,
    playing: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp, modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(
                coverPath, title, author,
                Modifier.width(40.dp).height(58.dp).clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onToggle) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Duraklat" else "Dinle")
            }
        }
    }
}
