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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document

class DhakaFlix :
    AnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val name = "DhakaFlix Anime"
    override val baseUrl = "http://172.16.50.9"
    override val lang = "all"
    override val supportsLatest = false

    private val rootPath = "/DHAKA-FLIX-9/Anime%20%26%20Cartoon%20TV%20Series/"
    private val videoExt = setOf("mkv", "mp4", "avi", "webm", "m4v", "mov", "ts", "flv")
    private val maxDepth = 3 // how many folder levels deep to look for episodes
    private val pageSize = 30

    private val preferences by getPreferencesLazy()

    // ---------- Directory listing helpers ----------

    private class Entry(val url: HttpUrl, val isDir: Boolean) {
        val name: String get() = url.pathSegments.last { it.isNotEmpty() }
        val relPath: String get() = url.encodedPath
    }

    private fun String.natKey() = lowercase().replace(Regex("\\d+")) { it.value.padStart(6, '0') }

    private fun parseEntries(response: Response): List<Entry> = parseEntries(response.asJsoup(), response.request.url)

    // Returns only direct children of the current folder (skips parent/sort links)
    private fun parseEntries(doc: Document, current: HttpUrl): List<Entry> {
        val cur = current.pathSegments.filter { it.isNotEmpty() }
        return doc.select("a[href]").mapNotNull { a ->
            val link = a.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
            if (link.host != current.host) return@mapNotNull null
            val seg = link.pathSegments.filter { it.isNotEmpty() }
            if (seg.size <= cur.size || seg.take(cur.size) != cur) return@mapNotNull null
            Entry(link, link.encodedPath.endsWith("/"))
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
            .sortedBy { it.name.natKey() }
    }

    private fun toAnime(e: Entry) = SAnime.create().apply {
        title = e.name
        url = e.relPath
    }

    private fun taggedRequest(tag: String): Request {
        val url = (baseUrl + rootPath).toHttpUrl().newBuilder().fragment(tag).build()
        return GET(url, headers)
    }

    private fun paginate(all: List<Entry>, tag: String): AnimesPage {
        if (tag.startsWith("q:")) {
            val q = tag.removePrefix("q:")
            return AnimesPage(attachCovers(all.filter { it.name.contains(q, ignoreCase = true) }), false)
        }
        val page = tag.removePrefix("p:").toIntOrNull() ?: 1
        val from = (page - 1) * pageSize
        if (from >= all.size) return AnimesPage(emptyList(), false)
        val slice = all.subList(from, minOf(from + pageSize, all.size))
        return AnimesPage(attachCovers(slice), from + pageSize < all.size)
    }

    private fun listParse(response: Response): AnimesPage = paginate(allAnime(), response.request.url.fragment.orEmpty())

    override fun popularAnimeRequest(page: Int): Request = taggedRequest("p:$page")

    override fun popularAnimeParse(response: Response): AnimesPage = listParse(response)

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = listParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = taggedRequest("q:$query")

    override fun searchAnimeParse(response: Response): AnimesPage = listParse(response)

    // ---------- Covers (AniList lookup, cached on-device) ----------

    private val coverCache by lazy {
        val raw = preferences.getString(PREF_CACHE_KEY, null)
        if (raw.isNullOrBlank()) JSONObject() else runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun cleanTitle(title: String) = title.replace(Regex("""\s*[\[(].*?[\])]\s*"""), " ").trim()

    private fun fetchCover(rawTitle: String): String? {
        val title = cleanTitle(rawTitle)
        synchronized(coverCache) { coverCache.optString(title, "") }.takeIf { it.isNotEmpty() }?.let { return it }

        return runCatching {
            val query = "query(\$s: String) { Media(search: \$s, type: ANIME) { coverImage { large } } }"
            val body = JSONObject().apply {
                put("query", query)
                put("variables", JSONObject().put("s", title))
            }.toString().toRequestBody("application/json".toMediaType())

            val req = Request.Builder().url("https://graphql.anilist.co").post(body).build()
            client.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string().orEmpty())
                val url = json.optJSONObject("data")
                    ?.optJSONObject("Media")
                    ?.optJSONObject("coverImage")
                    ?.optString("large")
                    ?.takeIf { it.isNotEmpty() }
                if (url != null) {
                    synchronized(coverCache) {
                        coverCache.put(title, url)
                        preferences.edit().putString(PREF_CACHE_KEY, coverCache.toString()).apply()
                    }
                }
                url
            }
        }.getOrNull()
    }

    private fun attachCovers(entries: List<Entry>): List<SAnime> {
        val animes = entries.map { toAnime(it) }
        if (!preferences.getBoolean(PREF_COVERS_KEY, true)) return animes

        val covers = runBlocking(Dispatchers.IO) {
            val gate = Semaphore(6)
            entries.map { e -> async { e.relPath to gate.withPermit { fetchCover(e.name) } } }.map { it.await() }
        }.toMap()

        animes.forEachIndexed { i, a -> a.thumbnail_url = covers[entries[i].relPath] }
        return animes
    }

    // ---------- Details ----------

    override fun animeDetailsParse(response: Response): SAnime = SAnime.create().apply {
        val segs = response.request.url.pathSegments.filter { it.isNotEmpty() }
        title = segs.last()
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
                name = prefix + e.name.substringBeforeLast('.')
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
            title = "Fetch anime covers online"
            summary = "Looks up a poster for each anime from AniList by title. Turn off for faster, text-only browsing."
            setDefaultValue(true)
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_COVERS_KEY = "pref_fetch_covers"
        private const val PREF_CACHE_KEY = "pref_cover_cache"
    }
}
