package eu.kanade.tachiyomi.extension.en.rinkocomics

import android.util.Base64
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class RinkoComics :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = comicsUrl(page)
            .addQueryParameter(SORT_PARAM, "views")
            .build()

        return parseComicsPage(client.get(url, Fetch.DOCUMENT).asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = comicsUrl(page)
            .addQueryParameter(SORT_PARAM, "updated")
            .build()

        return parseComicsPage(client.get(url, Fetch.DOCUMENT).asJsoup())
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = comicsUrl(page)

        if (query.isNotBlank()) {
            url.addQueryParameter("q", query)
        }

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> {
                    filter.state.filter { it.state }
                        .forEach { url.addQueryParameter("genres[]", it.slug) }
                }

                is SortFilter -> {
                    filter.toQuery()?.let { url.addQueryParameter(SORT_PARAM, it) }
                }

                else -> {}
            }
        }

        return parseComicsPage(client.get(url.build(), Fetch.DOCUMENT).asJsoup())
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()
        val updatedManga = parseMangaDetails(document).apply { url = manga.url }

        if (!fetchChapters) return SMangaUpdate(updatedManga, chapters)

        return SMangaUpdate(updatedManga, parseChapterList(document))
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        title = requireField(document.selectFirst("h1")?.text(), "title")

        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")

        status = parseStatus(
            document.selectFirst("dl dt:containsOwn(Status) + dd")?.text(),
        )

        genre = document.select("a[href*=/comics_genres/]")
            .joinToString { it.text() }

        description = buildString {
            append(document.select("#cwSynopsis p").joinToString("\n\n") { it.text() })
            document.selectFirst("span:containsOwn(Also known as:)")?.parent()?.ownText()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { append("\n\nAlternative titles: ", it) }
        }.trim().ifEmpty { null }
    }

    private suspend fun parseChapterList(document: Document): List<SChapter> {
        val hideLocked = preferences.getBoolean(PREF_HIDE_LOCKED, false)

        val chapters = LinkedHashMap<String, SChapter>()
        fun addAll(items: List<SChapter>, hideLocked: Boolean) {
            items.forEach { chapter ->
                if (hideLocked && chapter.url.endsWith(LOCK_SUFFIX)) return@forEach
                if (!chapters.containsKey(chapter.url)) {
                    chapters[chapter.url] = chapter
                }
            }
        }

        addAll(parseChapterElements(document.select(CHAPTER_SELECTOR)), hideLocked)

        val loadMoreBtn = document.selectFirst("#cwMoreChapters")
        val comicId = loadMoreBtn?.attr("data-comic-id").orEmpty()
        val order = loadMoreBtn?.attr("data-order").orEmpty()
        val nonce = extractNonce(document).orEmpty()
        var offset = loadMoreBtn?.attr("data-offset")?.toIntOrNull() ?: 0

        if (comicId.isNotBlank() && nonce.isNotBlank()) {
            while (true) {
                val items = fetchMoreChapters(comicId, offset, order, nonce)
                if (items.isEmpty()) break
                addAll(items, hideLocked)
                offset += items.size
            }
        }

        return chapters.values.toList()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        if (chapter.url.contains(LOCK_SUFFIX)) {
            throw Exception("This chapter is locked. Use WebView to purchase it.")
        }

        val document = client.get(getChapterUrl(chapter), Fetch.DOCUMENT).asJsoup()
        val payload = document.selectFirst("script#cwPages")?.data()?.trim()
            ?: return emptyList()
        val pages = decodePages(payload).mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }

        if (pages.isEmpty()) {
            throw Exception("Chapter is locked or unavailable.")
        }

        return pages
    }

    override val supportsFilterFetching get() = true

    override suspend fun fetchFilterData(): JsonElement {
        val document = client.get(comicsUrl(1).build(), Fetch.DOCUMENT).asJsoup()

        return document.select("input[name='genres[]']")
            .mapNotNull { input ->
                val slug = input.attr("value").trim()
                val name = input.parent()?.text()?.trim().orEmpty()
                if (slug.isBlank() || name.isBlank()) null else Pair(name, slug)
            }
            .toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filters = mutableListOf<Filter<*>>()

        data?.parseAs<List<Pair<String, String>>>()?.also { genres ->
            filters += GenreFilter(genres.map { Genre(it.first, it.second) })
        }

        filters += SortFilter()

        return FilterList(filters)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_HIDE_LOCKED
            title = "Hide paid chapters"
            summary = "Hide locked/paid chapters from the list."
            setDefaultValue(false)
        }.let(screen::addPreference)
    }

    private fun comicsUrl(page: Int): HttpUrl.Builder {
        val url = if (page <= 1) {
            "$baseUrl/comic/"
        } else {
            "$baseUrl/comic/page/$page/"
        }
        return url.toHttpUrl().newBuilder()
    }

    private fun parseComicsPage(document: Document): MangasPage {
        val entries = document.select("li > a[href*=/comic/]:has(img)").mapNotNull { card ->
            val url = card.attr("abs:href").trim()
            val title = requireField(card.selectFirst(".line-clamp-2")?.text(), "title")

            SManga.create().apply {
                setUrlWithoutDomain(url)
                this.title = title
                thumbnail_url = card.selectFirst("img")?.let { imageFromElement(it) }
            }
        }

        val hasNextPage = document.selectFirst("nav[aria-label=Pages] a:containsOwn(›)") != null

        return MangasPage(entries, hasNextPage)
    }

    private fun parseChapterElements(elements: List<Element>): List<SChapter> {
        return elements.mapNotNull { element ->
            val permalink = element.attr("data-permalink").trim()
            val href = element.attr("abs:href")
            val url = permalink.ifBlank { href }
            if (url.isBlank() || url.endsWith("#")) return@mapNotNull null

            val texts = element.child(0).children()
            val name = texts.firstOrNull()?.text() ?: element.attr("data-title")
            val dateText = texts.getOrNull(1)?.text()?.substringBefore(" ·")

            SChapter.create().apply {
                setUrlWithoutDomain(url)
                this.name = name.trim()
                date_upload = dateFormat.tryParseDate(dateText)

                if (isLocked(element)) {
                    this.name = "$LOCK_PREFIX${this.name}"
                    this.url += LOCK_SUFFIX
                }
            }
        }
    }

    private fun isLocked(element: Element): Boolean {
        val reason = element.attr("data-reason").lowercase(Locale.ROOT)
        if (reason.isNotBlank() && reason != "free") return true
        if (element.hasClass("is-locked")) return true

        val href = element.attr("href")
        return href.isBlank() || href == "#"
    }

    private suspend fun fetchMoreChapters(
        comicId: String,
        offset: Int,
        order: String,
        nonce: String,
    ): List<SChapter> {
        val formBody = FormBody.Builder()
            .add("action", "load_more_chapters")
            .add("nonce", nonce)
            .add("comic_id", comicId)
            .add("offset", offset.toString())
            .add("order", order)
            .add("ui", "v2")
            .build()

        val xhrHeaders = headers.newBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
        val result = client.post("$baseUrl/wp-admin/admin-ajax.php", xhrHeaders, Fetch.XHR, formBody).parseAs<AjaxResponse>()
        if (!result.success) return emptyList()

        val html = result.data?.html.orEmpty()
        if (html.isBlank()) return emptyList()

        val doc = Jsoup.parseBodyFragment(html)
        return parseChapterElements(doc.select(CHAPTER_SELECTOR))
    }

    private fun decodePages(payload: String): List<String> {
        val raw = Base64.decode(payload, Base64.DEFAULT)

        return ByteArray(raw.size - 16) { i ->
            ((raw[i + 16].toInt() and 0xFF) xor (raw[i % 16].toInt() and 0xFF)).toByte()
        }.toString(Charsets.UTF_8).parseAs()
    }

    private fun extractNonce(document: Document): String? {
        val match = NONCE_REGEX.find(document.html()) ?: return null
        return match.groupValues[1]
    }

    private fun parseStatus(status: String?): Int = when (status?.trim()?.lowercase(Locale.ROOT)) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.UNKNOWN
        "cancelled", "canceled" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    private fun imageFromElement(element: Element): String? {
        val imageUrl = when {
            element.hasAttr("data-src") -> element.attr("abs:data-src")
            element.hasAttr("data-lazy-src") -> element.attr("abs:data-lazy-src")
            else -> element.attr("abs:src")
        }
        return imageUrl.trim().ifEmpty { null }
    }

    private fun requireField(value: String?, label: String): String {
        val trimmed = value?.trim()
        if (trimmed.isNullOrBlank()) {
            throw Exception("Missing $label")
        }
        return trimmed
    }

    class Genre(name: String, val slug: String) : Filter.CheckBox(name)

    class GenreFilter(genres: List<Genre>) : Filter.Group<Genre>("Genres", genres)

    class SortFilter : Filter.Select<String>("Sort", OPTIONS.map { it.first }.toTypedArray()) {
        fun toQuery(): String? = OPTIONS.getOrNull(state)?.second

        companion object {
            val OPTIONS = listOf(
                Pair("Latest update", "updated"),
                Pair("Most viewed", "views"),
                Pair("Newest series", "new"),
                Pair("A-Z", "az"),
            )
        }
    }

    @Serializable
    private class AjaxResponse(
        val success: Boolean = false,
        val data: AjaxData? = null,
    )

    @Serializable
    private class AjaxData(
        val html: String? = null,
    )

    companion object {
        private const val PREF_HIDE_LOCKED = "hide_paid_chapters"
        private const val SORT_PARAM = "sort"
        private const val LOCK_PREFIX = "🔒 "
        private const val LOCK_SUFFIX = "#lock"
        private const val CHAPTER_SELECTOR = "a.chapter-item"
        private val NONCE_REGEX = Regex(
            """comicworld_ajax\s*=\s*\{[^}]*"nonce"\s*:\s*"([^"]+)"""",
        )
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("[MMMM][MMM] d, yyyy", Locale.ENGLISH)
