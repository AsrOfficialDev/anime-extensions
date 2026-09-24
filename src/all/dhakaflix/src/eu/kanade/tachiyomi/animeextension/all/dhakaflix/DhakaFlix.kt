package eu.kanade.tachiyomi.animeextension.all.dhakaflix

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AnimeHttpLegacySource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document

class DhakaFlix : AnimeHttpLegacySource() {

    override val name = "DhakaFlix Anime"
    override val baseUrl = "http://172.16.50.9"
    override val lang = "all"
    override val supportsLatest = false

    private val rootPath = "/DHAKA-FLIX-9/Anime%20%26%20Cartoon%20TV%20Series/"
    private val videoExt = setOf("mkv", "mp4", "avi", "webm", "m4v", "mov", "ts", "flv")
    private val maxDepth = 3 // how many folder levels deep to look for episodes

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

    // ---------- Anime list ----------
    // The root holds range folders (0-9, A-F, G-M, N-S, T-Z).
    // Each range folder = one page, and each folder inside it = one anime.

    private fun toAnime(e: Entry) = SAnime.create().apply {
        title = e.name
        url = e.relPath
    }

    private fun subFolders(folder: Entry): List<Entry> = client.newCall(GET(folder.url, headers)).execute().use { parseEntries(it) }.filter { it.isDir }

    // The page number / search query travels in the URL fragment (never sent to the server)
    private fun rootRequest(tag: String): Request {
        val url = (baseUrl + rootPath).toHttpUrl().newBuilder().fragment(tag).build()
        return GET(url, headers)
    }

    override fun popularAnimeRequest(page: Int): Request = rootRequest("p:$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val tag = response.request.url.fragment.orEmpty()
        val ranges = parseEntries(response).filter { it.isDir }

        if (tag.startsWith("q:")) {
            val q = tag.removePrefix("q:")
            val found = ranges.flatMap { subFolders(it) }
                .filter { it.name.contains(q, ignoreCase = true) }
                .map { toAnime(it) }
            return AnimesPage(found, false)
        }

        val page = tag.removePrefix("p:").toIntOrNull() ?: 1
        val range = ranges.getOrNull(page - 1) ?: return AnimesPage(emptyList(), false)
        return AnimesPage(subFolders(range).map { toAnime(it) }, page < ranges.size)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = rootRequest("q:$query")

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

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
}
