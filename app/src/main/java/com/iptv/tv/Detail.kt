@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.iptv.tv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun DetailScreen(item: Item, play: (PlayArgs) -> Unit) {
    val isSeries = item.kind == "series"
    var meta by remember { mutableStateOf<Meta?>(null) }
    var eps by remember { mutableStateOf<List<Ep>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var season by remember { mutableStateOf(-1) }
    var srcs by remember { mutableStateOf<List<Src>>(emptyList()) }
    val playFR = remember { FocusRequester() }
    val hv = History.version
    val lastKey = "lastEp_${Store.pid()}_${item.id}"

    LaunchedEffect(Unit) { delay(120); runCatching { playFR.requestFocus() } }
    LaunchedEffect(item) { if (item.kind == "movie") srcs = runCatching { Addons.sources(item.name) }.getOrDefault(emptyList()) }
    LaunchedEffect(item) { meta = withContext(Dispatchers.IO) { runCatching { Api.tmdb(item.name, isSeries, item.id) }.getOrNull() } }
    LaunchedEffect(item) {
        if (isSeries) {
            eps = withContext(Dispatchers.IO) { runCatching { Api.episodes(item.id) }.getOrDefault(emptyList()) }
            val last = Store.str(lastKey, "")
            season = eps.firstOrNull { it.id == last }?.season ?: eps.firstOrNull()?.season ?: 0
            loaded = true
        }
    }

    val queue = remember(eps) {
        eps.map { Media("e${it.id}", Api.epUrl(it.id, it.ext), "${item.name} S${it.season}E${it.num}", item.img, "series", item.id) }
    }
    val watched = remember(hv) { History.all().filter { it.pos > 0 }.map { it.key }.toSet() }
    val movie = remember(item) { Media("m${item.id}", Api.vodUrl(item.id, item.ext), item.name, item.img, "movie") }

    Box(Modifier.fillMaxSize()) {
        Img(meta?.backdrop?.takeIf { it.isNotBlank() } ?: item.img, Modifier.fillMaxSize().alpha(0.25f), ContentScale.Crop, 1280, 720)
        Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Img(item.img, Modifier.width(130.dp).height(195.dp).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val line = listOfNotNull(meta?.year?.takeIf { it.isNotBlank() }, meta?.rating?.takeIf { it.isNotBlank() }?.let { "★ $it" }).joinToString("   ")
                if (line.isNotBlank()) Text(line, fontSize = 13.sp)
                Text(meta?.overview ?: "", fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isSeries) {
                        val h = remember(hv) { History.get(movie.key) }
                        val label = if (h != null && h.pos > 5000) "▶  Resume ${fmtTime(h.pos)}" else "▶  Play"
                        Chip(label, true, Modifier.focusRequester(playFR)) { play(PlayArgs(vod = listOf(movie))) }
                    } else {
                        val last = Store.str(lastKey, "")
                        val li = queue.indexOfFirst { it.key == "e$last" }
                        val label = if (li >= 0) "▶  Continue ${queue[li].title.substringAfterLast(' ')}" else "▶  Play"
                        Chip(label, true, Modifier.focusRequester(playFR)) {
                            if (queue.isNotEmpty()) {
                                val idx = if (li >= 0) li else 0
                                Store.put(lastKey, queue[idx].key.removePrefix("e"))
                                play(PlayArgs(vod = queue, idx = idx))
                            }
                        }
                    }
                    Chip(if (Store.favs.contains(item.fkey)) "★ Unfavorite" else "☆ Favorite", false) { Store.fav(item) }
                }

                if (!isSeries) {
                    if (srcs.isNotEmpty()) {
                        Text("More sources (addons)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(srcs) { x ->
                                Chip(x.label, false, Modifier.fillMaxWidth()) {
                                    play(PlayArgs(vod = listOf(Media("u" + x.url.hashCode(), x.url, item.name, item.img, "movie"))))
                                }
                            }
                        }
                    }
                } else if (!loaded) {
                    Text("Loading...", fontSize = 12.sp)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(eps.map { it.season }.distinct()) { s -> Chip("Season $s", s == season) { season = s } }
                    }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(eps.filter { it.season == season }) { e ->
                            val mark = if ("e${e.id}" in watched) "  ●" else ""
                            Chip("E${e.num}  ${e.title}$mark", false, Modifier.fillMaxWidth()) {
                                val idx = queue.indexOfFirst { it.key == "e${e.id}" }
                                if (idx >= 0) {
                                    Store.put(lastKey, e.id)
                                    play(PlayArgs(vod = queue, idx = idx))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchScreen(open: (Item) -> Unit, play: (List<Item>, Int) -> Unit) {
    var q by remember { mutableStateOf("") }
    var films by remember { mutableStateOf(listOf<Item>()) }
    var sers by remember { mutableStateOf(listOf<Item>()) }
    var chans by remember { mutableStateOf(listOf<Item>()) }
    var menu by remember { mutableStateOf<MenuState?>(null) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); runCatching { fr.requestFocus() } }
    LaunchedEffect(q) {
        val k = nz(q.trim())
        if (k.length < 2) { films = emptyList(); sers = emptyList(); chans = emptyList(); return@LaunchedEffect }
        delay(250)
        val r = withContext(Dispatchers.Default) {
            Triple(
                Repo.movies.filter { it.nn.contains(k) }.take(60),
                Repo.series.filter { it.nn.contains(k) }.take(60),
                (Repo.live + Repo.radio).filter { it.nn.contains(k) }.take(60)
            )
        }
        films = r.first; sers = r.second; chans = r.third
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Field("Search films, series, channels", q, false, Modifier.focusRequester(fr)) { q = it }
        }
        LazyVerticalGrid(
            GridCells.Adaptive(120.dp), Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (films.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Text("Movies", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                items(films) { m -> PosterCard(m.name, m.year, m.img, Store.favs.contains(m.fkey), 0f, true, Modifier, { open(m) }, { menu = MenuState(m.name, listOf(MenuAction("ⓘ Details") { open(m) }, favAction(m))) }) }
            }
            if (sers.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Text("Series", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                items(sers) { m -> PosterCard(m.name, m.year, m.img, Store.favs.contains(m.fkey), 0f, true, Modifier, { open(m) }, { menu = MenuState(m.name, listOf(MenuAction("ⓘ Details") { open(m) }, favAction(m))) }) }
            }
            if (chans.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Text("Channels", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                itemsIndexed(chans, span = { _, _ -> GridItemSpan(2) }) { i, c ->
                    LiveCard(ChNum.num(c, i), c.name, c.img, Store.favs.contains(c.fkey), Modifier, { play(chans, i) }, { menu = MenuState(c.name, listOf(MenuAction("▶  Play") { play(chans, i) }, favAction(c))) })
                }
            }
        }
        menu?.let { mm -> OptionsDialog(mm.title, mm.actions) { menu = null } }
    }
}
