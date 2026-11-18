@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.iptv.tv

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Selection state that must survive opening a detail page / the player and coming back. */
class MainUi {
    var tab by mutableStateOf(0)          // 0 movies, 1 series, 2 live
    var cat by mutableStateOf("*")        // "*" all, "cw" continue watching, "fav", "radio" or a category id
    var lastIdx = 0                       // last focused grid index, to restore focus on return
}

class Cell(val item: Item?, val hist: Hist? = null)
private class Entry(val id: String, val label: String)

private fun subOf(i: Item): String {
    val r = if (Store.bool("rating", true) && i.rating.isNotBlank() && i.rating != "0") "★ ${i.rating}" else ""
    return listOf(i.year, r).filter { it.isNotBlank() }.joinToString("  ")
}

@Composable
fun MainScreen(
    ui: MainUi, grid: LazyGridState,
    onOpen: (Item) -> Unit, onLive: (List<Item>, Int) -> Unit, onResume: (Hist) -> Unit, onPlay: (Media) -> Unit,
    onSearch: () -> Unit, onSettings: () -> Unit, onPlaylists: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val side = rememberLazyListState()
    val selFR = remember { FocusRequester() }
    val restoreFR = remember { FocusRequester() }
    var contentFocused by remember { mutableStateOf(false) }
    var lastBack by remember { mutableStateOf(0L) }
    var menu by remember { mutableStateOf<MenuState?>(null) }

    val tab = ui.tab
    val plName = remember(Repo.version) { Store.cur()?.name?.ifBlank { null } ?: "Playlists" }
    val cat = ui.cat
    val ver = Repo.version
    val hv = History.version
    val fv = Store.favs.size
    val sort = remember { Store.int("sort", 0) }
    val showLabel = remember { !Store.bool("hideLabels", false) }
    val cardMin = remember { when (Store.int("grid", 1)) { 0 -> 100; 2 -> 140; else -> 120 } }

    val entries = remember(tab, ver) {
        val l = mutableListOf<Entry>()
        if (tab == 2) {
            l += Entry("fav", "★ Favorites"); l += Entry("*", "All channels")
            Repo.liveCats.forEach { l += Entry(it.id, it.name) }
            if (Repo.radio.isNotEmpty()) l += Entry("radio", "Radio")
        } else {
            l += Entry("cw", "Continue Watching"); l += Entry("fav", "★ Favorites"); l += Entry("", "Categories"); l += Entry("*", "All")
            (if (tab == 0) Repo.movieCats else Repo.serCats).forEach { l += Entry(it.id, it.name) }
        }
        l
    }
    val selIdx = entries.indexOfFirst { it.id == cat }.coerceAtLeast(0)

    val cells = remember(tab, cat, ver, hv, fv, sort) {
        fun sorted(l: List<Item>): List<Item> = if (tab == 2) l else when (sort) {
            1 -> l.sortedByDescending { it.added }
            2 -> l.sortedBy { it.nn }
            3 -> l.sortedByDescending { it.rating.toDoubleOrNull() ?: 0.0 }
            else -> l
        }
        val base = when (tab) { 0 -> Repo.movies; 1 -> Repo.series; else -> Repo.live }
        when (cat) {
            "cw" -> History.all().filter { it.kind == (if (tab == 0) "movie" else "series") }
                .distinctBy { if (it.sid.isNotBlank()) it.sid else it.key }.map { Cell(null, it) }
            "fav" -> sorted((if (tab == 2) Repo.live + Repo.radio else base).filter { Store.favs.contains(it.fkey) }).map { Cell(it) }
            "radio" -> Repo.radio.map { Cell(it) }
            "*" -> sorted(base).map { Cell(it) }
            else -> sorted(base.filter { it.cat == cat }).map { Cell(it) }
        }
    }
    val chans = remember(cells) { cells.mapNotNull { it.item } }
    val restoreIdx = ui.lastIdx.coerceIn(0, maxOf(0, cells.size - 1))

    val title = when (cat) {
        "cw" -> "Continue Watching"; "fav" -> "★ Favorites"; "*" -> if (tab == 2) "All channels" else "All"; "radio" -> "Radio"
        else -> entries.firstOrNull { it.id == cat }?.label ?: ""
    }

    var focusedLive by remember { mutableStateOf<Item?>(null) }
    var epg by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(focusedLive) {
        epg = emptyList(); val f = focusedLive ?: return@LaunchedEffect; delay(350)
        epg = withContext(Dispatchers.IO) { runCatching { Api.epg(f.id) }.getOrDefault(emptyList()) }
    }

    fun switchTab(i: Int) {
        ui.tab = i; ui.cat = "*"; ui.lastIdx = 0; focusedLive = null
        scope.launch { grid.scrollToItem(0); side.scrollToItem(0) }
    }
    fun switchCat(id: String) {
        ui.cat = id; ui.lastIdx = 0; focusedLive = null
        scope.launch { grid.scrollToItem(0) }
    }
    fun exitApp() {
        val now = System.currentTimeMillis()
        if (!Store.bool("confirmExit", true) || now - lastBack < 2000) ctx.activity()?.finish()
        else { lastBack = now; Toast.makeText(ctx, tr("Press back again to exit"), Toast.LENGTH_SHORT).show() }
    }

    // ---- Long-press (hold OK) menus: more than just favorites
    fun itemMenu(m: Item, index: Int): MenuState {
        val a = mutableListOf<MenuAction>()
        if (m.kind == "live" || m.kind == "radio") {
            a += MenuAction("▶  Play") { onLive(chans, index) }
        } else {
            if (m.kind == "movie") a += MenuAction("▶  Play") { onPlay(Media("m${m.id}", Api.vodUrl(m.id, m.ext), m.name, m.img, "movie")) }
            a += MenuAction("ⓘ Details") { onOpen(m) }
        }
        a += favAction(m)
        val inHistory = History.all().any { if (m.kind == "movie") it.key == "m${m.id}" else m.kind == "series" && it.sid == m.id }
        if (inHistory) a += MenuAction("✕ Remove from Continue Watching") {
            if (m.kind == "movie") History.remove("m${m.id}") else History.removeWhere { it.sid == m.id }
        }
        if (m.cat.isNotBlank() && m.cat != cat && entries.any { it.id == m.cat }) a += MenuAction("Go to category") { switchCat(m.cat) }
        return MenuState(m.name, a)
    }
    fun cwMenu(h: Hist): MenuState {
        val a = mutableListOf<MenuAction>()
        a += MenuAction(if (h.pos > 5000) "▶  Resume ${fmtTime(h.pos)}" else "▶  Play") { onResume(h) }
        val m = if (h.kind == "movie") Repo.movies.firstOrNull { "m${it.id}" == h.key } else Repo.series.firstOrNull { it.id == h.sid }
        if (m != null) { a += MenuAction("ⓘ Details") { onOpen(m) }; a += favAction(m) }
        a += MenuAction("✕ Remove from Continue Watching") { if (h.sid.isNotBlank()) History.removeWhere { it.sid == h.sid } else History.remove(h.key) }
        a += MenuAction("Clear all Continue Watching") { History.clear() }
        return MenuState(h.title, a)
    }

    // Back: content -> sidebar, sidebar -> (double back) exit.
    BackHandler {
        if (contentFocused) {
            scope.launch {
                side.scrollToItem(selIdx); delay(30)
                if (runCatching { selFR.requestFocus() }.isFailure) exitApp()
            }
        } else exitApp()
    }

    // Initial / restored focus once the library is available.
    LaunchedEffect(Repo.ready) {
        if (!Repo.ready) return@LaunchedEffect
        if (restoreIdx > 0) grid.scrollToItem(restoreIdx)
        repeat(12) {
            val ok = runCatching { if (cells.isNotEmpty()) restoreFR.requestFocus() else selFR.requestFocus() }.isSuccess
            if (ok) return@LaunchedEffect
            delay(60)
        }
    }

    if (!Repo.ready) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(when {
                    Repo.error.isNotBlank() -> "Error: ${Repo.error}"
                    Api.c.server.isBlank() -> "No playlist yet"
                    else -> "Loading your library..."
                })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Playlists", true) { onPlaylists() }
                    Chip("Retry", false) { scope.launch { Repo.load(true) } }
                    Chip("Settings", false) { onSettings() }
                }
            }
        }
        return
    }

    Row(Modifier.fillMaxSize()) {
        // ---------- Sidebar ----------
        Column(Modifier.width(210.dp).fillMaxHeight().background(Theme.sidebar).padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Movies", "Series", "Live").forEachIndexed { i, n ->
                    Chip(n, tab == i, Modifier.weight(1f), 4.dp) { switchTab(i) }
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), state = side, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(entries) { _, e ->
                    if (e.id.isEmpty()) Text(e.label, Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp), fontSize = 11.sp)
                    else SideItem(e.label, e.id == cat, if (e.id == cat) Modifier.focusRequester(selFR) else Modifier) { switchCat(e.id) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Chip("☰  " + plName, false, Modifier.fillMaxWidth(), 8.dp) { onPlaylists() }
        }

        // ---------- Content ----------
        Column(Modifier.weight(1f).fillMaxHeight().onFocusChanged { contentFocused = it.hasFocus }) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (Repo.refreshing) Text("↻", fontSize = 14.sp)
                Text("${cells.size}", fontSize = 12.sp)
                Chip("Search", false, Modifier, 10.dp) { onSearch() }
                Chip(if (Vpn.up != null) "VPN ●" else "Settings", false, Modifier, 10.dp) { onSettings() }
            }
            if (cells.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                    Text(if (cat == "cw") "Nothing watched yet" else if (cat == "fav") "No favorites yet (hold OK on an item)" else "Nothing here")
                }
            } else {
                LazyVerticalGrid(
                    GridCells.Adaptive(if (tab == 2) 190.dp else cardMin.dp), Modifier.weight(1f), state = grid,
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(cells) { i, c ->
                        val mod = Modifier
                            .onFocusChanged { if (it.hasFocus) { ui.lastIdx = i; if (tab == 2) focusedLive = c.item } }
                            .then(if (i == restoreIdx) Modifier.focusRequester(restoreFR) else Modifier)
                        val h = c.hist
                        val m = c.item
                        if (h != null) {
                            PosterCard(h.title, if (h.dur > 0L) "${fmtTime(h.pos)} / ${fmtTime(h.dur)}" else "Next episode", h.img, false, h.progress, showLabel, mod, { onResume(h) }, { menu = cwMenu(h) })
                        } else if (m != null) {
                            val fav = Store.favs.contains(m.fkey)
                            if (tab == 2) LiveCard(ChNum.num(m, i), m.name, m.img, fav, mod, { onLive(chans, i) }, { menu = itemMenu(m, i) })
                            else PosterCard(m.name, subOf(m), m.img, fav, 0f, showLabel, mod, { onOpen(m) }, { menu = itemMenu(m, i) })
                        }
                    }
                }
            }
            if (tab == 2) {
                Column(Modifier.fillMaxWidth().height(58.dp).background(Theme.panel).padding(horizontal = 16.dp, vertical = 4.dp)) {
                    focusedLive?.let { f ->
                        Text(f.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        epg.take(2).forEach { Text(it, fontSize = 11.sp, maxLines = 1) }
                    }
                }
            }
        }
    }
    menu?.let { mm -> OptionsDialog(mm.title, mm.actions) { menu = null } }
}

