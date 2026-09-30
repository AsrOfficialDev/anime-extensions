package eu.kanade.tachiyomi.animeextension.all.dhakaflix

import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale

class DhakaFlix :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "DhakaFlix Anime"
    override val baseUrl = "http://172.16.50.10"
    override val lang = "all"
    override val supportsLatest = true

    private val rootPath = "/DHAKA-FLIX-10/Anime%20%26%20Cartoon%20TV%20Series/"
    private val videoExt = setOf("mkv", "mp4", "avi", "webm", "m4v", "mov", "ts", "flv")
    private val maxDepth = 3 // how many folder levels deep to look for episodes
    private val pageSize = 30

    private val preferences by getPreferencesLazy()

    // ---------- Directory listing helpers ----------

    private class Entry(val url: HttpUrl, val isDir: Boolean, val modified: String?) {
        val name: String get() = url.pathSegments.last { it.isNotEmpty() }
        val relPath: String get() = url.encodedPath
        val modifiedMillis: Long get() = modified?.let {
            runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(it)?.time }.getOrNull()
        } ?: 0L
    }

    private fun String.natKey() = lowercase().replace(Regex("\\d+")) { it.value.padStart(6, '0') }

    private fun parseEntries(response: Response): List<Entry> = parseEntries(response.asJsoup(), response.request.url)

    // Returns only direct children of the current folder (skips parent/sort links).
    // h5ai (this server's software) always includes a plain #fallback table with a
    // "Last modified" column, even with JS off, so we read that when it's present.
    private fun parseEntries(doc: Document, current: HttpUrl): List<Entry> {
        val cur = current.pathSegments.filter { it.isNotEmpty() }

        fun toEntry(link: HttpUrl, modified: String?): Entry? {
            if (link.host != current.host) return null
            val seg = link.pathSegments.filter { it.isNotEmpty() }
            if (seg.size <= cur.size || seg.take(cur.size) != cur) return null
            return Entry(link, link.encodedPath.endsWith("/"), modified)
        }

        val rows = doc.select("#fallback tr").mapNotNull { row ->
            val a = row.selectFirst("td.fb-n a[href]") ?: return@mapNotNull null
            val link = a.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
            val modified = row.selectFirst("td.fb-d")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            toEntry(link, modified)
        }
        if (rows.isNotEmpty()) return rows.distinctBy { it.relPath }

        // Fallback for servers without h5ai's table structure
        return doc.select("a[href]").mapNotNull { a ->
            val link = a.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
            toEntry(link, null)
        }.distinctBy { it.relPath }
    }

    private fun fetchEntries(url: HttpUrl): List<Entry> = client.newCall(GET(url, headers)).execute().use { parseEntries(it) }

    // ---------- Anime list ----------
    // The root holds range folders (0-9, A-F, G-M, N-S, T-Z). Each folder inside those is one anime.
    // We flatten every range into one sorted list and paginate that ourselves.

    private fun allAnime(): List<Entry> {
        val ranges = fetchEntries((baseUrl + rootPath).toHttpUrl()).filter { it.isDir }
        return ranges.flatMap { r -> fetchEntries(r.url).filter { it.isDir } }
            .distinctBy { it.relPath }
    }

    // Strips/normalizes characters that some clients mis-handle when saving titles to their
    // own storage: accented letters -> plain letters, curly quotes/dashes -> plain ASCII ones.
    private fun sanitize(s: String): String {
        val plain = Normalizer.normalize(s, Normalizer.Form.NFKD).replace(Regex("\\p{M}"), "")
        return plain
            .replace("\\", "")
            .replace(Regex("[\u2010-\u2015]"), "-")
            .replace(Regex("[\u2018\u2019]"), "'")
            .replace(Regex("[\u201C\u201D]"), "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun toAnime(e: Entry) = SAnime.create().apply {
        title = sanitize(e.name)
        url = e.relPath
    }

    private fun taggedRequest(tag: String): Request {
        val url = (baseUrl + rootPath).toHttpUrl().newBuilder().fragment(tag).build()
        return GET(url, headers)
    }

    private fun paginate(all: List<Entry>, tag: String): AnimesPage {
        if (tag.startsWith("q:")) {
            val q = tag.removePrefix("q:")
            val matches = all.filter { it.name.contains(q, ignoreCase = true) }.sortedBy { it.name.natKey() }
            return AnimesPage(attachCovers(matches), false)
        }
        val sorted = if (tag.startsWith("l:")) {
            all.sortedByDescending { it.modifiedMillis }
        } else {
            all.sortedBy { it.name.natKey() }
        }
        val page = tag.substringAfter(':').toIntOrNull() ?: 1
        val from = (page - 1) * pageSize
        if (from >= sorted.size) return AnimesPage(emptyList(), false)
        val slice = sorted.subList(from, minOf(from + pageSize, sorted.size))
        return AnimesPage(attachCovers(slice), from + pageSize < sorted.size)
    }

    private fun listParse(response: Response): AnimesPage = paginate(allAnime(), response.request.url.fragment.orEmpty())

    override fun popularAnimeRequest(page: Int): Request = taggedRequest("p:$page")

    override fun popularAnimeParse(response: Response): AnimesPage = listParse(response)

    override fun latestUpdatesRequest(page: Int): Request = taggedRequest("l:$page")

    override fun latestUpdatesParse(response: Response): AnimesPage = listParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = taggedRequest("q:$query")

    override fun searchAnimeParse(response: Response): AnimesPage = listParse(response)

    // ---------- Covers (each anime folder already has its own poster image) ----------

    private val imageExt = setOf("jpg", "jpeg", "png", "webp")

    private val coverCache by lazy {
        val raw = preferences.getString(PREF_CACHE_KEY, null)
        if (raw.isNullOrBlank()) JSONObject() else runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun fetchCover(anime: Entry): String? {
        synchronized(coverCache) { coverCache.optString(anime.relPath, "") }.takeIf { it.isNotEmpty() }?.let { return it }

        return runCatching {
            val images = fetchEntries(anime.url)
                .filter { !it.isDir && it.name.substringAfterLast('.', "").lowercase() in imageExt }
            val pick = images.firstOrNull {
                it.name.contains("cover", true) || it.name.contains("poster", true) || it.name.contains("folder", true)
            } ?: images.minByOrNull { it.name.natKey() }

            val url = pick?.url?.toString()
            if (url != null) {
                synchronized(coverCache) {
                    coverCache.put(anime.relPath, url)
                    preferences.edit().putString(PREF_CACHE_KEY, coverCache.toString()).apply()
                }
            }
            url
        }.getOrNull()
    }

    private fun attachCovers(entries: List<Entry>): List<SAnime> {
        val animes = entries.map { toAnime(it) }
        if (!preferences.getBoolean(PREF_COVERS_KEY, true)) return animes

        val covers = runBlocking(Dispatchers.IO) {
            val gate = Semaphore(6)
            entries.map { e -> async { e.relPath to gate.withPermit { fetchCover(e) } } }.map { it.await() }
        }.toMap()

        animes.forEachIndexed { i, a -> a.thumbnail_url = covers[entries[i].relPath] }
        return animes
    }

    // ---------- Details ----------

    override fun animeDetailsParse(response: Response): SAnime = SAnime.create().apply {
        val segs = response.request.url.pathSegments.filter { it.isNotEmpty() }
        title = sanitize(segs.last())
        description = "Path: " + segs.joinToString(" / ")
        status = SAnime.UNKNOWN
    }

    // ---------- Episodes (video files, including inside season sub-folders) ----------

    private fun collect(
        entries: List<Entry>,
        prefix: String,
        depth: Int,
        out: MutableList<Pair<String, Entry>>,
    ) {
        for (e in entries.sortedBy { it.name.natKey() }) {
            when {
                e.isDir && depth < maxDepth -> {
                    val sub = client.newCall(GET(e.url, headers)).execute().use { parseEntries(it) }
                    collect(sub, "$prefix${e.name} / ", depth + 1, out)
                }
                !e.isDir && e.name.substringAfterLast('.', "").lowercase() in videoExt ->
                    out.add(prefix to e)
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val files = mutableListOf<Pair<String, Entry>>()
        collect(parseEntries(response), "", 1, files)
        return files.mapIndexed { i, (prefix, e) ->
            SEpisode.create().apply {
                url = e.relPath
                name = sanitize(prefix + e.name.substringBeforeLast('.'))
                episode_number = (i + 1).toFloat()
            }
        }.reversed()
    }

    // ---------- Video ----------

    // The episode url is already the direct file link, so just hand it to the player
    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        response.close()
        return listOf(Video(url, "Direct", url))
    }

    // ---------- Preferences ----------

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_COVERS_KEY
            title = "Fetch anime covers"
            summary = "Uses the cover image already inside each anime's folder. Turn off for faster, text-only browsing."
            setDefaultValue(true)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_COVERS_KEY = "pref_fetch_covers"
        private const val PREF_CACHE_KEY = "pref_cover_cache"
    }
}
