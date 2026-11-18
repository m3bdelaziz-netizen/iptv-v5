@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.iptv.tv

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api

/** Channel numbering: 0 = position in the list shown, 1 = provider's own numbers, 2 = same number in every category, 3 = hidden. */
object ChNum {
    fun mode() = Store.int("chnum", 0).coerceIn(0, 3)

    private var cache: Map<String, Int> = emptyMap()
    private var cacheVer = -1
    private fun global(): Map<String, Int> {
        val v = Repo.version
        if (cacheVer != v) {
            val m = HashMap<String, Int>(Repo.live.size * 2)
            Repo.live.forEachIndexed { i, c -> m[c.id] = i + 1 }
            cache = m; cacheVer = v
        }
        return cache
    }

    /** Number to show for channel [c] at 0-based position [pos] of the list on screen; 0 = show nothing. */
    fun num(c: Item, pos: Int): Int = when (mode()) {
        3 -> 0
        1 -> if (c.num > 0) c.num else pos + 1
        2 -> global()[c.id] ?: (pos + 1)
        else -> pos + 1
    }

    /** Channel index for a number typed on the remote, or -1. */
    fun indexOf(list: List<Item>, n: Int): Int {
        if (n < 1) return -1
        return when (mode()) {
            1, 2 -> list.indices.firstOrNull { num(list[it], it) == n } ?: -1
            else -> if (n <= list.size) n - 1 else -1
        }
    }
}

fun chLabel(c: Item?, pos: Int, title: String): String {
    val n = if (c != null) ChNum.num(c, pos) else pos + 1
    return if (n > 0) "$n  $title" else title
}

/** Pick which provider categories are kept. Everything else is dropped when the library is loaded (smaller, faster app). */
@Composable
fun CategoryPickerScreen(onSaved: () -> Unit) {
    val kinds = listOf("live", "movie", "series")
    val lists = remember { listOf(Repo.allLiveCats, Repo.allMovieCats, Repo.allSerCats).map { l -> l.distinctBy { it.id } } }  // unique ids: LazyColumn keys must not repeat
    val sels = remember {
        mutableStateListOf<Set<String>>().apply {
            kinds.forEachIndexed { i, k -> add(Repo.selection(k) ?: lists[i].map { it.id }.toSet()) }
        }
    }
    var tab by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf("") }
    val list = lists[tab]
    val sel = sels[tab]

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Choose categories", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Only the categories you tick are loaded. Press Save to apply.", fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Live TV", tab == 0) { tab = 0 }
            Chip("Movies", tab == 1) { tab = 1 }
            Chip("Series", tab == 2) { tab = 2 }
            Spacer(Modifier.width(16.dp))
            Chip("Select all", false) { sels[tab] = list.map { it.id }.toSet() }
            Chip("Clear", false) { sels[tab] = emptySet() }
            Chip("Save", true) {
                val bad = kinds.indices.firstOrNull { lists[it].isNotEmpty() && sels[it].isEmpty() }
                if (bad != null) msg = "Pick at least one category in " + listOf("Live TV", "Movies", "Series")[bad]
                else {
                    kinds.forEachIndexed { i, k -> Repo.saveSelection(k, if (sels[i].size >= lists[i].size) null else sels[i]) }
                    onSaved()
                }
            }
        }
        Text("${sel.size} / ${list.size}" + (if (msg.isNotBlank()) "   $msg" else ""), fontSize = 12.sp)
        if (list.isEmpty()) Text("Load your playlist first, then come back here.", fontSize = 13.sp)
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(list, key = { it.id }) { c ->
                val on = c.id in sel
                Chip((if (on) "✓  " else "○  ") + c.name, on, Modifier.fillMaxWidth()) {
                    sels[tab] = if (on) sel - c.id else sel + c.id
                    msg = ""
                }
            }
        }
    }
}
