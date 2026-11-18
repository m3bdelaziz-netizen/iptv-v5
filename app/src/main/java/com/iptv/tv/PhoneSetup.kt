@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.iptv.tv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Tiny one-shot web server so a phone can send Xtream Codes details and the TMDB key to the TV.
 * It only runs while the "Setup from phone" screen is open, listens on the local network only,
 * and every request must carry the random token that is inside the QR code.
 */
object PhoneSetup {
    var status by mutableStateOf("")
    var plAdded by mutableStateOf(0)
    @Volatile private var ss: ServerSocket? = null
    @Volatile private var token = ""

    private fun lanIp(): String? {
        val all = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return null
        var fallback: String? = null
        for (ni in all) {
            val up = runCatching { ni.isUp && !ni.isLoopback }.getOrDefault(false)
            if (!up) continue
            for (a in ni.inetAddresses) {
                if (a is Inet4Address && !a.isLoopbackAddress) {
                    val h = a.hostAddress ?: continue
                    if (a.isSiteLocalAddress) return h
                    if (fallback == null) fallback = h
                }
            }
        }
        return fallback
    }

    /** Starts the server and returns the address to put in the QR code (null = no network). Call off the main thread. */
    fun start(): String? {
        stop()
        val ip = lanIp() ?: return null
        val s = runCatching { ServerSocket(8765) }.getOrElse { runCatching { ServerSocket(0) }.getOrNull() } ?: return null
        token = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
        ss = s; status = ""; plAdded = 0
        Thread({ loop(s) }, "phone-setup").apply { isDaemon = true; start() }
        return "http://$ip:${s.localPort}/?t=$token"
    }

    fun stop() {
        val s = ss; ss = null
        if (s != null) runCatching { s.close() }
    }

