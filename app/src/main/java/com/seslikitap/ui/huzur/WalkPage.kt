package com.seslikitap.ui.huzur

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seslikitap.container
import com.seslikitap.data.CalmStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Yürüyüş modu: yürürken kitap çalar; adım, süre ve dinlenen bölüm sayılır. */
@Composable
fun WalkPage() {
    val context = LocalContext.current
    val player = context.container.player
    val st by player.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var startedAt by remember { mutableStateOf<Long?>(null) }
    var startChapter by remember { mutableStateOf(0) }
    var stepBase by remember { mutableStateOf<Float?>(null) }
    var steps by remember { mutableLongStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var summary by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    val sm = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val sensor = remember { sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }

    LaunchedEffect(Unit) { history = withContext(Dispatchers.IO) { CalmStore.list(context, CalmStore.WALKS) } }
    LaunchedEffect(startedAt) { while (startedAt != null) { now = System.currentTimeMillis(); delay(1000) } }

    val listener = remember {
        object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val v = e.values.firstOrNull() ?: return
                if (stepBase == null) stepBase = v
                steps = (v - (stepBase ?: v)).toLong()
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
    }
    DisposableEffect(Unit) { onDispose { sm.unregisterListener(listener) } }

    fun begin() {
        summary = null
        startedAt = System.currentTimeMillis()
        startChapter = st.chapterIndex
        stepBase = null; steps = 0
        sensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        scope.launch { if (!player.state.value.playing) { if (player.state.value.active) player.play() else player.resumeLast() } }
    }
    fun end() {
        val start = startedAt ?: return
        sm.unregisterListener(listener)
        val ms = System.currentTimeMillis() - start
        val chapters = (player.state.value.chapterIndex - startChapter).coerceAtLeast(0)
        val min = ms / 60_000
        summary = "$min dakika yürüdün" + (if (sensor != null) ", $steps adım attın" else "") +
            (if (chapters > 0) " ve $chapters bölüm dinledin." else ".") + " 🌿"
        val o = JSONObject().put("t", start).put("ms", ms).put("steps", steps).put("chapters", chapters).put("book", player.state.value.bookTitle)
        history = history + o
        scope.launch(Dispatchers.IO) { CalmStore.add(context, CalmStore.WALKS, o) }
        startedAt = null
        player.pause()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { begin() }

    PageColumn {
        Text("Yürüyüşe çıkarken başlat: kitabın kaldığın yerden çalar, adımların ve süren sayılır.", style = MaterialTheme.typography.bodyMedium)
        val s = startedAt
        if (s == null) {
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                else begin()
            }, modifier = Modifier.padding(vertical = 12.dp)) { Text("🚶 Yürüyüşe başla") }
        } else {
            val sec = (now - s) / 1000
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("%d:%02d".format(sec / 60, sec % 60), style = MaterialTheme.typography.displaySmall)
                    if (sensor != null) Text("$steps adım", style = MaterialTheme.typography.titleMedium)
                    else Text("Bu telefonda adım sayar yok; yalnızca süre tutuluyor.", style = MaterialTheme.typography.bodySmall)
                    Text(st.bookTitle, style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedButton(onClick = { end() }) { Text("Yürüyüşü bitir") }
        }
        summary?.let { Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp)) }
        if (history.isNotEmpty()) {
            Text("Yürüyüşlerin", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
            val fmt = SimpleDateFormat("d MMM, HH:mm", Locale("tr"))
            history.reversed().take(30).forEach { o ->
                Text(
                    "${fmt.format(Date(o.optLong("t")))} · ${o.optLong("ms") / 60_000} dk" +
                        (if (o.optLong("steps") > 0) " · ${o.optLong("steps")} adım" else "") +
                        (if (o.optString("book").isNotBlank()) " · ${o.optString("book")}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
