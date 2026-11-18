@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.iptv.tv

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Opt(label: String, value: String, f: () -> Unit) {
    Button(
        onClick = f, modifier = Modifier.fillMaxWidth(0.85f),
        colors = ButtonDefaults.colors(containerColor = Theme.chip),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, color = Color.White)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, color = Color.White)
    }
}

@Composable
private fun Swatch(c: Color, sel: Boolean, f: () -> Unit) {
    Button(
        onClick = f, modifier = Modifier.width(46.dp).height(34.dp),
        colors = ButtonDefaults.colors(containerColor = c, focusedContainerColor = c),
        contentPadding = PaddingValues(0.dp)
    ) { Text(if (sel) "✓" else "", fontSize = 14.sp, color = Color.White) }
}

@Composable
private fun Group(t: String) {
    Text(t, Modifier.padding(top = 12.dp, bottom = 2.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Theme.accent)
}

@Composable
fun SettingsScreen(goVpn: () -> Unit, goPl: () -> Unit, goAddons: () -> Unit, goPhone: () -> Unit, goCats: () -> Unit, refresh: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    var tmdb by remember { mutableStateOf(Store.str("tmdb", "")) }
    var msg by remember { mutableStateOf("") }
    var crash by remember { mutableStateOf(runCatching { java.io.File(Store.dir, "crash.log").takeIf { it.exists() }?.readText()?.take(900) }.getOrNull()) }
    @Suppress("UNUSED_VARIABLE") val t = tick

    fun cyc(k: String, n: Int, d: Int = 0) { Store.put(k, (Store.int(k, d) + 1) % n); tick++ }
    fun tog(k: String, d: Boolean) { Store.put(k, !Store.bool(k, d)); tick++ }
    fun cycStr(k: String, vararg v: String) { val i = v.indexOf(Store.str(k, v[0])); Store.put(k, v[(i + 1) % v.size]); tick++ }
    fun pick(k: String, d: Int, vararg l: String) = l[Store.int(k, d).coerceIn(0, l.size - 1)]
    fun onoff(b: Boolean) = if (b) "On" else "Off"
    fun lang(v: String) = when (v) { "ar" -> "العربية"; "en" -> "English"; else -> "Auto / تلقائي" }
    fun track(v: String) = when (v) { "ar" -> "Arabic"; "en" -> "English"; else -> "Auto" }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Settings", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)

        Group("General")
        Opt("Language / اللغة", lang(Store.str("lang", "auto"))) {
            cycStr("lang", "auto", "en", "ar"); Lang.apply()
        }
        Opt("Start screen", pick("start", 0, "Movies", "Series", "Live")) { cyc("start", 3) }
        Opt("Channel numbers", pick("chnum", 0, "List position", "Provider number", "Same in every category", "Off")) { cyc("chnum", 4) }
        Opt("Sort", pick("sort", 0, "Provider order", "Recently added", "A-Z", "Top rated")) { cyc("sort", 4) }
        Opt("Press Back twice to exit", onoff(Store.bool("confirmExit", true))) { tog("confirmExit", true) }

        Group("Appearance")
        Opt("Theme", Theme.modes[Theme.mode]) { Theme.setMode((Theme.mode + 1) % Theme.modes.size); tick++ }
        Text(tr("Accent color") + ": " + tr(Theme.accents[Theme.accentIdx].first), Modifier.padding(top = 4.dp), fontSize = 13.sp, color = Color.White)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Theme.accents.forEachIndexed { i, a -> Swatch(Color(a.second), i == Theme.accentIdx) { Theme.setAccent(i); tick++ } }
        }
        Opt("Poster size", pick("grid", 1, "Small", "Medium", "Large")) { cyc("grid", 3, 1) }
        Opt("Titles under posters", onoff(!Store.bool("hideLabels", false))) { tog("hideLabels", false) }
        Opt("Show ratings", onoff(Store.bool("rating", true))) { tog("rating", true) }

        Group("Playback")
        Opt("Stream format", if (Store.str("fmt", "m3u8") == "ts") "TS" else "HLS (m3u8)") { cycStr("fmt", "m3u8", "ts") }
        Opt("Buffer", pick("buf", 1, "Fast start", "Balanced", "Large")) { cyc("buf", 3, 1) }
        Opt("Default aspect", pick("aspect", 0, "Fit", "Fill", "Zoom")) { cyc("aspect", 3) }
        Opt("Resume where I stopped", onoff(Store.bool("resume", true))) { tog("resume", true) }
        Opt("Auto-play next episode", onoff(Store.bool("autonext", true))) { tog("autonext", true) }
        Opt("Seek step (Left / Right)", pick("seek", 0, "10 s", "30 s")) { cyc("seek", 2) }
        Opt("Swap Up / Down channel keys", onoff(Store.bool("swapUpDown", false))) { tog("swapUpDown", false) }
        Opt("Show clock in player", onoff(Store.bool("clock", true))) { tog("clock", true) }
        Opt("Parental guidance (TMDB)", onoff(Store.bool("guide", true))) { tog("guide", true) }
        Opt("Audio language", track(Store.str("alang", ""))) { cycStr("alang", "", "ar", "en") }
        Opt("Subtitle language", track(Store.str("slang", ""))) { cycStr("slang", "", "ar", "en") }
        Opt("Subtitle size", pick("ssize", 1, "Small", "Normal", "Large", "Huge")) { cyc("ssize", 4, 1) }

        Group("Library")
        Chip("Choose categories", true) { goCats() }
        Chip("Setup from phone (QR)", false) { goPhone() }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Playlists", false) { goPl() }
            Chip("Addons", false) { goAddons() }
            Chip("VPN profiles", false) { goVpn() }
        }
        Field("TMDB API key v3 (optional)", tmdb) { tmdb = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Save TMDB key", true) { Store.put("tmdb", tmdb); Api.c = Api.c.copy(tmdb = tmdb); msg = "Saved" }
            Chip("Refresh library", false) { refresh() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Clear favorites", false) { Store.clearFavs(); msg = "Saved" }
            Chip("Clear continue watching", false) { History.clear(); msg = "Saved" }
        }
        if (msg.isNotBlank()) Text(msg, color = Color.White)
        crash?.let { c ->
            Group("Last crash (send this text to get it fixed)")
            Text(c, fontSize = 10.sp, color = Color.White)
            Chip("Clear crash log", false) { runCatching { java.io.File(Store.dir, "crash.log").delete() }; crash = null }
        }
    }
}