@Composable
fun PosterCard(
    title: String, sub: String, img: String, fav: Boolean, progress: Float, showLabel: Boolean,
    mod: Modifier, onClick: () -> Unit, onLong: () -> Unit
) {
    Card(onClick = onClick, onLongClick = onLong, modifier = mod.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Theme.card)) {
            if (img.isNotBlank()) Img(img, Modifier.fillMaxSize())
            else Text(title, Modifier.align(Alignment.Center).padding(6.dp), fontSize = 11.sp, maxLines = 4)
            if (fav) Text("★", Modifier.align(Alignment.TopEnd).padding(4.dp), color = Color(0xFFFFC107), fontSize = 14.sp)
            if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(3.dp).background(Theme.accent))
        }
        if (showLabel) Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp).height(38.dp)) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
fun LiveCard(num: Int, name: String, img: String, fav: Boolean, mod: Modifier, onClick: () -> Unit, onLong: () -> Unit) {
    Card(onClick = onClick, onLongClick = onLong, modifier = mod.fillMaxWidth()) {
        Row(Modifier.padding(8.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            if (num > 0) Text("$num", Modifier.width(30.dp), fontSize = 11.sp, maxLines = 1)
            if (img.isNotBlank()) Img(img, Modifier.size(34.dp), ContentScale.Fit, 100, 100) else Spacer(Modifier.size(34.dp))
            Spacer(Modifier.width(8.dp))
            Text(name, Modifier.weight(1f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (fav) Text("★", color = Color(0xFFFFC107))
        }
    }
}
