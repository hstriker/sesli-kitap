@file:OptIn(ExperimentalLayoutApi::class)

package com.seslikitap.ui.huzur

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.seslikitap.data.CalmStore
import com.seslikitap.player.AudioSession
import com.seslikitap.player.CalmAi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// ------------------------------------------------------------------ Sesli macera

private val SETTINGS = listOf(
    "Sisli bir adada kayıp bir fener", "Eski bir İstanbul konağında gizli bir oda", "Kuzey ışıkları altında bir kızak yolculuğu",
    "Denizin dibinde unutulmuş bir şehir", "Bir antikacıda konuşan bir pusula", "Yıldızlara giden sessiz bir uzay gemisi",
)

private class Step(val text: String, val a: String?, val b: String?, val end: Boolean)

private fun parseStep(raw: String): Step {
    val end = raw.contains("[SON]")
    val clean = raw.replace("[SON]", "")
    val text = clean.substringAfter("METİN:", clean.substringAfter("METIN:", clean)).substringBefore("\nA:").trim()
    val a = Regex("(?m)^A:\\s*(.+)$").find(clean)?.groupValues?.get(1)?.trim()
    val b = Regex("(?m)^B:\\s*(.+)$").find(clean)?.groupValues?.get(1)?.trim()
    return Step(text, a, b, end || a == null || b == null)
}

@Composable
fun AdventurePage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var setting by remember { mutableStateOf(SETTINGS.first()) }
    val steps = remember { mutableStateListOf<Step>() }
    val chosen = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var speak by remember { mutableStateOf(true) }

    fun run(block: suspend () -> String) {
        busy = true; error = null
        scope.launch {
            try {
                val s = parseStep(block())
                steps.add(s)
                if (speak) AudioSession.play(context, "Sesli macera", s.text + (if (!s.end) "\n\nSeçimin: birincisi, ${s.a}. İkincisi, ${s.b}." else ""))
            } catch (e: Exception) { error = e.message ?: "Hikâye alınamadı" } finally { busy = false }
        }
    }

    PageColumn {
        if (steps.isEmpty()) {
            Text("Bir dünya seç; hikâye senin seçimlerinle ilerlesin (6 bölüm).", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                SETTINGS.forEach { t -> FilterChip(selected = setting == t, onClick = { setting = t }, label = { Text(t) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = speak, onCheckedChange = { speak = it }); Text("Sesli oku")
            }
            Button(onClick = { run { CalmAi.ask(context, null, CalmAi.adventureStart(setting), 0.9) } }, enabled = !busy) { Text("Maceraya başla") }
        }
        steps.forEachIndexed { i, s ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(s.text, style = MaterialTheme.typography.bodyLarge)
                    chosen.getOrNull(i)?.let { Text("→ $it", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
                }
            }
        }
        val last = steps.lastOrNull()
        if (last != null && !busy) {
            if (last.end) {
                Text("Son. 🌙", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { steps.clear(); chosen.clear(); AudioSession.stop() }) { Text("Yeni macera") }
            } else if (chosen.size < steps.size) {
                listOfNotNull(last.a, last.b).forEach { opt ->
                    OutlinedButton(onClick = {
                        chosen.add(opt)
                        val story = steps.zip(chosen).joinToString("\n\n") { (st, c) -> st.text + "\n(Seçim: $c)" }
                        run { CalmAi.ask(context, null, CalmAi.adventureNext(story, opt, steps.size), 0.9) }
                    }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(opt) }
                }
            }
        }
        if (busy) CircularProgressIndicator(Modifier.padding(16.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

// ------------------------------------------------------------------ Hikâye ortaklığı

@Composable
fun CoWritePage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val parts = remember { mutableStateListOf<Pair<Boolean, String>>() } // (ben mi, metin)
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var open by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) { saved = withContext(Dispatchers.IO) { CalmStore.list(context, CalmStore.STORIES) } }

    PageColumn {
        Text(
            "Bir iki cümleyle başla; Gemini devam ettirsin, sonra sıra yine sende. Bitince kaydet ve baştan sona dinle.",
            style = MaterialTheme.typography.bodyMedium,
        )
        parts.forEach { (mine, t) ->
            Text(
                t,
                style = MaterialTheme.typography.bodyLarge,
                color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        OutlinedTextField(
            value = input, onValueChange = { input = it },
            placeholder = { Text(if (parts.isEmpty()) "Bir zamanlar…" else "Sıra sende…") },
            modifier = Modifier.fillMaxWidth(), minLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            Button(enabled = !busy && input.isNotBlank(), onClick = {
                parts.add(true to input.trim()); input = ""
                busy = true; error = null
                scope.launch {
                    try {
                        val story = parts.joinToString("\n\n") { it.second }
                        parts.add(false to CalmAi.ask(context, null, CalmAi.coWrite(story), 0.9))
                    } catch (e: Exception) { error = e.message } finally { busy = false }
                }
            }) { Text("Ekle") }
            if (parts.isNotEmpty()) {
                OutlinedButton(onClick = { AudioSession.play(context, "Ortak hikâye", parts.joinToString("\n\n") { it.second }) }) { Text("Dinle") }
                OutlinedButton(onClick = {
                    val text = parts.joinToString("\n\n") { it.second }
                    val o = JSONObject().put("t", System.currentTimeMillis()).put("title", text.take(40) + "…").put("text", text)
                    scope.launch {
                        withContext(Dispatchers.IO) { CalmStore.add(context, CalmStore.STORIES, o) }
                        saved = saved + o
                        parts.clear()
                    }
                }) { Text("Kaydet") }
            }
        }
        if (busy) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (saved.isNotEmpty()) {
            Text("Kaydettiğin hikâyeler", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            saved.reversed().forEach { o ->
                TextButton(onClick = { open = if (open === o) null else o }) { Text(o.optString("title")) }
                if (open === o) {
                    Text(o.optString("text"), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { AudioSession.play(context, "Hikâye", o.optString("text")) }) { Text("Dinle") }
                }
            }
        }
    }
}
