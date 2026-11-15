package com.seslikitap.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.seslikitap.player.PlayerState

/** Alt menünün üstünde duran küçük oynatıcı çubuğu. */
@Composable
fun MiniPlayer(st: PlayerState, onClick: () -> Unit, onToggle: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp, modifier = Modifier.clickable(onClick = onClick)) {
        Column {
            if (st.chapterCount > 0) {
                val p = (st.chapterIndex + if (st.sentenceCount > 0) st.sentenceIndex.toFloat() / st.sentenceCount else 0f) /
                    st.chapterCount
                LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BookCover(
                    st.coverPath, st.bookTitle, st.author,
                    Modifier.width(36.dp).height(52.dp).clip(RoundedCornerShape(4.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(st.bookTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        st.chapterTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        if (st.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (st.playing) "Duraklat" else "Dinle",
                    )
                }
            }
        }
    }
}
