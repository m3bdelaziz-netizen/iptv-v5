package com.iptv.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/** Library in memory + on-disk JSON cache so the app opens instantly and refreshes in the background. */
object Repo {
    var liveCats = listOf<Cat>(); var live = listOf<Item>(); var radioCats = listOf<Cat>(); var radio = listOf<Item>()
    var movieCats = listOf<Cat>(); var movies = listOf<Item>(); var serCats = listOf<Cat>(); var series = listOf<Item>()
    /** Every category the provider offers (before the "choose categories" filter), used by the picker screen. */
    var allLiveCats = listOf<Cat>(); var allMovieCats = listOf<Cat>(); var allSerCats = listOf<Cat>()
    var ready by mutableStateOf(false)
    var error by mutableStateOf("")
    var refreshing by mutableStateOf(false)
    var version by mutableStateOf(0)

    private val names = listOf("get_live_categories", "get_live_streams", "get_vod_categories", "get_vod_streams",
        "get_series_categories", "get_series")

    private fun selKey(kind: String) = "sel_${Store.pid()}_$kind"
    /** Saved category ids for live / movie / series, or null = keep everything. */
    fun selection(kind: String): Set<String>? =
        Store.str(selKey(kind), "").takeIf { it.isNotEmpty() }?.split(",")?.toHashSet()
    fun saveSelection(kind: String, ids: Set<String>?) {
        if (ids == null) Store.sp.edit().remove(selKey(kind)).apply() else Store.put(selKey(kind), ids.joinToString(","))
    }
    private fun validSel(kind: String, cats: List<Cat>): Set<String>? =
        selection(kind)?.takeIf { s -> cats.any { it.id in s } }   // ignore a stale selection that matches nothing

    private fun isRadio(n: String) = n.contains("radio", true) || n.contains("راديو") || n.contains("إذاعة") || n.contains("اذاعة")
    private fun file(pid: String, n: String) = File(Store.dir, "c_${pid}_$n.json")

    fun reset() {
        ready = false; error = ""; refreshing = false
        allLiveCats = emptyList(); allMovieCats = emptyList(); allSerCats = emptyList()
        liveCats = emptyList(); live = emptyList(); radioCats = emptyList(); radio = emptyList()
        movieCats = emptyList(); movies = emptyList(); serCats = emptyList(); series = emptyList()
        version++
    }

    private fun applyRaw(raw: Map<String, String?>) {
        val cats0 = Api.parseCats(raw["get_live_categories"] ?: "[]")
        allLiveCats = cats0
        val selL = validSel("live", cats0)
        val cats = if (selL == null) cats0 else cats0.filter { it.id in selL }
        val all0 = Api.parseItems(raw["get_live_streams"] ?: "[]", "live", "stream_id", "stream_icon")
        val all = if (selL == null) all0 else all0.filter { it.cat in selL }
        val rid = cats.filter { isRadio(it.name) }.map { it.id }.toSet()
        liveCats = cats.filter { it.id !in rid }; radioCats = cats.filter { it.id in rid }
        live = all.filter { it.cat !in rid }
        radio = all.filter { it.cat in rid }.map { it.copy(kind = "radio") }
        allMovieCats = runCatching { Api.parseCats(raw["get_vod_categories"] ?: "[]") }.getOrDefault(emptyList())
        val selM = validSel("movie", allMovieCats)
        movieCats = if (selM == null) allMovieCats else allMovieCats.filter { it.id in selM }
        val mv = runCatching { Api.parseItems(raw["get_vod_streams"] ?: "[]", "movie", "stream_id", "stream_icon") }.getOrDefault(emptyList())
        movies = if (selM == null) mv else mv.filter { it.cat in selM }
        allSerCats = runCatching { Api.parseCats(raw["get_series_categories"] ?: "[]") }.getOrDefault(emptyList())
        val selS = validSel("series", allSerCats)
        serCats = if (selS == null) allSerCats else allSerCats.filter { it.id in selS }
        val sr = runCatching { Api.parseItems(raw["get_series"] ?: "[]", "series", "series_id", "cover") }.getOrDefault(emptyList())
        series = if (selS == null) sr else sr.filter { it.cat in selS }
        version++
    }

    /** Re-applies the category selection from the on-disk cache (no network, no reload of the provider). */
    suspend fun reapply() {
        val pid = Store.pid()
        val raw = withContext(Dispatchers.IO) {
            val m = HashMap<String, String?>()
            names.forEach { n -> val f = file(pid, n); m[n] = if (f.exists()) runCatching { f.readText() }.getOrNull() else null }
            m
        }
        if (raw["get_live_streams"] == null) { load(true); return }
        withContext(Dispatchers.Default) { runCatching { applyRaw(raw) } }
    }

    /** Reads the on-disk library and applies it. Returns the cache's timestamp, or null when there is no usable cache. */
    private suspend fun loadCache(): Long? {
        val pid = Store.pid()
        val cached = withContext(Dispatchers.IO) {
            val m = HashMap<String, String?>()
            names.forEach { n -> val f = file(pid, n); m[n] = if (f.exists()) runCatching { f.readText() }.getOrNull() else null }
            if (m["get_live_streams"] == null) null else Pair(m, file(pid, "get_live_streams").lastModified())
        } ?: return null
        val ok = withContext(Dispatchers.Default) { runCatching { applyRaw(cached.first) }.isSuccess }
        if (!ok) return null
        ready = true
        return cached.second
    }

    /** Shows the saved library immediately (no network) so the app opens straight into it. */
    suspend fun showCached() { if (!ready) loadCache() }

    /** force = true always hits the network. Otherwise cached data is used and refreshed only when older than 6 hours. */
    suspend fun load(force: Boolean = false) {
        val pid = Store.pid()
        error = ""
        var fresh = false
        if (!ready) {
            val ts = loadCache()
            if (ts != null) fresh = System.currentTimeMillis() - ts < 6 * 3600_000L
        } else {
            fresh = !force
        }
        if (fresh && !force) return
        refreshing = true
        try {
            val raw = withContext(Dispatchers.IO) {
                coroutineScope {
                    val jobs = names.map { n -> n to async { if (n == "get_live_categories" || n == "get_live_streams") Api.act(n) else runCatching { Api.act(n) }.getOrNull() } }
                    jobs.associate { (n, j) -> n to j.await() }
                }
            }
            withContext(Dispatchers.IO) { raw.forEach { (n, t) -> if (t != null) runCatching { file(pid, n).writeText(t) } } }
            withContext(Dispatchers.Default) { applyRaw(raw) }
            ready = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!ready) error = e.message ?: "Load failed"
        } finally {
            refreshing = false
        }
    }
}
