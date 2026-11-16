@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.seslikitap.ui.folders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seslikitap.data.Weather
import com.seslikitap.ui.theme.AppTheme
import kotlinx.coroutines.launch

/** Ayarlar → Görünüm ve hava: tema seçimi, havaya göre öneri için şehir. */
@Composable
fun LookSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by AppTheme.current.collectAsStateWithLifecycle()
    var city by remember { mutableStateOf(Weather.city(context)?.name) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Text("Görünüm", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTheme.NAMES.forEach { (k, l) -> FilterChip(selected = theme == k, onClick = { AppTheme.set(context, k) }, label = { Text(l) }) }
        }
        Text("Havaya göre öneri", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        Text(
            "Şehrini yazarsan ana sayfada havaya uygun kitap önerisi çıkar (Open-Meteo; konum izni istenmez).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (city != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Şehir: $city", modifier = Modifier.weight(1f))
            TextButton(onClick = { Weather.clearCity(context); city = null }) { Text("Kaldır") }
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Ör. İzmir") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(enabled = query.isNotBlank(), onClick = {
                status = "Aranıyor…"
                scope.launch {
                    val c = runCatching { Weather.setCity(context, query) }.getOrNull()
                    city = c?.name
                    status = if (c == null) "Şehir bulunamadı" else null
                }
            }) { Text("Kaydet") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
