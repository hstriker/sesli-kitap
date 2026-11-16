package com.seslikitap.ui.huzur

import androidx.compose.runtime.Composable

/** Sonraki paketlerin bölümleri (oyunlar, günün köşesi, hatıralar…). */
val EXTRA_SECTIONS: List<Pair<String, List<HuzurTile>>> = emptyList()

@Composable
fun ExtraPage(kind: String, onOpenBook: (Long) -> Unit, onOpenReader: (Long) -> Unit) {
}
