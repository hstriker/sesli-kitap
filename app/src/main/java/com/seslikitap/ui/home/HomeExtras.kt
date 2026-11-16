@file:OptIn(ExperimentalLayoutApi::class)

package com.seslikitap.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.seslikitap.data.BookEntity

/** "Bugün nasılsın?": ruh hâline göre öneriler. */
@Composable
fun MoodCard(
    continueBook: BookEntity?,
    onContinue: (Long) -> Unit,
    onTool: (String, String?) -> Unit,
) {
    var mood by rememberSaveable { mutableStateOf<String?>(null) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Bugün nasılsın?", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MOODS.forEach { (m, _) ->
                    FilterChip(selected = mood == m, onClick = { mood = if (mood == m) null else m }, label = { Text(m) })
                }
            }
            val m = mood ?: return@Column
            Text(MOODS.first { it.first == m }.second, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (continueBook != null) {
                    OutlinedButton(onClick = { onContinue(continueBook.id) }) { Text("Kaldığım kitaba dön") }
                }
                OutlinedButton(onClick = { onTool("recommend", m.lowercase()) }) { Text("Bana göre bir kitap") }
                if (m == "Yorgun" || m == "Uykusuz") {
                    OutlinedButton(onClick = { onTool("weekend", null) }) { Text("Kısa bir kitap") }
                }
            }
        }
    }
}

private val MOODS = listOf(
    "Yorgun" to "Yorulmuşsun. Kısa, yormayan bir şey iyi gelebilir; istersen sakin ekranla dinle.",
    "Gergin" to "Biraz yavaşlayalım. Ritüelle başlamak (okuyucuda ⋮ → Ritüelle başla) iyi gelebilir.",
    "Keyifli" to "Güzel! Yeni bir maceraya başlamak için iyi bir gün.",
    "Hüzünlü" to "Sıcak, umut veren bir hikâye sana eşlik etsin.",
    "Uykusuz" to "Sakin ekranı açıp uyku zamanlayıcısıyla dinlemeyi dene.",
    "Meraklı" to "Merakına göre yeni bir kitap ya da yazar keşfedelim.",
)

/** Bir süredir dokunulmayan yarım kitap için nazik soru. */
@Composable
fun NeglectedCard(book: BookEntity, onContinue: () -> Unit, onShelve: () -> Unit, onLater: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "\"${book.title}\" bir süredir seni bekliyor (%${(book.progress * 100).toInt()}). Devam etmek ister misin, yoksa şimdilik rafa mı kaldıralım?",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row {
                TextButton(onClick = onContinue) { Text("Devam et") }
                TextButton(onClick = onShelve) { Text("Rafa kaldır") }
                TextButton(onClick = onLater) { Text("Sonra") }
            }
        }
    }
}

/** Kitaplık araçları kısayolları. */
@Composable
fun ToolsRow(onTool: (String, String?) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        AssistChip(onClick = { onTool("recommend", null) }, label = { Text("✨ Bana kitap öner") })
        AssistChip(onClick = { onTool("readinglist", null) }, label = { Text("📋 Okuma listem") })
        AssistChip(onClick = { onTool("weekend", null) }, label = { Text("⏱️ Hafta sonuna yetecekler") })
        AssistChip(onClick = { onTool("series", null) }, label = { Text("📚 Serilerim") })
        AssistChip(onClick = { onTool("journal", null) }, label = { Text("📔 Kitap günlüğüm") })
        AssistChip(onClick = { onTool("duplicates", null) }, label = { Text("🔁 Çift kitaplar") })
    }
}

/** Havaya göre öneri (Ayarlar'da şehir seçildiyse). */
@Composable
fun WeatherCard(onTool: (String, String?) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val city = androidx.compose.runtime.remember { com.seslikitap.data.Weather.city(context) } ?: return
    var now by androidx.compose.runtime.remember { mutableStateOf<com.seslikitap.data.Weather.Now?>(null) }
    androidx.compose.runtime.LaunchedEffect(city) { now = com.seslikitap.data.Weather.now(context) }
    val n = now ?: return
    val (desc, mood) = com.seslikitap.data.Weather.describe(n.code)
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("${city.name}: $desc, ${n.temp.toInt()}°", style = MaterialTheme.typography.titleSmall)
            Text("Bugünün havasına yakışan bir kitap ister misin?", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onTool("recommend", mood) }) { Text("Havaya göre öner") }
        }
    }
}
