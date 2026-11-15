@file:OptIn(ExperimentalMaterial3Api::class)

package com.seslikitap.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.seslikitap.AppContainer
import com.seslikitap.data.BookEntity
import com.seslikitap.ui.BookCover
import com.seslikitap.ui.appViewModel
import com.seslikitap.ui.home.formatMinutes
import com.seslikitap.ui.home.streakOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

data class DayBar(val label: String, val ms: Long, val isToday: Boolean)

data class StatsUi(
    val todayMs: Long = 0,
    val weekMs: Long = 0,
    val totalMs: Long = 0,
    val week: List<DayBar> = emptyList(),
    val streak: Int = 0,
    val bestStreak: Int = 0,
    val activeDays: Int = 0,
    val finishedCount: Int = 0,
    val inProgressCount: Int = 0,
    val bookCount: Int = 0,
    val topBooks: List<BookEntity> = emptyList(),
)

class StatsViewModel(container: AppContainer) : ViewModel() {
    private val tr = Locale("tr", "TR")

    val ui: StateFlow<StatsUi> = combine(container.library.listening, container.library.books) { days, books ->
        val byDay = days.associate { it.day to it.ms }
        val today = LocalDate.now()
        val week = (6 downTo 0).map { i ->
            val d = today.minusDays(i.toLong())
            DayBar(
                label = d.dayOfWeek.getDisplayName(TextStyle.SHORT, tr).take(3),
                ms = byDay[d.toString()] ?: 0L,
                isToday = i == 0,
            )
        }
        // En uzun seri
        var best = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in days.filter { it.ms >= 60_000L }.map { LocalDate.parse(it.day) }.sorted()) {
            run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
            best = maxOf(best, run)
            prev = d
        }
        StatsUi(
            todayMs = byDay[today.toString()] ?: 0L,
            weekMs = week.sumOf { it.ms },
            totalMs = days.sumOf { it.ms },
            week = week,
            streak = streakOf(days),
            bestStreak = best,
            activeDays = days.count { it.ms >= 60_000L },
            finishedCount = books.count { it.finished },
            inProgressCount = books.count { it.progress > 0f && !it.finished },
            bookCount = books.size,
            topBooks = books.filter { it.listenedMs > 60_000L }.sortedByDescending { it.listenedMs }.take(5),
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUi())
}

@Composable
fun StatsScreen(onOpenBook: (Long) -> Unit) {
    val vm = appViewModel { StatsViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("İstatistikler", fontWeight = FontWeight.SemiBold) }) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatBox("Bugün", formatMinutes(ui.todayMs), Modifier.weight(1f))
                    StatBox("Bu hafta", formatMinutes(ui.weekMs), Modifier.weight(1f))
                    StatBox("Toplam", formatMinutes(ui.totalMs), Modifier.weight(1f))
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Son 7 gün", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        WeekChart(ui.week)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatBox("Seri 🔥", "${ui.streak} gün", Modifier.weight(1f))
                    StatBox("En uzun seri", "${ui.bestStreak} gün", Modifier.weight(1f))
                    StatBox("Dinlenen gün", "${ui.activeDays}", Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatBox("Biten kitap ✅", "${ui.finishedCount}", Modifier.weight(1f))
                    StatBox("Devam eden", "${ui.inProgressCount}", Modifier.weight(1f))
                    StatBox("Kitaplık", "${ui.bookCount}", Modifier.weight(1f))
                }
            }
            if (ui.topBooks.isNotEmpty()) {
                item { Text("En çok dinlediklerin", style = MaterialTheme.typography.titleMedium) }
                items(ui.topBooks, key = { it.id }) { b ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenBook(b.id) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BookCover(b.coverPath, b.title, b.author, Modifier.width(44.dp).height(64.dp).clip(RoundedCornerShape(4.dp)))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                formatMinutes(b.listenedMs) + " · %${(b.progress * 100).toInt()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (ui.totalMs == 0L) {
                item {
                    Text(
                        "Kitap dinledikçe istatistiklerin burada görünecek.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun WeekChart(week: List<DayBar>) {
    val max = (week.maxOfOrNull { it.ms } ?: 0L).coerceAtLeast(10 * 60_000L)
    val barColor = MaterialTheme.colorScheme.primary
    val todayColor = MaterialTheme.colorScheme.secondary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val n = week.size
            val slot = size.width / n
            val barW = slot * 0.55f
            week.forEachIndexed { i, d ->
                val x = i * slot + (slot - barW) / 2
                drawRoundRect(track, Offset(x, 0f), Size(barW, size.height), CornerRadius(8f, 8f))
                val h = size.height * (d.ms.toFloat() / max)
                if (h > 0f) {
                    drawRoundRect(
                        if (d.isToday) todayColor else barColor,
                        Offset(x, size.height - h), Size(barW, h), CornerRadius(8f, 8f),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            week.forEach { d ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(d.label, style = MaterialTheme.typography.labelSmall, fontWeight = if (d.isToday) FontWeight.Bold else FontWeight.Normal)
                    Text("${d.ms / 60_000}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