@Composable
fun AddonScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(Addons.list()) }
    var url by remember { mutableStateOf("") }
    var paste by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val t = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
            note = Addons.addJson(t, (uri.lastPathSegment ?: "Local").substringAfterLast('/').removeSuffix(".json")) ?: "Added"; list = Addons.list()
        }.onFailure { note = "Cannot read file" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Addons (extra film sources)", fontWeight = FontWeight.Bold, color = Color.White)
        list.forEach { a ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${a.name}  (${if (a.kind == "stremio") "link" else "local json"})", Modifier.width(300.dp), maxLines = 1, color = Color.White)
                Chip("Remove", false) { Addons.remove(a); list = Addons.list() }
            }
        }
        Field("Addon link (manifest.json or stremio://...)", url) { url = it }
        Chip("Add from link", false) {
            scope.launch {
                val r = runCatching { withContext(Dispatchers.IO) { Addons.addUrl(url) } }
                note = if (r.isFailure) "Failed: ${r.exceptionOrNull()?.message}" else (r.getOrNull() ?: "Added"); list = Addons.list()
            }
        }
        Chip("Import .json file", false) { picker.launch(arrayOf("*/*")) }
        Field("Or paste JSON text", paste) { paste = it }
        Chip("Add pasted JSON", false) { note = Addons.addJson(paste, "Local") ?: "Added"; list = Addons.list() }
        if (note.isNotBlank()) Text(note, fontSize = 13.sp, color = Color.White)
        Text("Link addons use the Stremio addon format and need your TMDB key (set in Settings). Only direct http(s) stream links play; torrent-only streams are skipped.", fontSize = 11.sp, color = Color.White)
        Text("Local JSON format: {\"name\":\"My links\",\"streams\":[{\"title\":\"Movie 2010\",\"imdb\":\"tt0000000\",\"url\":\"https://...\"}]}  (matched by IMDb id or title)", fontSize = 11.sp, color = Color.White)
    }
}

@Composable
fun VpnScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(Vpn.list()) }
    var auto by remember { mutableStateOf(Store.sp.getString("vpn_auto", "") ?: "") }
    var url by remember { mutableStateOf("") }
    var paste by remember { mutableStateOf("") }
    var pname by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<VpnP?>(null) }
    fun connect(p: VpnP) { scope.launch { withContext(Dispatchers.IO) { Vpn.connect(ctx, p) }; note = Vpn.msg } }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) pending?.let { connect(it) } else note = "VPN permission denied"
    }
    fun start(p: VpnP) { val i = VpnService.prepare(ctx); if (i == null) connect(p) else { pending = p; perm.launch(i) } }
    fun add(n: String, t: String) { note = Vpn.add(n, t) ?: "Added '$n'"; profiles = Vpn.list() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val t = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
            add((uri.lastPathSegment ?: "profile").substringAfterLast('/').removeSuffix(".conf"), t)
        }.onFailure { note = "Cannot read file" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("VPN profiles (WireGuard .conf)", fontWeight = FontWeight.Bold, color = Color.White)
        Text(if (Vpn.up != null) "Connected: ${Vpn.up}" else "Not connected", fontSize = 13.sp, color = Color.White)
        profiles.forEach { p ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, Modifier.width(160.dp), maxLines = 1, color = Color.White)
                if (Vpn.up == p.name) Chip("Disconnect", true) { scope.launch { withContext(Dispatchers.IO) { Vpn.disconnect(ctx) } } }
                else Chip("Connect", false) { start(p) }
                Chip(if (auto == p.name) "Auto-connect: ON" else "Auto-connect: off", auto == p.name) {
                    auto = if (auto == p.name) "" else p.name; Store.sp.edit().putString("vpn_auto", auto).apply()
                }
                Chip("Delete", false) { Vpn.remove(p.name); profiles = Vpn.list() }
            }
        }
        Text("Add profile", fontWeight = FontWeight.Bold, color = Color.White)
        Chip("Import .conf from file", false) { picker.launch(arrayOf("*/*")) }
        Field("Direct link to .conf (optional)", url) { url = it }
        Chip("Import from link", false) {
            scope.launch {
                val r = runCatching { withContext(Dispatchers.IO) { Api.get(url) } }
                val txt = r.getOrNull()
                if (txt != null) { add(url.substringAfterLast('/').removeSuffix(".conf").ifBlank { "profile" }, txt) } else { note = "Download failed" }
            }
        }
        Field("Or paste: profile name", pname) { pname = it }
        Field("Paste full .conf text", paste) { paste = it }
        Chip("Add pasted profile", false) { add(pname.ifBlank { "profile" }, paste) }
        if (note.isNotBlank()) Text(note, fontSize = 13.sp, color = Color.White)
    }
}

