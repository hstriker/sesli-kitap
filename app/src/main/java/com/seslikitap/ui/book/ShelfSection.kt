@file:OptIn(ExperimentalLayoutApi::class)

package com.seslikitap.ui.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seslikitap.container

/** Kitap sayfasında "Raflarım": kitabı raflara ekleme/çıkarma, yeni raf. */
@Composable
fun ShelfSection(bookId: Long) {
    val repo = LocalContext.current.container.library
    val shelves by repo.shelves.collectAsStateWithLifecycle(initialValue = emptyList())
    val onShelves by remember(bookId) { repo.shelvesOf(bookId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var newShelf by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Raflarım", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shelves.forEach { s ->
                val on = s.id in onShelves
                FilterChip(
                    selected = on,
                    onClick = { repo.setOnShelf(s.id, bookId, !on) },
                    label = { Text(s.name) },
                    leadingIcon = if (on) ({ Icon(Icons.Default.Check, null) }) else null,
                )
            }
            AssistChip(
                onClick = { name = ""; newShelf = true },
                label = { Text("Yeni raf") },
                leadingIcon = { Icon(Icons.Default.Add, null) },
            )
        }
    }

    if (newShelf) {
        AlertDialog(
            onDismissRequest = { newShelf = false },
            title = { Text("Yeni raf") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("ör. Sırada, Yaz tatili, Tekrar dinlenecek") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { repo.createShelf(name, withBook = bookId); newShelf = false }) {
                    Text("Oluştur ve ekle")
                }
            },
            dismissButton = { TextButton(onClick = { newShelf = false }) { Text("Vazgeç") } },
        )
    }
}
