@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.iptv.tv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Hub {
    @Volatile var p: ExoPlayer? = null
    var was = false
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val x = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%02d:%02d".format(m, x)
}

private fun trackName(g: Tracks.Group, n: Int, total: Int): String {
    val f = g.getTrackFormat(0)
    return (f.label ?: f.language ?: "Track") + " (${n + 1}/$total)"
}

fun cycleTrack(p: ExoPlayer, type: Int, allowOff: Boolean): String {
    val gs = p.currentTracks.groups.filter { it.type == type }
    if (gs.isEmpty()) return "—"
    val cur = gs.indexOfFirst { it.isSelected }
    val b = p.trackSelectionParameters.buildUpon()
    if (allowOff && cur == gs.size - 1) {
        b.setTrackTypeDisabled(type, true)
        p.trackSelectionParameters = b.build()
        return "Off"
    }
    val n = (cur + 1) % gs.size
    b.setTrackTypeDisabled(type, false)
    b.setOverrideForType(TrackSelectionOverride(gs[n].mediaTrackGroup, 0))
    p.trackSelectionParameters = b.build()
    return trackName(gs[n], n, gs.size)
}

@Composable
fun PlayerScreen(args: PlayArgs, onExit: () -> Unit, onSearch: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isLive = args.chans.isNotEmpty()

    // Live channel list & category state (allows switching categories while watching in split mode)
    var activeChans by remember { mutableStateOf(args.chans) }
    var ci by remember { mutableStateOf(args.idx.coerceAtLeast(0)) }
    var fullScreen by remember { mutableStateOf(true) }
    var selectedCatId by remember {
        mutableStateOf(args.chans.getOrNull(args.idx)?.cat?.takeIf { it.isNotBlank() } ?: "*")
    }

    var u by remember(ci, activeChans) { mutableStateOf(0) }
    var err by remember(ci, activeChans, u) { mutableStateOf("") }
    var perr by remember { mutableStateOf<PlaybackException?>(null) }
    var ended by remember { mutableStateOf(0) }
    var buffering by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var bar by remember { mutableStateOf(false) }
    var stamp by remember { mutableStateOf(0) }
    var fit by remember { mutableStateOf(Store.int("aspect", 0).coerceIn(0, 2)) }
    var note by remember { mutableStateOf("") }
    var digits by remember { mutableStateOf("") }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    var epg by remember(ci, activeChans) { mutableStateOf(listOf<String>()) }
    var banner by remember(ci, activeChans) { mutableStateOf(true) }
    var speedIdx by remember { mutableStateOf(1) }
    var controls by remember { mutableStateOf(false) }   // VOD button bar has the focus
    var playing by remember { mutableStateOf(true) }     // play/pause icon

    val rootFR = remember { FocusRequester() }
    val menuFR = remember { FocusRequester() }
    val ctlFR = remember { FocusRequester() }
    val activeChanFR = remember { FocusRequester() }
    val loaded = remember { arrayOfNulls<Media>(1) }

    val cur: Item? = if (isLive) activeChans.getOrNull(ci) else null
    val media: Media? = if (!isLive) args.vod.getOrNull(ci) else null
    val title = cur?.name ?: media?.title ?: ""
    val swap = remember { Store.bool("swapUpDown", false) }
    val seekStep = remember { if (Store.int("seek", 0) == 0) 10_000L else 30_000L }
    val subMult = remember { when (Store.int("ssize", 1)) { 0 -> 0.8f; 2 -> 1.3f; 3 -> 1.6f; else -> 1f } }
    val showClock = remember { Store.bool("clock", true) }
    val clockFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val speeds = remember { listOf(0.75f, 1f, 1.25f, 1.5f, 2f) }

    val player = remember {
        val (mn, mx, st, rb) = when (Store.int("buf", 1)) {
            0 -> listOf(5000, 20000, 500, 1500)
            2 -> listOf(30000, 120000, 2500, 5000)
            else -> listOf(15000, 50000, 1500, 3000)
        }
        val lc = DefaultLoadControl.Builder().setBufferDurationsMs(mn, mx, st, rb).build()
        val dsf = DefaultHttpDataSource.Factory().setUserAgent("IPTVSmartersPro").setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000).setReadTimeoutMs(15000)
        val rf = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
        ExoPlayer.Builder(ctx, rf)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(dsf))
            .setLoadControl(lc)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                val al = Store.str("alang", ""); val sl = Store.str("slang", "")
                var b = trackSelectionParameters.buildUpon()
                if (al.isNotEmpty()) b = b.setPreferredAudioLanguage(al)
                if (sl.isNotEmpty()) b = b.setPreferredTextLanguage(sl)
                trackSelectionParameters = b.build()
            }
    }

    fun poke() { bar = true; stamp++ }
    fun markDone(m: Media) {
        History.remove(m.key)
        if (m.kind != "series") return
        val i = args.vod.indexOfFirst { it.key == m.key }
        val n = args.vod.getOrNull(i + 1) ?: return
        if (i < 0) return
        History.put(Hist(n.key, n.title, n.img, n.url, "series", 0L, 0L, System.currentTimeMillis(), n.sid))
        if (n.sid.isNotBlank()) Store.put("lastEp_${Store.pid()}_${n.sid}", n.key.removePrefix("e"))
    }
    fun saveNow() {
        val m = loaded[0] ?: return
        val d = player.duration; val p = player.currentPosition
        if (d <= 0 || d == C.TIME_UNSET) return
        if (p > d * 0.95) markDone(m)
        else if (p > 5000) History.put(Hist(m.key, m.title, m.img, m.url, m.kind, p, d, System.currentTimeMillis(), m.sid))
    }
    fun zap(d: Int) { val n = activeChans.size; if (n > 0) ci = (ci + d + n) % n }
    fun seek(delta: Long) {
        val d = player.duration
        var t = (player.currentPosition + delta).coerceAtLeast(0L)
        if (d != C.TIME_UNSET && d > 0) t = t.coerceAtMost(maxOf(0L, d - 1000))
        player.seekTo(t); poke()
    }
    fun toggle() { if (player.playWhenReady) player.pause() else player.play(); poke() }

    DisposableEffect(player) {
        Hub.p = player
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { perr = error }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) ended++
            }
        }
        player.addListener(l)
        onDispose { saveNow(); Hub.p = null; player.removeListener(l); player.release() }
    }

    // Play or switch channel seamlessly without destroying the PlayerView
    LaunchedEffect(cur?.id, media?.key, u) {
        saveNow(); loaded[0] = null
        val url: String
        if (isLive) {
            val c = cur ?: return@LaunchedEffect
            url = Api.liveUrls(c.id)[u.coerceIn(0, 1)]
        } else {
            val m = media ?: return@LaunchedEffect
            loaded[0] = m; url = m.url
        }
        player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.playWhenReady = true
        val m = media
        if (!isLive && m != null && Store.bool("resume", true)) {
            val h = History.get(m.key)
            if (h != null && h.pos > 5000 && h.progress < 0.95f) player.seekTo(h.pos)
        }
    }

    var guide by remember(ci) { mutableStateOf<Guide?>(null) }
    var guideShow by remember(ci) { mutableStateOf(false) }
    LaunchedEffect(ci) {
        if (isLive || !Store.bool("guide", true) || Api.c.tmdb.isBlank()) return@LaunchedEffect
        val m = media ?: return@LaunchedEffect
        val t = m.title.replace(Regex("\\s+S\\d+E\\d+$"), "")
        val g = withContext(Dispatchers.IO) { runCatching { Api.guide(t, m.kind == "series") }.getOrNull() } ?: return@LaunchedEffect
        guide = g
        var w = 0
        while (!player.isPlaying && w < 60) { delay(300); w++ }
        guideShow = true; delay(8000); guideShow = false
    }
    LaunchedEffect(perr) {
        val e = perr ?: return@LaunchedEffect
        perr = null
        if (isLive && u < 1) { u++ } else { err = e.errorCodeName }
    }
    LaunchedEffect(ended) {
        if (ended == 0 || isLive) return@LaunchedEffect
        media?.let { markDone(it) }
        loaded[0] = null
        if (Store.bool("autonext", true) && ci < args.vod.size - 1) ci++ else onExit()
    }
    LaunchedEffect(cur?.id) { banner = true; delay(3500); banner = false }
    LaunchedEffect(menu, fullScreen, controls) {
        delay(80)
        runCatching {
            when {
                menu -> menuFR.requestFocus()
                fullScreen -> if (controls) ctlFR.requestFocus() else rootFR.requestFocus()
                else -> activeChanFR.requestFocus()
            }
        }
    }
    LaunchedEffect(bar, menu) {
        while (bar || menu) {
            pos = player.currentPosition
            dur = player.duration.let { if (it == C.TIME_UNSET || it < 0) 0L else it }
            clock = System.currentTimeMillis()
            delay(500)
        }
    }
    LaunchedEffect(stamp, playing) {
        if (stamp == 0) return@LaunchedEffect
        delay(if (controls) 6000 else 4000)
        if (!isLive && !playing) return@LaunchedEffect   // keep the bar on screen while paused
        bar = false; controls = false
    }
    LaunchedEffect(note) { if (note.isNotEmpty()) { delay(2000); note = "" } }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1500)
        val n = digits.toIntOrNull(); digits = ""
        if (n != null) { val j = ChNum.indexOf(activeChans, n); if (j >= 0) ci = j }
    }
    // Fetch EPG whenever OSD menu is open OR split-screen preview is visible
    LaunchedEffect(menu, fullScreen, cur?.id) {
        val c = cur ?: return@LaunchedEffect
        if (menu || !fullScreen) {
            epg = withContext(Dispatchers.IO) { runCatching { Api.epg(c.id) }.getOrDefault(emptyList()) }
        }
    }

    fun handleKey(e: KeyEvent): Boolean {
        if (!fullScreen || menu || e.action != KeyEvent.ACTION_DOWN) return false
        val k = e.keyCode
        val ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER
        val up = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_CHANNEL_UP
        val down = k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_CHANNEL_DOWN
        val info = k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_INFO
        if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { toggle(); return true }
        if (k == KeyEvent.KEYCODE_MEDIA_PLAY) { player.play(); poke(); return true }
        if (k == KeyEvent.KEYCODE_MEDIA_PAUSE) { player.pause(); poke(); return true }
        if (isLive) {
            val digit = when (k) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> k - KeyEvent.KEYCODE_0
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> k - KeyEvent.KEYCODE_NUMPAD_0
                else -> -1
            }
            return when {
                digit >= 0 -> { if (digits.length < 4) digits += digit.toString(); true }
                up -> { zap(if (swap) -1 else 1); true }
                down -> { zap(if (swap) 1 else -1); true }
                k == KeyEvent.KEYCODE_MEDIA_NEXT -> { zap(1); true }
                k == KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { zap(-1); true }
                ok || info -> { menu = true; true }
                k == KeyEvent.KEYCODE_DPAD_LEFT -> { fullScreen = false; true }
                k == KeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> false
            }
        }
        if (controls) {
            poke()
            return when (k) {
                KeyEvent.KEYCODE_DPAD_UP -> { controls = false; true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seek(-30_000L); true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seek(30_000L); true }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { if (ci < args.vod.size - 1) ci++; true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (ci > 0) ci--; true }
                else -> false   // Left / Right / OK are handled by the focused button
            }
        }
        val mult = if (e.repeatCount > 8) 6 else if (e.repeatCount > 3) 3 else 1
        return when {
            k == KeyEvent.KEYCODE_DPAD_LEFT -> { seek(-seekStep * mult); true }
            k == KeyEvent.KEYCODE_DPAD_RIGHT -> { seek(seekStep * mult); true }
            k == KeyEvent.KEYCODE_MEDIA_REWIND -> { seek(-30_000L); true }
            k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seek(30_000L); true }
            k == KeyEvent.KEYCODE_MEDIA_NEXT -> { if (ci < args.vod.size - 1) ci++; true }
            k == KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (ci > 0) ci--; true }
            ok -> { toggle(); true }
            up || info -> { menu = true; true }
            down -> { poke(); controls = true; true }
            else -> false
        }
    }

    // Back from menu closes menu; Back from full-screen Live opens the split-screen channel browser
    BackHandler(enabled = menu || controls || (isLive && fullScreen)) {
        if (menu) menu = false
        else if (controls) controls = false
        else if (isLive && fullScreen) fullScreen = false
    }

    // Single shared PlayerView instance so switching between full-screen and preview never stops playback
    val videoSurface: @Composable (Modifier) -> Unit = { mod ->
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false; keepScreenOn = true
                    isFocusable = false; isFocusableInTouchMode = false
                    setKeepContentOnPlayerReset(true)
                }
            },
            update = {
                it.player = player
                it.resizeMode = when (fit) {
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                it.subtitleView?.setFractionalTextSize(0.0533f * subMult)
            },
            modifier = mod
        )
    }

    if (isLive && !fullScreen) {
        // ==================== SPLIT-SCREEN LIVE BROWSER (Categories | Channels | Preview + EPG) ====================
        val categories = remember(Repo.version) {
            buildList {
                add(Triple("fav", "★ Favorites", (Repo.live + Repo.radio).count { Store.favs.contains(it.fkey) }))
                add(Triple("*", "All channels", Repo.live.size))
                Repo.liveCats.forEach { c -> add(Triple(c.id, c.name, Repo.live.count { it.cat == c.id })) }
                if (Repo.radio.isNotEmpty()) add(Triple("radio", "Radio", Repo.radio.size))
            }
        }
        val displayedChans = remember(selectedCatId, Repo.version, Store.favs.size) {
            when (selectedCatId) {
                "fav" -> (Repo.live + Repo.radio).filter { Store.favs.contains(it.fkey) }
                "radio" -> Repo.radio
                "*" -> Repo.live
                else -> Repo.live.filter { it.cat == selectedCatId }
            }
        }
        val catListState = rememberLazyListState()
        val chanListState = rememberLazyListState()

        LaunchedEffect(selectedCatId) {
            val activeIdxInDisplayed = displayedChans.indexOfFirst { it.id == cur?.id }
            if (activeIdxInDisplayed >= 0) chanListState.scrollToItem(activeIdxInDisplayed)
        }

        Column(Modifier.fillMaxSize().background(Theme.bg)) {
            // Top Header Bar
            Row(
                Modifier.fillMaxWidth().background(Theme.sidebar).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Live TV", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Theme.accent)
                Text("•", fontSize = 14.sp, color = Color.Gray)
                Text(cur?.name ?: "", Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (showClock) Text(clockFmt.format(Date(clock)), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }

            // 3-Column Layout: Categories | Channel List | Live Preview + EPG + Buttons
            Row(Modifier.weight(1f).fillMaxWidth()) {
                // 1. Left Column: Categories
                LazyColumn(
                    state = catListState,
                    modifier = Modifier.width(210.dp).fillMaxHeight().background(Theme.sidebar).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(categories) { _, (id, label, count) ->
                        val sel = id == selectedCatId
                        Button(
                            onClick = { selectedCatId = id },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.colors(
                                containerColor = if (sel) Theme.accent.copy(alpha = 0.35f) else Color.Transparent
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
                        ) {
                            Text(label, Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("$count", fontSize = 11.sp, color = Color.LightGray)
                        }
                    }
                }

                // 2. Middle Column: Channels in Selected Category
                LazyColumn(
                    state = chanListState,
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(displayedChans) { idx, ch ->
                        val isPlayingThis = ch.id == cur?.id
                        val isFav = Store.favs.contains(ch.fkey)
                        Button(
                            onClick = {
                                if (isPlayingThis) {
                                    // Clicking the already-playing channel returns to full screen
                                    fullScreen = true
                                } else {
                                    // Switch stream immediately while staying in split view
                                    activeChans = displayedChans
                                    ci = idx
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                                .then(if (isPlayingThis) Modifier.focusRequester(activeChanFR) else Modifier),
                            colors = ButtonDefaults.colors(
                                containerColor = if (isPlayingThis) Theme.accent.copy(alpha = 0.28f) else Theme.card
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            val num = ChNum.num(ch, idx)
                            if (num > 0) Text("$num", Modifier.width(34.dp), fontSize = 11.sp, color = Color.LightGray)
                            if (ch.img.isNotBlank()) {
                                Img(ch.img, Modifier.size(28.dp), ContentScale.Fit, 80, 80)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                ch.name, Modifier.weight(1f), fontSize = 12.sp,
                                fontWeight = if (isPlayingThis) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            if (isPlayingThis) Text("▶ ", fontSize = 11.sp, color = Theme.accent)
                            if (isFav) Text("★", fontSize = 12.sp, color = Color(0xFFFFC107))
                        }
                    }
                }

                // 3. Right Column: Mini Live Player + EPG + Action Buttons
                Column(
                    modifier = Modifier.width(360.dp).fillMaxHeight().background(Theme.panel).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Mini Live Video Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black)
                            .border(1.dp, Theme.accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .clickable { fullScreen = true }
                    ) {
                        videoSurface(Modifier.fillMaxSize())
                        if (buffering && err.isBlank()) Text("Loading...", Modifier.align(Alignment.Center), fontSize = 11.sp)
                        if (err.isNotBlank()) Text("Error: $err", Modifier.align(Alignment.Center), fontSize = 11.sp)
                    }

                    // Channel Title & EPG Info
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(cur?.name ?: "", fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (epg.isEmpty()) {
                            Text("No guide data", fontSize = 11.sp, color = Color.Gray)
                        } else {
                            epg.take(3).forEach { line ->
                                Text(line, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    // Bottom Action Buttons (Matching screenshot)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Full Screen", true, Modifier.weight(1f), 8.dp) { fullScreen = true }
                        if (cur != null) {
                            Chip(
                                if (Store.favs.contains(cur.fkey)) "★ Fav" else "☆ Fav",
                                false, Modifier.weight(1f), 8.dp
                            ) { Store.fav(cur) }
                        }
                        if (onSearch != null) {
                            Chip("Search", false, Modifier.weight(1f), 8.dp) { onSearch() }
                        }
                    }
                }
            }
        }
        return
    }

    // ==================== FULL-SCREEN PLAYER ====================
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(rootFR).focusable().onKeyEvent { handleKey(it.nativeKeyEvent) }) {
        videoSurface(Modifier.fillMaxSize())

        if (buffering && err.isBlank()) Text("Loading...", Modifier.align(Alignment.Center), fontSize = 14.sp)
        if (err.isNotBlank()) Text("Playback error: $err", Modifier.align(Alignment.Center), fontSize = 16.sp)
        if (isLive && banner && !menu) Text(chLabel(cur, ci, title), Modifier.align(Alignment.TopStart).padding(24.dp), fontSize = 20.sp)
        val gd = guide
        if (guideShow && gd != null && !menu) Column(
            Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 20.dp)
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text("Parental guidance", fontSize = 9.sp)
            if (gd.cert.isNotBlank()) Text(gd.cert, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (gd.tags.isNotEmpty()) Text(gd.tags.joinToString(" · ") { tr(it) }, fontSize = 10.sp, maxLines = 1)
        }
        if (digits.isNotEmpty()) Text(digits, Modifier.align(Alignment.TopEnd).padding(24.dp), fontSize = 34.sp, fontWeight = FontWeight.Bold)
        if (note.isNotEmpty()) Text(note, Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
            .background(Color(0xAA000000), RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 14.sp)
        if (showClock && (bar || menu)) Text(clockFmt.format(Date(clock)), Modifier.align(Alignment.TopEnd).padding(24.dp), fontSize = 16.sp)

        if (!isLive && (bar || menu)) {
            val multi = args.vod.size > 1
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                    .padding(horizontal = 32.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(fmtTime(pos), fontSize = 12.sp)
                    Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x55FFFFFF))) {
                        val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Theme.accent))
                    }
                    Text(fmtTime(dur), fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (multi) CtlBtn(Ico.PREV) { if (ci > 0) ci--; poke() }
                    CtlBtn(Ico.BACK10) { seek(-seekStep) }
                    CtlBtn(if (playing) Ico.PAUSE else Ico.PLAY, Modifier.focusRequester(ctlFR), big = true) { toggle() }
                    CtlBtn(Ico.STOP) { onExit() }
                    CtlBtn(Ico.FWD10) { seek(seekStep) }
                    if (multi) CtlBtn(Ico.NEXT) { if (ci < args.vod.size - 1) ci++; poke() }
                    Spacer(Modifier.weight(1f))
                    Chip("Aspect: " + listOf("Fit", "Fill", "Zoom")[fit], false, Modifier, 12.dp) { fit = (fit + 1) % 3; Store.put("aspect", fit); poke() }
                    Chip("Audio", false, Modifier, 12.dp) { note = "Audio: " + cycleTrack(player, C.TRACK_TYPE_AUDIO, false); poke() }
                    Chip("Subtitles", false, Modifier, 12.dp) { note = "Subtitles: " + cycleTrack(player, C.TRACK_TYPE_TEXT, true); poke() }
                    Chip("Speed: ${speeds[speedIdx]}x", false, Modifier, 12.dp) {
                        speedIdx = (speedIdx + 1) % speeds.size; player.setPlaybackSpeed(speeds[speedIdx]); poke()
                    }
                }
            }
        }

        if (menu) Column(
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xDD000000), Color.Transparent))).padding(24.dp)
        ) {
            Text(if (isLive) chLabel(cur, ci, title) else title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            epg.forEach { Text(it, fontSize = 12.sp, maxLines = 1) }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isLive) Chip(if (playing) "⏸  Pause" else "▶  Play", true, Modifier.focusRequester(menuFR)) { toggle(); menu = false }
                if (isLive) Chip("Channels", true, Modifier.focusRequester(menuFR)) { menu = false; fullScreen = false }
                Chip("Aspect: " + listOf("Fit", "Fill", "Zoom")[fit], false, if (isLive) Modifier else Modifier) {
                    fit = (fit + 1) % 3; Store.put("aspect", fit)
                }
                Chip("Audio", false) { note = "Audio: " + cycleTrack(player, C.TRACK_TYPE_AUDIO, false) }
                Chip("Subtitles", false) { note = "Subtitles: " + cycleTrack(player, C.TRACK_TYPE_TEXT, true) }
                if (!isLive) Chip("Speed: ${speeds[speedIdx]}x", false) {
                    speedIdx = (speedIdx + 1) % speeds.size; player.setPlaybackSpeed(speeds[speedIdx])
                }
                if (isLive && cur != null) Chip(if (Store.favs.contains(cur.fkey)) "★ Unfavorite" else "☆ Favorite", false) { Store.fav(cur) }
            }
        }
    }
}


