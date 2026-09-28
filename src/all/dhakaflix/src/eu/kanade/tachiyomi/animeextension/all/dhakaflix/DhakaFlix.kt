package eu.kanade.tachiyomi.animeextension.all.dhakaflix

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.AnimeHttpLegacySource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
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
    private val maxDepth = 3

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private class Entry(val url: HttpUrl, val isDir: Boolean, val date: Long = 0L) {
        val name: String get() = url.pathSegments.last { it.isNotEmpty() }
        val relPath: String get() = url.encodedPath
    }

    private fun String.natKey() = lowercase().replace(Regex("\\d+")) { it.value.padStart(6, '0') }
    private fun String.cleanTitle() = replace(Regex("[★♥♦—]"), "").trim().replace(Regex("\\s+"), " ")

    private fun parseEntries(response: Response): List<Entry> = parseEntries(response.asJsoup(), response.request.url)

    private fun parseEntries(doc: Document, current: HttpUrl): List<Entry> {
        val cur = current.pathSegments.filter { it.isNotEmpty() }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        return doc.select("div#fallback table tr").mapNotNull { tr ->
            val a = tr.selectFirst("td.fb-n a") ?: return@mapNotNull null
            val link = a.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
            if (link.host != current.host || link.encodedPath == current.encodedPath) return@mapNotNull null
            
            val seg = link.pathSegments.filter { it.isNotEmpty() }
            if (seg.size <= cur.size || seg.take(cur.size) != cur) return@mapNotNull null

            val dateText = tr.selectFirst("td.fb-d")?.text()?.trim() ?: ""
            val dateMillis = runCatching { dateFormat.parse(dateText)?.time ?: 0L }.getOrDefault(0L)

            Entry(link, link.encodedPath.endsWith("/"), dateMillis)
        }.distinctBy { it.relPath }
    }

    // ---------- Anime list ----------

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl + rootPath, headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val animes = parseEntries(response)
            .filter { it.isDir }
            .sortedBy { it.name.lowercase() }
            .map { e ->
                SAnime.create().apply {
                    title = e.name.cleanTitle()
                    url = e.relPath
                    if (preferences.getBoolean("fetch_covers", true)) {
                        thumbnail_url = fetchAniListCover(title)
                    }
                }
            }
        return AnimesPage(animes, false)
    }

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val animes = parseEntries(response)
            .filter { it.isDir }
            .sortedByDescending { it.date }
            .map { e ->
                SAnime.create().apply {
                    title = e.name.cleanTitle()
                    url = e.relPath
                    if (preferences.getBoolean("fetch_covers", true)) {
                        thumbnail_url = fetchAniListCover(title)
                    }
                }
            }
        return AnimesPage(animes, false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = (baseUrl + rootPath).toHttpUrl().newBuilder().fragment(query).build()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ---------- AniList Covers ----------

    private fun fetchAniListCover(title: String): String {
        val query = """
            query {
                Media(search: "$title", type: ANIME) {
                    coverImage {
                        extraLarge
                    }
                }
            }
        """.trimIndent()
        
        val body = """{"query": ${Json.encodeToString(kotlinx.serialization.builtins.serializer(), query)}}"""
            .toRequestBody("application/json".toMediaType())
            
        return try {
            val res = client.newCall(POST("https://graphql.anilist.co", headers, body)).execute()
            val json = Json.parseToJsonElement(res.body.string()).jsonObject
            json["data"]?.jsonObject?.get("Media")?.jsonObject?.get("coverImage")?.jsonObject?.get("extraLarge")?.jsonPrimitive?.content ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // ---------- Details & Episodes ----------

    override fun animeDetailsParse(response: Response): SAnime = SAnime.create().apply {
        val segs = response.request.url.pathSegments.filter { it.isNotEmpty() }
        title = segs.last().cleanTitle()
        description = "Path: " + segs.joinToString(" / ")
        status = SAnime.UNKNOWN
    }

    private fun collect(entries: List<Entry>, prefix: String, depth: Int, out: MutableList<Pair<String, Entry>>) {
        for (e in entries.sortedBy { it.name.natKey() }) {
            when {
                e.isDir && depth < maxDepth -> {
                    val sub = client.newCall(GET(e.url, headers)).execute().use { parseEntries(it) }
                    collect(sub, "$prefix${e.name.cleanTitle()} / ", depth + 1, out)
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

    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        response.close()
        return listOf(Video(url, "Direct", url))
    }

    // ---------- Settings ----------

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val coverPref = SwitchPreferenceCompat(screen.context).apply {
            key = "fetch_covers"
            title = "Fetch anime covers online"
            summary = "Requires internet connection. Turn off for faster local browsing."
            setDefaultValue(true)
        }
        screen.addPreference(coverPref)
    }
}
