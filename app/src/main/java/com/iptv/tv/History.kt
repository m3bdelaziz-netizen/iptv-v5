package com.iptv.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** One "continue watching" entry. [sid] is the series id for episodes (blank for movies). */
data class Hist(
    val key: String, val title: String, val img: String, val url: String, val kind: String,
    val pos: Long, val dur: Long, val ts: Long, val sid: String = ""
) {
    val progress: Float get() = if (dur > 0L) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
}

/** Watch history, kept per playlist in SharedPreferences. [version] changes on every edit so Compose can react. */
object History {
    var version by mutableStateOf(0)
    private var pid = ""
    private var list = mutableListOf<Hist>()

    private fun ensure() {
        val p = Store.pid()
        if (p != pid) { pid = p; list = read(p) }
    }

    private fun read(p: String): MutableList<Hist> {
        val out = mutableListOf<Hist>()
        runCatching {
            val a = JSONArray(Store.sp.getString("hist_$p", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out += Hist(o.s("k"), o.s("t"), o.s("i"), o.s("u"), o.s("kd"), o.optLong("p"), o.optLong("d"), o.optLong("ts"), o.s("s"))
            }
        }
        return out
    }

    private fun write() {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("k", it.key).put("t", it.title).put("i", it.img).put("u", it.url)
                .put("kd", it.kind).put("p", it.pos).put("d", it.dur).put("ts", it.ts).put("s", it.sid))
        }
        Store.sp.edit().putString("hist_$pid", a.toString()).apply()
        version++
    }

    fun all(): List<Hist> { ensure(); return list.sortedByDescending { it.ts } }
    fun get(key: String): Hist? { ensure(); return list.firstOrNull { it.key == key } }

    fun put(h: Hist) {
        ensure()
        list.removeAll { it.key == h.key }
        list.add(h)
        if (list.size > 200) list = list.sortedByDescending { it.ts }.take(200).toMutableList()
        write()
    }
    fun remove(key: String) { ensure(); if (list.removeAll { it.key == key }) write() }
    fun removeWhere(f: (Hist) -> Boolean) { ensure(); if (list.removeAll(f)) write() }
    fun clear() { ensure(); list.clear(); write() }
}
