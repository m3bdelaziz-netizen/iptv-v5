package com.iptv.tv

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

data class Addon(val name: String, val kind: String, val ref: String)
data class Src(val label: String, val url: String)

object Addons {
    fun list(): List<Addon> {
        val a = JSONArray(Store.sp.getString("addons", "[]") ?: "[]")
        return List(a.length()) { val o = a.getJSONObject(it); Addon(o.getString("n"), o.getString("k"), o.getString("r")) }
    }
    private fun save(l: List<Addon>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("n", it.name).put("k", it.kind).put("r", it.ref)) }
        Store.sp.edit().putString("addons", a.toString()).apply()
    }
    fun remove(a: Addon) = save(list().filter { it != a })
    fun addUrl(u: String): String? {
        var url = u.trim().replaceFirst("stremio://", "https://")
        if (!url.endsWith("manifest.json")) url = url.trimEnd('/') + "/manifest.json"
        val m = JSONObject(Api.get(url))
        if (m.optJSONArray("resources") == null) return "Not a valid addon manifest"
        val base = url.removeSuffix("/manifest.json")
        save(list().filter { it.ref != base } + Addon(m.s("name").ifBlank { "Addon" }, "stremio", base))
        return null
    }
    fun addJson(text: String, fallback: String): String? {
        val j = try { JSONObject(text) } catch (e: Exception) { return "Invalid JSON" }
        if (j.has("resources")) return "This is an addon manifest. Add it with its link instead (the link is needed to fetch streams)."
        if (!j.has("streams")) return "Unknown JSON format (expected a \"streams\" list)"
        save(list() + Addon(j.s("name").ifBlank { fallback }, "local", text))
        return null
    }
    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
    suspend fun sources(title: String): List<Src> = coroutineScope {
        val ads = list()
        if (ads.isEmpty()) return@coroutineScope emptyList<Src>()
        val imdb = runCatching { withContext(Dispatchers.IO) { Api.imdb(title) } }.getOrNull()
        ads.map { a -> async(Dispatchers.IO) { runCatching { one(a, title, imdb) }.getOrDefault(emptyList()) } }.awaitAll().flatten().take(30)
    }
    private fun one(a: Addon, title: String, imdb: String?): List<Src> {
        if (a.kind == "stremio") {
            if (imdb == null) return emptyList()
            val arr = JSONObject(Api.get("${a.ref}/stream/movie/$imdb.json")).optJSONArray("streams") ?: return emptyList()
            return (0 until arr.length()).mapNotNull {
                val o = arr.getJSONObject(it); val u = o.s("url")
                if (!u.startsWith("http")) null else Src("${a.name} · " + (o.s("name") + " " + o.s("title")).replace("\n", " ").trim(), u)
            }
        }
        val arr = JSONObject(a.ref).optJSONArray("streams") ?: return emptyList()
        val nt = norm(title)
        return (0 until arr.length()).mapNotNull {
            val o = arr.getJSONObject(it); val u = o.s("url"); val t = norm(o.s("title"))
            val hit = (imdb != null && o.s("imdb") == imdb) || (t.isNotEmpty() && nt.isNotEmpty() && (t == nt || nt.contains(t) || t.contains(nt)))
            if (u.isBlank() || !hit) null else Src("${a.name} · " + o.s("name").ifBlank { o.s("title") }, u)
        }
    }
}
