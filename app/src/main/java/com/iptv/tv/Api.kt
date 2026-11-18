package com.iptv.tv

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Creds(val server: String, val user: String, val pass: String, val tmdb: String)
data class Cat(val id: String, val name: String)
data class Item(val id: String, val name: String, val img: String, val kind: String, val cat: String,
                val ext: String = "", val added: Long = 0, val rating: String = "", val epgId: String = "", val num: Int = 0) {
    val nn: String by lazy(LazyThreadSafetyMode.NONE) { nz(name) }
    val year: String by lazy(LazyThreadSafetyMode.NONE) { Regex("(19|20)\\d{2}").find(name)?.value ?: "" }
    val fkey: String get() = "$kind:$id"
}
data class Ep(val id: String, val title: String, val season: Int, val num: Int, val ext: String)
data class Meta(val backdrop: String?, val overview: String, val rating: String, val year: String, val id: Int = 0, val cast: String = "", val director: String = "", val cert: String = "", val trailer: String = "")
data class Guide(val cert: String, val tags: List<String>)

data class Prog(val start: Long, val stop: Long, val title: String)
data class Pl(val id: String, val name: String, val server: String, val user: String, val pass: String)

fun JSONObject.s(k: String): String = if (isNull(k)) "" else optString(k)
private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

object Store {
    lateinit var sp: SharedPreferences
    lateinit var dir: File
    val favs = mutableStateListOf<String>()

    /** Index of the playlist in use. Written with commit() so it survives the app being killed right after a change. */
    var active: Int
        get() = sp.getInt("active", 0)
        set(v) { sp.edit().putInt("active", v).commit() }

    private fun backup() = File(dir, "playlists.json")

    fun init(c: Context) {
        sp = c.getSharedPreferences("cfg", 0)
        dir = c.filesDir
        if (!sp.contains("pls")) {
            // Prefs were wiped (or first run after an update): restore from the backup file, else migrate the old single-server settings.
            val txt = runCatching { backup().readText() }.getOrNull()
            val n = if (txt == null) 0 else runCatching { JSONArray(txt).length() }.getOrDefault(0)
            if (txt != null && n > 0) sp.edit().putString("pls", txt).commit()
            else if (!sp.getString("server", "").isNullOrBlank())
                savePls(listOf(Pl("0", "Default", sp.getString("server", "")!!, sp.getString("user", "") ?: "", sp.getString("pass", "") ?: "")))
        }
    }
    fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    fun int(k: String, d: Int) = sp.getInt(k, d)
    fun str(k: String, d: String) = sp.getString(k, d) ?: d
    fun put(k: String, v: Any) {
        val e = sp.edit()
        when (v) { is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v); is Long -> e.putLong(k, v); else -> e.putString(k, v.toString()) }
        e.apply()
    }
    fun pls(): List<Pl> {
        val a = runCatching { JSONArray(sp.getString("pls", "[]") ?: "[]") }.getOrDefault(JSONArray())
        return List(a.length()) { val o = a.getJSONObject(it); Pl(o.s("id"), o.s("n"), o.s("s"), o.s("u"), o.s("p")) }
    }
    fun savePls(l: List<Pl>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("n", it.name).put("s", it.server).put("u", it.user).put("p", it.pass)) }
        sp.edit().putString("pls", a.toString()).commit()
        runCatching { backup().writeText(a.toString()) }
    }
    /** The playlist in use (falls back to the first one if the saved index is stale). */
    fun cur(): Pl? { val l = pls(); return l.getOrNull(active) ?: l.firstOrNull() }
    fun pid(): String = cur()?.id ?: "0"

    /** Deletes playlist [i] together with its cached library, favourites and history; keeps the active index pointing at the same playlist. */
    fun removePl(i: Int) {
        val l = pls()
        if (i !in l.indices) return
        val gone = l[i]
        val rest = l.filterIndexed { j, _ -> j != i }
        var a = active
        if (i < a) a--
        a = a.coerceIn(0, maxOf(0, rest.size - 1))
        savePls(rest)
        active = a
        sp.edit().remove("fav2_${gone.id}").remove("favs_${gone.id}").remove("hist_${gone.id}")
            .remove("sel_${gone.id}_live").remove("sel_${gone.id}_movie").remove("sel_${gone.id}_series").commit()
        runCatching { dir.listFiles { f -> f.name.startsWith("c_${gone.id}_") }?.forEach { it.delete() } }
    }

    fun loadFavs() {
        favs.clear()
        val id = pid()
        val cur = sp.getStringSet("fav2_$id", null)
        if (cur != null) favs.addAll(cur)
        else sp.getStringSet("favs_$id", emptySet())?.forEach { favs.add("live:$it") }
    }
    fun isFav(i: Item) = favs.contains(i.fkey)
    fun fav(i: Item) {
        if (!favs.remove(i.fkey)) favs.add(i.fkey)
        sp.edit().putStringSet("fav2_" + pid(), favs.toSet()).apply()
    }
    fun clearFavs() { favs.clear(); sp.edit().putStringSet("fav2_" + pid(), emptySet()).apply() }
}

