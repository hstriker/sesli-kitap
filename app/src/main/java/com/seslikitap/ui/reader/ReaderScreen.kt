@file:OptIn(ExperimentalMaterial3Api::class)

package com.seslikitap.ui.reader

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seslikitap.container
import com.seslikitap.core.BlockKind
import com.seslikitap.player.Chapter
import com.seslikitap.player.EngineStatus
import com.seslikitap.player.formatDuration
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(bookId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val player = container.player
    val prefs = container.prefs
    val st by player.state.collectAsStateWithLifecycle()
    val chapter by player.chapter.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var sheet by remember { mutableStateOf<ReaderSheet?>(null) }
    var fontSize by remember { mutableStateOf(prefs.readerFontSize) }
    var follow by remember { mutableStateOf(true) }
    var actions by remember { mutableStateOf<SentenceAction?>(null) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(bookId) { player.open(bookId) }
    LaunchedEffect(st.error) {
        st.error?.let { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(st.finished) {
        if (st.finished) snackbar.showSnackbar("Kitap bitti. Tebrikler!")
    }

    fun playPause() {
        if (!st.playing && Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        follow = true
        player.toggle()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(st.bookTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (st.chapterTitle.isNotBlank()) {
                            Text(
                                st.chapterTitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Geri") }
                },
                actions = {
                    IconButton(onClick = { sheet = ReaderSheet.RECAP }) {
                        Icon(Icons.Default.History, "Nerede kalmıştık?")
                    }
                    IconButton(onClick = { sheet = ReaderSheet.TOC }) {
                        Icon(Icons.AutoMirrored.Filled.List, "İçindekiler")
                    }
                    IconButton(onClick = { sheet = ReaderSheet.BOOKMARKS }) {
                        Icon(Icons.Default.Bookmarks, "Yer imleri")
                    }
                    IconButton(onClick = { sheet = ReaderSheet.SETTINGS }) {
                        Icon(Icons.Default.Tune, "Ses ayarları")
                    }
                },
            )
        },
        bottomBar = {
            Controls(
                playing = st.playing,
                rate = st.rate,
                sentence = st.sentenceIndex,
                sentenceCount = st.sentenceCount,
                chapterIndex = st.chapterIndex,
                chapterCount = st.chapterCount,
                sleepActive = st.sleepAt != null || st.sleepAtChapterEnd,
                onPlayPause = ::playPause,
                onPrevSentence = { follow = true; player.previousSentence() },
                onNextSentence = { follow = true; player.nextSentence() },
                onPrevChapter = { follow = true; player.previousChapter() },
                onNextChapter = { follow = true; player.nextChapter() },
                onSpeed = { sheet = ReaderSheet.SETTINGS },
                onSleep = { sheet = ReaderSheet.SLEEP },
                onBookmark = {
                    player.addBookmark()
                    scope.launch { snackbar.showSnackbar("Yer imi eklendi") }
                },
                onReplay = { follow = true; player.replayParagraph() },
                remaining = remember(st.sentenceIndex, st.chapterIndex, st.rate, st.lengthsReady) { player.remainingMs() },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val ch = chapter
            when {
                st.loading || (ch == null && st.error == null) -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                ch == null -> {
                    Text(
                        st.error ?: "Kitap açılamadı",
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    )
                }
                else -> {
                    Column(Modifier.fillMaxSize()) {
                        EngineWarning(st.engine)
                        if (st.longAbsence) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                            ) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "Bu kitaba bir süredir ara vermişsin. Kısa bir özet ister misin?",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    TextButton(onClick = { player.dismissLongAbsence(); sheet = ReaderSheet.RECAP }) { Text("Özetle") }
                                    TextButton(onClick = { player.dismissLongAbsence() }) { Text("Gerek yok") }
                                }
                            }
                        }
                        if (st.finished && st.nextBookId != null) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                            ) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Kitap bitti 🎉 Serinin sıradaki kitabı:", style = MaterialTheme.typography.labelMedium)
                                        Text(st.nextBookTitle ?: "", style = MaterialTheme.typography.titleMedium)
                                    }
                                    Button(onClick = { follow = true; st.nextBookId?.let { player.open(it, autoPlay = true) } }) {
                                        Text("Başla")
                                    }
                                }
                            }
                        }
                        ChapterText(
                            chapter = ch,
                            current = st.sentenceIndex,
                            fontSize = fontSize,
                            follow = follow,
                            onUserScroll = { follow = false },
                            onSentenceTap = { i -> follow = true; player.seek(ch.index, i) },
                            onSentenceLongPress = { i, w -> actions = SentenceAction(ch, i, w) },
                        )
                    }
                    if (!follow) {
                        SmallFloatingActionButton(
                            onClick = { follow = true },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        ) { Icon(Icons.Default.MyLocation, "Okunan yere git") }
                    }
                }
            }
        }
    }

    actions?.let { a ->
        SentenceActionsSheet(
            action = a,
            onDismiss = { actions = null },
            onQuote = {
                player.addQuote(a.chapter, a.sentence)
                actions = null
                scope.launch { snackbar.showSnackbar("Alıntılara eklendi ✍️") }
            },
            onPlayFrom = { follow = true; player.seek(a.chapter.index, a.sentence); if (!st.playing) player.play(); actions = null },
        )
    }

    when (sheet) {
        ReaderSheet.TOC -> TocSheet(
            onDismiss = { sheet = null },
            onSelect = { chapterIdx, anchor ->
                follow = true
                player.seekToAnchor(chapterIdx, anchor)
                sheet = null
            },
        )
        ReaderSheet.BOOKMARKS -> BookmarksSheet(bookId = bookId, onDismiss = { sheet = null })
        ReaderSheet.SETTINGS -> SettingsSheet(
            fontSize = fontSize,
            onFontSize = { fontSize = it; prefs.readerFontSize = it },
            onDismiss = { sheet = null },
        )
        ReaderSheet.SLEEP -> SleepSheet(onDismiss = { sheet = null })
        ReaderSheet.RECAP -> RecapSheet(onDismiss = { sheet = null })
        null -> {}
    }
}

