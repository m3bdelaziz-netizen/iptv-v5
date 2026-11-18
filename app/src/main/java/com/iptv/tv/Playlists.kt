@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.iptv.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun hostOf(s: String) = s.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')

@Composable
private fun PlaylistRow(p: Pl, active: Boolean, fr: FocusRequester?, onUse: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Theme.card, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(46.dp).background(if (active) Theme.accent else Theme.chip, CircleShape), Alignment.Center) {
            Text(p.name.trim().take(1).uppercase().ifBlank { "•" }, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                color = if (active) Theme.onAccent else Color.White)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.name, Modifier.weight(1f, fill = false), fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
                if (active) Box(Modifier.background(Theme.accent.copy(alpha = 0.25f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("ACTIVE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Theme.accent)
                }
            }
            Text(hostOf(p.server) + "   ·   " + p.user, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Theme.muted)
        }
        Chip(if (active) "✓ In use" else "Use", active, if (fr != null) Modifier.focusRequester(fr) else Modifier) { onUse() }
        Chip("Remove", false) { onRemove() }
    }
}

/**
 * Manage playlists inside the app: switch, remove (with confirmation) and add a new Xtream login.
 * Everything is saved the moment it changes, so the app reopens straight into the last used playlist.
 */
@Composable
fun PlaylistScreen(onChanged: () -> Unit, goPhone: () -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pls by remember { mutableStateOf(Store.pls()) }
    var active by remember { mutableStateOf(Store.active.coerceIn(0, maxOf(0, Store.pls().size - 1))) }
    var confirm by remember { mutableStateOf<Int?>(null) }
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val listFR = remember { FocusRequester() }
    val nameFR = remember { FocusRequester() }

    LaunchedEffect(pls.size) {
        delay(150)
        runCatching { if (pls.isEmpty()) nameFR.requestFocus() else listFR.requestFocus() }
    }

    fun use(i: Int) {
        if (i == active) { onClose(); return }
        Store.active = i; active = i; onChanged()
    }
    fun remove(i: Int) {
        val wasActive = i == active
        Store.removePl(i)
        pls = Store.pls(); active = Store.active.coerceIn(0, maxOf(0, pls.size - 1))
        if (wasActive) {
            if (pls.isEmpty()) { Api.c = creds(); Store.loadFavs(); Repo.reset() } else onChanged()
        }
    }
    fun add() {
        if (busy) return
        val srv = Api.normUrl(server); val usr = user.trim()
        if (server.isBlank() || usr.isEmpty() || pass.isEmpty()) { msg = "Fill server, username and password"; return }
        busy = true; msg = "Connecting..."
        scope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { Api.test(srv, usr, pass) } }
            busy = false
            if (r.getOrNull() == true) {
                val l = Store.pls() + Pl(System.currentTimeMillis().toString(), name.trim().ifBlank { "Playlist ${Store.pls().size + 1}" }, srv, usr, pass)
                Store.savePls(l); Store.active = l.size - 1
                pls = l; active = l.size - 1
                name = ""; server = ""; user = ""; pass = ""; msg = ""
                onChanged()
            } else {
                msg = "Login failed: ${r.exceptionOrNull()?.message ?: "check server / user / password"}"
            }
        }
    }

    Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        // ---------------- saved playlists
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Playlists", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(if (pls.isEmpty()) "Nothing saved yet" else "${pls.size} saved  ·  your choice is remembered next time", fontSize = 12.sp, color = Theme.muted)
            Spacer(Modifier.height(8.dp))
            if (pls.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f).background(Theme.card.copy(alpha = 0.5f), RoundedCornerShape(16.dp)), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("No playlists yet", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Add one on the right, or set it up from your phone.", fontSize = 12.sp, color = Theme.muted)
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    itemsIndexed(pls, key = { _, p -> p.id }) { i, p ->
                        PlaylistRow(p, i == active, if (i == active) listFR else null, { use(i) }, { confirm = i })
                    }
                }
            }
        }

        // ---------------- add form
        Column(
            Modifier.width(380.dp).fillMaxHeight().background(Theme.panel, RoundedCornerShape(18.dp))
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Add playlist", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Xtream Codes login from your provider", fontSize = 11.sp, color = Theme.muted)
            Field("Name (optional)", name, false, Modifier.focusRequester(nameFR), 1f) { name = it; msg = "" }
            Field("Server (http://host:port)", server, false, Modifier, 1f) { server = it; msg = "" }
            Field("Username", user, false, Modifier, 1f) { user = it; msg = "" }
            Field("Password", pass, true, Modifier, 1f) { pass = it; msg = "" }
            if (msg.isNotBlank()) {
                val bad = msg.startsWith("Login failed") || msg.startsWith("Fill")
                Text(msg, fontSize = 12.sp, color = if (bad) Color(0xFFFF6B6B) else Theme.muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(if (busy) "Connecting..." else "Add & connect", true) { add() }
                Chip("Setup from phone (QR)", false) { goPhone() }
            }
        }
    }

    val ci = confirm
    val cp = if (ci != null) pls.getOrNull(ci) else null
    if (ci != null && cp != null) {
        OptionsDialog("Remove \"${cp.name}\"?", listOf(
            MenuAction("Cancel") {},
            MenuAction("✕ Remove playlist") { remove(ci) }
        )) { confirm = null }
    }
}