// ---------------------------------------------------------------- round icon buttons for the VOD control bar

enum class Ico { PLAY, PAUSE, STOP, PREV, NEXT, BACK10, FWD10 }

private fun DrawScope.drawIco(ico: Ico, c: Color) {
    val w = size.width; val h = size.height
    fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        drawPath(Path().apply { moveTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3); close() }, c)
    }
    when (ico) {
        Ico.PLAY -> tri(w * 0.15f, 0f, w * 0.15f, h, w, h / 2)
        Ico.PAUSE -> { drawRect(c, Offset(w * 0.1f, 0f), Size(w * 0.30f, h)); drawRect(c, Offset(w * 0.6f, 0f), Size(w * 0.30f, h)) }
        Ico.STOP -> drawRect(c, Offset(w * 0.08f, h * 0.08f), Size(w * 0.84f, h * 0.84f))
        Ico.PREV -> { drawRect(c, Offset(0f, 0f), Size(w * 0.16f, h)); tri(w, 0f, w, h, w * 0.22f, h / 2) }
        Ico.NEXT -> { drawRect(c, Offset(w * 0.84f, 0f), Size(w * 0.16f, h)); tri(0f, 0f, 0f, h, w * 0.78f, h / 2) }
        Ico.BACK10 -> { tri(w * 0.5f, 0f, w * 0.5f, h, 0f, h / 2); tri(w, 0f, w, h, w * 0.5f, h / 2) }
        Ico.FWD10 -> { tri(0f, 0f, 0f, h, w * 0.5f, h / 2); tri(w * 0.5f, 0f, w * 0.5f, h, w, h / 2) }
    }
}

/** Round, D-pad friendly icon button: grows and takes the accent colour when focused. */
@Composable
fun CtlBtn(ico: Ico, mod: Modifier = Modifier, big: Boolean = false, onClick: () -> Unit) {
    var foc by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (foc) 1.15f else 1f, label = "ctl")
    Box(
        mod.size(if (big) 56.dp else 44.dp).scale(sc).clip(CircleShape)
            .background(if (foc) Theme.accent else Color(0x55FFFFFF))
            .onFocusChanged { foc = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val fg = if (foc) Theme.onAccent else Color.White
        Canvas(Modifier.size(if (big) 24.dp else 18.dp)) { drawIco(ico, fg) }
    }
}
