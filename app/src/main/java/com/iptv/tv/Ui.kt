@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.iptv.tv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b); Store.init(this); Lang.apply(); Theme.load()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { java.io.File(filesDir, "crash.log").writeText(java.util.Date().toString() + "\n" + android.util.Log.getStackTraceString(e)) }
            prev?.uncaughtException(t, e)
        }
        setContent {
            // The layout is always left-to-right so the D-pad behaves the same in every language
            // (sidebar on the left, content on the right). Arabic text itself still renders correctly.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                MaterialTheme(colorScheme = darkColorScheme(
                    primary = Theme.accent, background = Theme.bg, surface = Theme.bg,
                    surfaceVariant = Theme.card, border = Theme.accent
                )) {
                    androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(
                        primary = Theme.accent, background = Theme.bg, surface = Theme.bg
                    )) { App() }
                }
            }
        }
    }
    override fun onStop() { super.onStop(); Hub.p?.let { Hub.was = it.playWhenReady; it.pause() } }
    override fun onStart() { super.onStart(); if (Hub.was) Hub.p?.play(); Hub.was = false }
}

fun Context.activity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

data class Media(val key: String, val url: String, val title: String, val img: String, val kind: String, val sid: String = "")
data class PlayArgs(val vod: List<Media> = emptyList(), val chans: List<Item> = emptyList(), val idx: Int = 0)

fun Hist.toMedia() = Media(key, url, title, img, kind, sid)

sealed class Route {
    object Main : Route()
    object Search : Route()
    object Settings : Route()
    object Playlists : Route()
    object Addons : Route()
    object Vpn : Route()
    object Phone : Route()
    object Cats : Route()
    data class Detail(val item: Item) : Route()
    data class Play(val args: PlayArgs) : Route()
}

fun creds(): Creds {
    val l = Store.pls(); val p = l.getOrNull(Store.active) ?: l.firstOrNull()
    return Creds(p?.server ?: "", p?.user ?: "", p?.pass ?: "", Store.sp.getString("tmdb", "") ?: "")
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val stack = remember { mutableStateListOf<Route>(Route.Main) }
    val ui = remember { MainUi() }
    val grid = rememberLazyGridState()
    val push: (Route) -> Unit = { stack.add(it) }
    val pop: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    val playLive: (List<Item>, Int) -> Unit = { l, i -> stack.add(Route.Play(PlayArgs(chans = l, idx = i))) }
    // Called after a playlist was added (on the TV or from the phone): rebuild the library from the new active playlist.
    val reload: () -> Unit = {
        Api.c = creds(); Store.loadFavs(); ui.cat = "*"; ui.lastIdx = 0
        Repo.reset()
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        scope.launch { Repo.load() }
    }

    LaunchedEffect(Unit) {
        Store.loadFavs(); Api.c = creds()
        ui.tab = Store.int("start", 0).coerceIn(0, 2)
        // Only ask for a playlist when none is saved. Otherwise open straight into the cached library.
        if (Api.c.server.isBlank()) { stack.add(Route.Playlists); return@LaunchedEffect }
        Repo.showCached()
        val auto = Store.sp.getString("vpn_auto", "")
        if (!auto.isNullOrBlank() && VpnService.prepare(ctx) == null)
            Vpn.list().firstOrNull { it.name == auto }?.let { withContext(Dispatchers.IO) { Vpn.connect(ctx, it) } }
        Repo.load()
    }

    // One simple rule for Back everywhere: go back one screen. (The main screen handles its own Back.)
    BackHandler(enabled = stack.size > 1) { pop() }

    val bg = Theme.bg
    Box(Modifier.fillMaxSize().background(bg)) {
        when (val r = stack[stack.lastIndex]) {
            Route.Main -> MainScreen(ui, grid,
                onOpen = { push(Route.Detail(it)) },
                onLive = playLive,
                onResume = { h ->
                    val single = { push(Route.Play(PlayArgs(vod = listOf(h.toMedia())))) }
                    if (h.kind == "series" && h.sid.isNotBlank()) scope.launch {
                        // Load the whole season list so the next episodes follow on after the resumed one.
                        val name = Repo.series.firstOrNull { it.id == h.sid }?.name ?: h.title.substringBeforeLast(" S")
                        val eps = withContext(Dispatchers.IO) { runCatching { Api.episodes(h.sid) }.getOrDefault(emptyList()) }
                        val q = eps.map { Media("e${it.id}", Api.epUrl(it.id, it.ext), "$name S${it.season}E${it.num}", h.img, "series", h.sid) }
                        val idx = q.indexOfFirst { it.key == h.key }
                        if (idx >= 0) { Store.put("lastEp_${Store.pid()}_${h.sid}", h.key.removePrefix("e")); push(Route.Play(PlayArgs(vod = q, idx = idx))) }
                        else single()
                    } else single()
                },
                onPlay = { push(Route.Play(PlayArgs(vod = listOf(it)))) },
                onSearch = { push(Route.Search) },
                onSettings = { push(Route.Settings) },
                onPlaylists = { push(Route.Playlists) })
            Route.Search -> SearchScreen(open = { push(Route.Detail(it)) }, play = playLive)
            Route.Settings -> SettingsScreen(
                goVpn = { push(Route.Vpn) }, goPl = { push(Route.Playlists) }, goAddons = { push(Route.Addons) }, goPhone = { push(Route.Phone) }, goCats = { push(Route.Cats) },
                refresh = { pop(); scope.launch { Repo.load(true) } })
            Route.Playlists -> PlaylistScreen(onChanged = reload, goPhone = { push(Route.Phone) }, onClose = pop)
            Route.Phone -> PhoneSetupScreen(onPlaylistAdded = reload)
            Route.Cats -> CategoryPickerScreen(onSaved = { ui.cat = "*"; ui.lastIdx = 0; scope.launch { Repo.reapply() }; pop() })
            Route.Addons -> AddonScreen()
            Route.Vpn -> VpnScreen()
            is Route.Detail -> DetailScreen(r.item, play = { push(Route.Play(it)) })
            is Route.Play -> PlayerScreen(r.args, onExit = pop, onSearch = { push(Route.Search) })
        }
    }
}