    private fun loop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (e: Exception) { break }
            try { c.soTimeout = 6000; handle(c) } catch (e: Exception) { /* bad request: ignore */ } finally { runCatching { c.close() } }
        }
    }

    private fun readLine(i: InputStream): String? {
        val b = StringBuilder()
        while (b.length < 4096) {
            val x = i.read()
            if (x < 0) return if (b.isEmpty()) null else b.toString()
            if (x == '\n'.code) return b.toString().trimEnd('\r')
            b.append(x.toChar())
        }
        return null
    }

    private fun handle(c: Socket) {
        val ins = BufferedInputStream(c.getInputStream())
        val out = c.getOutputStream()
        val first = readLine(ins) ?: return
        val p = first.split(" ")
        if (p.size < 2) return
        var len = 0
        var n = 0
        while (n++ < 60) {
            val h = readLine(ins) ?: break
            if (h.isEmpty()) break
            if (h.startsWith("content-length:", true)) len = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val path = p[1].substringBefore('?')
        val tok = p[1].substringAfter('?', "").split('&').firstOrNull { it.startsWith("t=") }?.substring(2) ?: ""
        if (tok != token) { send(out, 403, page("Invalid link", "<p>Scan the QR code on the TV again.</p>")); return }
        if (p[0] == "POST" && path == "/save") {
            val size = len.coerceIn(0, 8192)
            val buf = ByteArray(size)
            var r = 0
            while (r < size) { val k = ins.read(buf, r, size - r); if (k < 0) break; r += k }
            val f = HashMap<String, String>()
            String(buf, 0, r, Charsets.UTF_8).split('&').forEach { kv ->
                f[URLDecoder.decode(kv.substringBefore('='), "UTF-8")] = URLDecoder.decode(kv.substringAfter('=', "").replace("+", " "), "UTF-8")
            }
            val (ok, msg) = save(f)
            val back = "<p><a href=\"/?t=$token\">&larr; Back</a></p>"
            send(out, 200, page(if (ok) "&#10003; Saved" else "Not saved", "<p>$msg</p>" + (if (ok) "<p>You can close this page now.</p>" else back)))
        } else {
            send(out, 200, page("Send to TV", form()))
        }
    }

    private fun save(f: Map<String, String>): Pair<Boolean, String> {
        val msgs = mutableListOf<String>()
        var bad = false
        val tmdb = f["tmdb"]?.trim().orEmpty()
        val server = f["server"]?.trim().orEmpty()
        val user = f["user"]?.trim().orEmpty()
        val pass = f["pass"].orEmpty()
        if (tmdb.isNotEmpty()) { Store.put("tmdb", tmdb); Api.c = Api.c.copy(tmdb = tmdb); msgs += "TMDB key saved" }
        if (server.isNotEmpty() || user.isNotEmpty() || pass.isNotEmpty()) {
            if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                bad = true; msgs += "Fill server, username and password together"
            } else {
                val ok = runCatching { Api.test(server, user, pass) }
                if (ok.getOrNull() == true) {
                    val name = f["name"]?.trim().orEmpty()
                    val l = Store.pls() + Pl(System.currentTimeMillis().toString(), name.ifBlank { "Playlist ${Store.pls().size + 1}" }, Api.normUrl(server), user, pass)
                    Store.savePls(l); Store.active = l.size - 1
                    msgs += "Playlist added"; plAdded++
                } else {
                    bad = true; msgs += "Login failed: " + (ok.exceptionOrNull()?.message ?: "check server, username and password")
                }
            }
        }
        if (msgs.isEmpty()) { bad = true; msgs += "Nothing to save" }
        status = msgs.joinToString(" · ")
        return Pair(!bad, msgs.joinToString("<br>") { esc(it) })
    }

    private fun send(o: OutputStream, code: Int, html: String) {
        val b = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Forbidden"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${b.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        o.write(head.toByteArray(Charsets.UTF_8)); o.write(b); o.flush()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(title: String, body: String) = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>IPTV TV setup</title>
<style>body{font-family:system-ui,sans-serif;background:#0b0e1a;color:#fff;margin:0;padding:20px;max-width:480px}
h2{margin-top:0}label{display:block;margin:14px 0 4px;font-size:14px;color:#b8c0e0}
input{width:100%;box-sizing:border-box;padding:12px;font-size:16px;border-radius:8px;border:1px solid #3b4366;background:#1a1f30;color:#fff}
button{margin-top:22px;width:100%;padding:14px;font-size:17px;border:0;border-radius:8px;background:#e50914;color:#fff}
a{color:#8ab4ff}hr{border:0;border-top:1px solid #2a3050;margin:22px 0 4px}</style></head>
<body><h2>$title</h2>$body</body></html>"""

    private fun form() = """<form method="post" action="/save?t=$token">
<label>Playlist name / اسم القائمة (optional)</label><input name="name" autocomplete="off">
<label>Server / السيرفر</label><input name="server" placeholder="http://host:port" inputmode="url" autocapitalize="off" autocorrect="off" autocomplete="off">
<label>Username / اسم المستخدم</label><input name="user" autocapitalize="off" autocorrect="off" autocomplete="off">
<label>Password / كلمة المرور</label><input name="pass" autocapitalize="off" autocorrect="off" autocomplete="off">
<hr><label>TMDB API key v3 (optional) / مفتاح TMDB</label><input name="tmdb" autocapitalize="off" autocorrect="off" autocomplete="off">
<button type="submit">Save to TV / حفظ على التلفزيون</button></form>"""
}

@Composable
fun PhoneSetupScreen(onPlaylistAdded: () -> Unit) {
    var url by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            val u = withContext(Dispatchers.IO) { PhoneSetup.start() }
            if (u == null) failed = true else url = u
        } catch (e: CancellationException) { PhoneSetup.stop(); throw e }
    }
    DisposableEffect(Unit) { onDispose { PhoneSetup.stop() } }
    LaunchedEffect(PhoneSetup.plAdded) { if (PhoneSetup.plAdded > 0) onPlaylistAdded() }
    val qr = remember(url) { url?.let { u -> runCatching { QRCodeWriter().encode(u, BarcodeFormat.QR_CODE, 0, 0) }.getOrNull() } }

    Row(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(270.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp), Alignment.Center) {
            if (qr != null) Canvas(Modifier.fillMaxSize()) {
                val n = qr.width
                val cell = size.width / n
                for (y in 0 until n) for (x in 0 until n) if (qr.get(x, y))
                    drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
            } else Text(if (failed) "No Wi-Fi / network found" else "Preparing...", color = Color.Black, fontSize = 13.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Setup from your phone", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("1. Connect your phone to the same Wi-Fi as this TV.", fontSize = 14.sp)
            Text("2. Scan the QR code with your phone camera.", fontSize = 14.sp)
            Text("3. Enter the Xtream server, username, password and/or TMDB key, then tap Save.", fontSize = 14.sp)
            url?.let { Text(it, fontSize = 12.sp) }
            if (PhoneSetup.status.isNotBlank()) Text(PhoneSetup.status, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Works on your home network only. Keep this screen open until you save.", fontSize = 11.sp)
        }
    }
}