object Api {
    var c = Creds("", "", "", "")
    private val cache: MutableMap<String, Meta> = java.util.Collections.synchronizedMap(HashMap<String, Meta>())

    fun normUrl(server: String): String { val s = server.trim().trimEnd('/'); return if (s.startsWith("http", true)) s else "http://$s" }
    fun base(): String = normUrl(c.server)

    fun get(url: String, rt: Int = 90000): String {
        val cn = URL(url).openConnection() as HttpURLConnection
        cn.connectTimeout = minOf(15000, rt); cn.readTimeout = rt
        cn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV)")
        try { return cn.inputStream.bufferedReader().use { it.readText() } } finally { cn.disconnect() }
    }
    private fun auth(u: String = c.user, p: String = c.pass) = "username=${enc(u)}&password=${enc(p)}"
    /** Xtream "player_api.php" call. [extra] is a raw query tail such as "vod_id=12". */
    fun act(a: String, extra: String = "") = get("${base()}/player_api.php?${auth()}&action=$a${if (extra.isNotEmpty()) "&$extra" else ""}")

    // ------------------------------------------------------------------ login
    /** True when the server accepts the account. Throws with a readable message on network errors / expired accounts. */
    fun test(server: String, user: String, pass: String): Boolean {
        val j = JSONObject(get("${normUrl(server)}/player_api.php?${auth(user, pass)}", 20000))
        val ui = j.optJSONObject("user_info") ?: return false
        val ok = ui.optInt("auth", 0) == 1 || ui.optBoolean("auth", false)
        if (!ok) return false
        val st = ui.s("status")
        if (st.equals("Expired", true) || st.equals("Disabled", true) || st.equals("Banned", true)) throw IOException("Account is $st")
        return true
    }
    fun login(): Boolean = test(c.server, c.user, c.pass)

    // ------------------------------------------------------------------ stream urls
    private fun p(s: String) = Uri.encode(s)
    fun liveUrls(id: String): List<String> {
        val b = "${base()}/live/${p(c.user)}/${p(c.pass)}/$id"
        return if (Store.str("fmt", "m3u8") == "ts") listOf("$b.ts", "$b.m3u8") else listOf("$b.m3u8", "$b.ts")
    }
    fun vodUrl(id: String, ext: String) = "${base()}/movie/${p(c.user)}/${p(c.pass)}/$id.${ext.ifBlank { "mp4" }}"
    fun epUrl(id: String, ext: String) = "${base()}/series/${p(c.user)}/${p(c.pass)}/$id.${ext.ifBlank { "mp4" }}"

    // ------------------------------------------------------------------ library parsing
    fun parseCats(text: String): List<Cat> {
        val a = JSONArray(text)
        val out = ArrayList<Cat>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.s("category_id")
            if (id.isNotBlank()) out.add(Cat(id, o.s("category_name")))
        }
        return out
    }

    fun parseItems(text: String, kind: String, idKey: String, imgKey: String): List<Item> {
        val a = JSONArray(text)
        val out = ArrayList<Item>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.s(idKey)
            if (id.isBlank()) continue
            val added = o.s("added").ifBlank { o.s("last_modified") }.toLongOrNull() ?: 0L
            val rating = o.s("rating").ifBlank { o.s("rating_5based") }
            out.add(Item(id, o.s("name"), o.s(imgKey), kind, o.s("category_id"), o.s("container_extension"),
                added, rating, o.s("epg_channel_id"), o.optInt("num", 0)))
        }
        return out
    }

    fun episodes(seriesId: String): List<Ep> {
        val j = JSONObject(act("get_series_info", "series_id=$seriesId"))
        val e = j.opt("episodes")
        val out = mutableListOf<Ep>()
        fun addArr(a: JSONArray, fallbackSeason: Int) {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val n = o.optInt("episode_num", i + 1)
                val season = o.optInt("season", 0).takeIf { it > 0 } ?: fallbackSeason
                out.add(Ep(o.s("id"), o.s("title").ifBlank { "Episode $n" }, season, n, o.s("container_extension").ifBlank { "mp4" }))
            }
        }
        when (e) {
            is JSONObject -> e.keys().asSequence().toList().forEach { k -> e.optJSONArray(k)?.let { addArr(it, k.toIntOrNull() ?: 1) } }
            is JSONArray -> if (e.length() > 0 && e.opt(0) is JSONArray) {
                for (i in 0 until e.length()) e.optJSONArray(i)?.let { addArr(it, i + 1) }
            } else addArr(e, 1)
        }
        return out.sortedWith(compareBy({ it.season }, { it.num }))
    }

    private fun b64(s: String) = runCatching { String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(s)

    /** Next programmes of a live channel, as "HH:mm  Title" lines. */
    fun epg(streamId: String): List<String> {
        val j = JSONObject(act("get_short_epg", "stream_id=$streamId&limit=3"))
        val a = j.optJSONArray("epg_listings") ?: return emptyList()
        val f = SimpleDateFormat("HH:mm", Locale.getDefault())
        val out = ArrayList<String>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val t = b64(o.s("title")).trim()
            if (t.isBlank()) continue
            val st = o.s("start_timestamp").toLongOrNull()
            out.add((if (st != null) f.format(Date(st * 1000)) + "  " else "") + t)
        }
        return out
    }

    // ------------------------------------------------------------------ TMDB / provider details
    private const val TMDB = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p/w1280"

    /** First TMDB search hit for [name] (year digits removed), or null. */
    private fun tmdbFind(name: String, series: Boolean): JSONObject? {
        val q = enc(name.replace(Regex("\\(?(19|20)\\d{2}\\)?"), "").trim())
        val type = if (series) "tv" else "movie"
        val arr = JSONObject(get("$TMDB/search/$type?api_key=${c.tmdb}&query=$q", 15000)).optJSONArray("results") ?: return null
        return if (arr.length() > 0) arr.getJSONObject(0) else null
    }

    private fun certOf(detail: JSONObject, series: Boolean): String {
        val results = (if (series) detail.optJSONObject("content_ratings") else detail.optJSONObject("release_dates"))
            ?.optJSONArray("results") ?: return ""
        var any = ""
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val cert = if (series) o.s("rating") else {
                val rd = o.optJSONArray("release_dates")
                var r = ""
                if (rd != null) for (k in 0 until rd.length()) { val x = rd.optJSONObject(k)?.s("certification").orEmpty(); if (x.isNotBlank()) { r = x; break } }
                r
            }
            if (cert.isBlank()) continue
            if (o.s("iso_3166_1") == "US") return cert
            if (any.isEmpty()) any = cert
        }
        return any
    }

    /** Details for the detail page: provider info first (vod/series info call), TMDB fills the gaps when a key is set. Never null. */
    fun tmdb(name: String, series: Boolean, id: String = ""): Meta {
        val ck = "$series:$id:$name"
        cache[ck]?.let { return it }
        var backdrop = ""; var overview = ""; var rating = ""; var year = ""
        var cast = ""; var director = ""; var cert = ""; var trailer = ""

        if (id.isNotBlank()) runCatching {
            val j = if (series) JSONObject(act("get_series_info", "series_id=$id")) else JSONObject(act("get_vod_info", "vod_id=$id"))
            val info = j.optJSONObject("info") ?: JSONObject()
            overview = info.s("plot").ifEmpty { info.s("description") }
            rating = info.s("rating").ifEmpty { info.s("kinopoisk_rating") }
            year = info.s("releasedate").ifEmpty { info.s("releaseDate") }.ifEmpty { info.s("year") }.take(4)
            backdrop = info.optJSONArray("backdrop_path")?.optString(0) ?: ""
            cast = info.s("cast"); director = info.s("director"); trailer = info.s("youtube_trailer")
            cert = info.s("age").ifEmpty { info.s("mpaa") }
        }

        if (c.tmdb.isNotBlank()) runCatching {
            val hit = tmdbFind(name, series)
            if (hit != null) {
                val tid = hit.getInt("id")
                val type = if (series) "tv" else "movie"
                if (overview.isBlank()) overview = hit.s("overview")
                if (rating.isBlank()) rating = hit.optDouble("vote_average", 0.0).let { if (it > 0) "%.1f".format(Locale.US, it) else "" }
                if (year.isBlank()) year = hit.s("release_date").ifEmpty { hit.s("first_air_date") }.take(4)
                if (backdrop.isBlank()) hit.s("backdrop_path").takeIf { it.isNotBlank() }?.let { backdrop = IMG + it }
                val d = JSONObject(get("$TMDB/$type/$tid?api_key=${c.tmdb}&append_to_response=credits,release_dates,content_ratings", 15000))
                if (cast.isBlank()) d.optJSONObject("credits")?.optJSONArray("cast")?.let { ca ->
                    cast = (0 until minOf(5, ca.length())).joinToString(", ") { ca.getJSONObject(it).s("name") }
                }
                if (cert.isBlank()) cert = certOf(d, series)
            }
        }

        if (overview.isBlank()) overview = "No overview available."
        val m = Meta(backdrop.ifBlank { null }, overview, rating, year, 0, cast, director, cert, trailer)
        cache[ck] = m
        return m
    }

    /** IMDb id for a film title (needs the TMDB key), used by Stremio-style addons. */
    fun imdb(title: String): String? {
        if (c.tmdb.isBlank()) return null
        val hit = tmdbFind(title, false) ?: return null
        val x = JSONObject(get("$TMDB/movie/${hit.getInt("id")}/external_ids?api_key=${c.tmdb}", 15000)).s("imdb_id")
        return x.ifBlank { null }
    }

    private val tagWords = listOf(
        "violence" to "Violence", "gore" to "Gore", "blood" to "Blood", "nudity" to "Nudity", "sex" to "Sexual content",
        "drug" to "Drugs", "alcohol" to "Alcohol", "profanity" to "Strong language", "swear" to "Strong language",
        "torture" to "Torture", "rape" to "Sexual violence", "suicide" to "Suicide", "smoking" to "Smoking"
    )

    /** Age rating + content tags from TMDB, or null when there is nothing to show. */
    fun guide(title: String, series: Boolean): Guide? {
        if (c.tmdb.isBlank()) return null
        val hit = tmdbFind(title, series) ?: return null
        val tid = hit.getInt("id")
        val type = if (series) "tv" else "movie"
        val d = JSONObject(get("$TMDB/$type/$tid?api_key=${c.tmdb}&append_to_response=release_dates,content_ratings,keywords", 15000))
        val cert = certOf(d, series)
        val kw = d.optJSONObject("keywords")
        val arr = kw?.optJSONArray(if (series) "results" else "keywords")
        val tags = linkedSetOf<String>()
        if (arr != null) for (i in 0 until arr.length()) {
            val n = arr.getJSONObject(i).s("name").lowercase()
            tagWords.forEach { (w, t) -> if (n.contains(w)) tags.add(t) }
        }
        return if (cert.isBlank() && tags.isEmpty()) null else Guide(cert, tags.toList())
    }
}