// ---------------------------------------------------------------- shared widgets

@Composable
fun Chip(t: String, sel: Boolean, w: Modifier = Modifier, pad: Dp = 16.dp, f: () -> Unit) {
    Button(
        onClick = f, modifier = w,
        colors = if (sel) ButtonDefaults.colors(containerColor = Theme.accent, contentColor = Theme.onAccent)
                 else ButtonDefaults.colors(containerColor = Theme.chip),
        contentPadding = PaddingValues(horizontal = pad, vertical = 6.dp)
    ) {
        Text(t, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SideItem(t: String, sel: Boolean, w: Modifier = Modifier, f: () -> Unit) {
    Button(
        onClick = f, modifier = w.fillMaxWidth(),
        colors = ButtonDefaults.colors(containerColor = if (sel) Theme.accent.copy(alpha = 0.35f) else Color.Transparent),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(t, Modifier.fillMaxWidth(), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Image decoded at a small fixed size: much lighter on memory/CPU than loading full posters. */
@Composable
fun Img(url: String, mod: Modifier, scale: ContentScale = ContentScale.Crop, w: Int = 300, h: Int = 450) {
    if (url.isBlank()) return
    val ctx = LocalContext.current
    val req = remember(url, w, h) { ImageRequest.Builder(ctx).data(url).size(w, h).crossfade(false).build() }
    AsyncImage(model = req, contentDescription = null, modifier = mod, contentScale = scale)
}

@Composable
fun Field(l: String, v: String, pw: Boolean = false, mod: Modifier = Modifier, frac: Float = 0.7f, c: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(v, c, mod.fillMaxWidth(frac), singleLine = true, label = { Text(l) },
        visualTransformation = if (pw) PasswordVisualTransformation() else VisualTransformation.None)
}

// ---------------------------------------------------------------- long-press options

class MenuAction(val label: String, val run: () -> Unit)
class MenuState(val title: String, val actions: List<MenuAction>)

fun favAction(m: Item) = MenuAction(if (Store.favs.contains(m.fkey)) "★ Remove from favorites" else "☆ Add to favorites") { Store.fav(m) }

/** The popup shown on long press (hold OK). First option gets focus; Back closes it. */
@Composable
fun OptionsDialog(title: String, actions: List<MenuAction>, onDismiss: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { fr.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(320.dp).background(Theme.card, RoundedCornerShape(14.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, Modifier.padding(bottom = 4.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            actions.forEachIndexed { i, a ->
                Chip(a.label, false, Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(fr) else Modifier)) {
                    onDismiss(); a.run()
                }
            }
        }
    }
}
