package com.iptv.tv

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import org.json.JSONArray
import org.json.JSONObject

data class VpnP(val name: String, val conf: String)

object Vpn {
    var up by mutableStateOf<String?>(null)
    var msg by mutableStateOf("")
    private var backend: GoBackend? = null
    private val tunnel = object : Tunnel {
        override fun getName() = "iptvvpn"
        override fun onStateChange(newState: Tunnel.State) {}
    }
    private fun be(c: Context) = backend ?: GoBackend(c.applicationContext).also { backend = it }
    fun list(): List<VpnP> {
        val a = JSONArray(Store.sp.getString("vpn", "[]") ?: "[]")
        return List(a.length()) { VpnP(a.getJSONObject(it).getString("n"), a.getJSONObject(it).getString("c")) }
    }
    private fun save(l: List<VpnP>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("n", it.name).put("c", it.conf)) }
        Store.sp.edit().putString("vpn", a.toString()).apply()
    }
    fun add(name: String, conf: String): String? {
        try { Config.parse(conf.byteInputStream()) } catch (e: Exception) { return e.message ?: "Invalid .conf file" }
        save(list().filter { it.name != name } + VpnP(name, conf)); return null
    }
    fun remove(name: String) { save(list().filter { it.name != name }); if (Store.sp.getString("vpn_auto", "") == name) Store.sp.edit().remove("vpn_auto").apply() }
    fun connect(c: Context, p: VpnP): Boolean = try {
        be(c).setState(tunnel, Tunnel.State.UP, Config.parse(p.conf.byteInputStream())); up = p.name; msg = ""; true
    } catch (e: Exception) { msg = e.message ?: "Connect failed"; false }
    fun disconnect(c: Context) { try { be(c).setState(tunnel, Tunnel.State.DOWN, null) } catch (e: Exception) {}; up = null }
}