enum class ReaderSheet { TOC, BOOKMARKS, SETTINGS, SLEEP, RECAP }

@Composable
private fun EngineWarning(status: EngineStatus) {
    val context = LocalContext.current
    val (text, action, intentAction) = when (status) {
        EngineStatus.MissingTurkish -> Triple(
            "Telefonunuzda Türkçe ses verisi yüklü değil. Yükledikten sonra uygulamaya dönün.",
            "Türkçe sesi yükle",
            TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA,
        )
        is EngineStatus.Failed -> Triple(
            "Seslendirme motoru başlatılamadı. Ayarlardan \"Metin okuma çıktısı\" bölümünde bir motor seçin " +
                "(örn. Google Konuşma Hizmetleri).",
            "Ayarları aç",
            "com.android.settings.TTS_SETTINGS",
        )
        else -> return
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text)
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                try {
                    context.startActivity(Intent(intentAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    try {
                        context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {}
                }
            }) { Text(action) }
        }
    }
}

@Composable
private fun ChapterText(
    chapter: Chapter,
    current: Int,
    fontSize: Float,
    follow: Boolean,
    onUserScroll: () -> Unit,
    onSentenceTap: (Int) -> Unit,
    onSentenceLongPress: (Int, String) -> Unit,
) {
    val listState = rememberLazyListState()
    val currentBlock = chapter.sentences.getOrNull(current)?.blockIndex ?: 0
    val highlight = MaterialTheme.colorScheme.primaryContainer
    val onHighlight = MaterialTheme.colorScheme.onPrimaryContainer

    // Okunan paragrafı görünür tut
    LaunchedEffect(chapter.index, currentBlock, follow) {
        if (!follow) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val item = visible.firstOrNull { it.index == currentBlock }
        val viewport = listState.layoutInfo.viewportEndOffset
        val fullyVisible = item != null && item.offset >= 0 && item.offset + item.size <= viewport * 0.8
        if (!fullyVisible) listState.animateScrollToItem(currentBlock, scrollOffset = -viewport / 4)
    }
    // Kullanıcı kendisi kaydırırsa takibi bırak
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) onUserScroll()
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy((fontSize * 0.6f).dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(chapter.blocks) { bi, block ->
            val sentencesInBlock = remember(chapter, bi) {
                chapter.sentences.withIndex().filter { it.value.blockIndex == bi }
            }
            val text: AnnotatedString = if (bi == currentBlock) {
                val s = chapter.sentences.getOrNull(current)
                buildAnnotatedString {
                    append(block.text)
                    if (s != null) {
                        addStyle(SpanStyle(background = highlight, color = onHighlight), s.start, s.end)
                    }
                }
            } else AnnotatedString(block.text)
            var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
            val heading = block.kind == BlockKind.HEADING
            Text(
                text = text,
                fontSize = (if (heading) fontSize * 1.25f else fontSize).sp,
                lineHeight = (fontSize * 1.55f).sp,
                fontWeight = if (heading) FontWeight.SemiBold else FontWeight.Normal,
                onTextLayout = { layout = it },
                modifier = Modifier.fillMaxWidth().pointerInput(chapter.index, bi) {
                    detectTapGestures(
                        onTap = { pos ->
                            val offset = layout?.getOffsetForPosition(pos) ?: 0
                            val hit = sentencesInBlock.firstOrNull { offset < it.value.end } ?: sentencesInBlock.lastOrNull()
                            if (hit != null) onSentenceTap(hit.index)
                        },
                        onLongPress = { pos ->
                            val offset = layout?.getOffsetForPosition(pos) ?: 0
                            val hit = sentencesInBlock.firstOrNull { offset < it.value.end } ?: sentencesInBlock.lastOrNull()
                            if (hit != null) onSentenceLongPress(hit.index, wordAt(block.text, offset))
                        },
                    )
                },
            )
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun Controls(
    playing: Boolean,
    rate: Float,
    sentence: Int,
    sentenceCount: Int,
    chapterIndex: Int,
    chapterCount: Int,
    sleepActive: Boolean,
    onPlayPause: () -> Unit,
    onPrevSentence: () -> Unit,
    onNextSentence: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    onBookmark: () -> Unit,
    onReplay: () -> Unit,
    remaining: Pair<Long, Long?>,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (sentenceCount > 0) "Bölüm ${chapterIndex + 1}/$chapterCount · Cümle ${sentence + 1}/$sentenceCount" else "",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (sentenceCount > 0) {
                        Text(
                            "Bölüm: ${formatDuration(remaining.first)} kaldı" +
                                (remaining.second?.let { " · Kitap: ${formatDuration(it)}" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                AssistChip(onClick = onSpeed, label = { Text(formatRate(rate)) })
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onSleep) {
                    Icon(
                        Icons.Default.Bedtime, "Uyku zamanlayıcısı",
                        tint = if (sleepActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onBookmark) { Icon(Icons.Default.BookmarkAdd, "Yer imi ekle") }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrevChapter) { Icon(Icons.Default.SkipPrevious, "Önceki bölüm") }
                IconButton(onClick = onReplay) { Icon(Icons.Default.Replay, "Kaçırdım: paragrafı tekrarla") }
                IconButton(onClick = onPrevSentence) { Icon(Icons.Default.FastRewind, "Önceki cümle") }
                FilledIconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(),
                ) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (playing) "Duraklat" else "Dinle",
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = onNextSentence) { Icon(Icons.Default.FastForward, "Sonraki cümle") }
                IconButton(onClick = onNextChapter) { Icon(Icons.Default.SkipNext, "Sonraki bölüm") }
            }
        }
    }
}

fun formatRate(rate: Float): String {
    val r = Math.round(rate * 100) / 100.0
    return (if (r == Math.floor(r)) "${r.toInt()}.0" else r.toString()).replace('.', ',') + "x"
}

/** Paragraftaki konumun üzerindeki kelime. */
fun wordAt(text: String, offset: Int): String {
    if (text.isEmpty()) return ""
    var a = offset.coerceIn(0, text.length - 1)
    var b = a
    while (a > 0 && text[a - 1].isLetter()) a--
    while (b < text.length && text[b].isLetter()) b++
    return text.substring(a, b)
}
