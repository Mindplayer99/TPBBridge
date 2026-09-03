/*
 * TPBBridge - CloudStream plugin
 *
 * Reads configured Stremio/TPB manifests, keeps normal Recent catalogs together
 * in one Home provider, and exposes each Search catalog as its own CloudStream
 * provider. Configured manifest URLs stay only in local app storage.
 *
 * GPL-3.0-or-later. Stremio protocol handling is derived from the GPL bridge
 * approach used by Hexated/phisher98 and compatible forks.
 */
package com.tpbbridge

import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixTitle
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** One Home provider. Its global search intentionally returns no results. */
internal class TPBHomeProvider(
    override var name: String,
    internal val manifestBases: List<String>
) : TPBBaseProvider(manifestBases) {
    // Never expose configured URLs as MainAPI.mainUrl: CloudStream logs provider mainUrl on registration.
    override var mainUrl: String = "$SAFE_MAIN_URL/home"
    override var lang: String = "en"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rows = manifestBases.amap { base ->
            val manifest = fetchManifest(base) ?: return@amap emptyList<HomeRow>()
            manifest.catalogs
                .filter { it.isHomeCatalog() && (page <= 1 || it.supportsSkip()) }
                .amap { catalog -> catalog.toHomeRow(base, this, page) }
                .filter { it.items.isNotEmpty() }
        }.flatten()

        val merged = linkedMapOf<String, HomeRow>()
        rows.forEach { row ->
            val key = row.name.lowercase(Locale.ROOT)
            val old = merged[key]
            if (old == null) {
                merged[key] = row
            } else {
                val combined = (old.items + row.items).distinctBy { it.url }
                merged[key] = old.copy(
                    items = combined,
                    horizontal = old.horizontal || row.horizontal,
                    hasNext = old.hasNext || row.hasNext
                )
            }
        }

        return newHomePageResponse(
            merged.values.map { row -> HomePageList(row.name, row.items, row.horizontal) },
            false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> = emptyList()

    override suspend fun search(query: String, page: Int): SearchResponseList? =
        newSearchResponseList(emptyList(), false)
}

/** One instance per discovered source. No Home rows, source-specific search only. */
internal class TPBSearchProvider(
    override var name: String,
    internal val sourceName: String,
    internal val routes: List<SearchRoute>
) : TPBBaseProvider(routes.map { it.baseUrl }.distinct()) {
    override var mainUrl: String = "$SAFE_MAIN_URL/search/${safeNameToken(sourceName)}"
    override var lang: String = "en"
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = false
    override val searchTimeoutMs: Long? = 90_000L

    override suspend fun search(query: String): List<SearchResponse> = searchPage(query, 1).first

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val (results, hasNext) = searchPage(query, page)
        return newSearchResponseList(results, hasNext)
    }

    internal suspend fun searchPage(query: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (query.isBlank() || page < 1) return emptyList<SearchResponse>() to false

        val usableRoutes = if (page == 1) routes else routes.filter { it.supportsSkip }
        if (usableRoutes.isEmpty()) return emptyList<SearchResponse>() to false

        val encoded = encodePath(query.trim())

        val entries = usableRoutes.amap { route ->
            val extras = "search=$encoded"
            fetchSearchCatalogEntries(
                route.baseUrl,
                route.type,
                route.catalogId,
                extras,
                page,
                route.supportsSkip
            ).orEmpty()
                .filter { it.id.isNotBlank() && it.name.isNotBlank() }
                .map { route.baseUrl to it.withFallbackType(route.type) }
        }.flatten()

        val deduped = entries.distinctBy { (base, entry) -> "${baseRef(base)}|${entry.type}|${entry.id}" }
        val results = deduped.mapNotNull { (base, entry) ->
            entry.toSearchResponse(this, base, sourceName)
        }

        val supportsMore = routes.any { it.supportsSkip }
        return results to (supportsMore && results.isNotEmpty())
    }
}

internal abstract class TPBBaseProvider(
    internal val knownBases: List<String>
) : MainAPI() {
    override suspend fun load(url: String): LoadResponse {
        val bridge = parseJson<BridgeItem>(url)
        val fallback = bridge.entry
        if (fallback.id.isBlank() || fallback.name.isBlank()) throw ErrorLoadingException("Invalid TPB item")

        val base = resolveBase(bridge.baseRef, bridge.baseUrl)
            ?: throw ErrorLoadingException("TPB manifest is no longer configured")
        val type = fallback.type?.takeIf { it.isNotBlank() }
            ?: bridge.type?.takeIf { it.isNotBlank() }
            ?: "Porn"
        val id = fallback.id

        val meta = fetchMetadataEntry(base, type, id) ?: fallback

        val resolvedType = meta.type?.takeIf { it.isNotBlank() } ?: type
        val videos = meta.videos.orEmpty()
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
        val responseName = meta.name.ifBlank { fallback.name }

        // Stremio represents a MegaPack (and any other multi-video item) as one
        // meta object with child video ids. A movie response can expose only one
        // Play button, so give CloudStream one selectable child per real video.
        // TvType.Others deliberately avoids pretending the collection is a TV show.
        if (videos.size > 1) {
            val items = videos.mapIndexed { index, video ->
                newEpisode(
                    StreamLoadData(
                        baseRef = baseRef(base),
                        type = resolvedType,
                        id = video.id
                    )
                ) {
                    name = fixTitle(video.title?.takeIf { it.isNotBlank() } ?: "Video ${index + 1}")
                    season = 1
                    episode = index + 1
                    posterUrl = video.thumbnail ?: video.poster
                    description = formatMetadataDescription(video.overview ?: video.description)
                    addDate(video.released)
                }
            }

            return newTvSeriesLoadResponse(
                fixTitle(responseName),
                url,
                TvType.Others,
                items
            ) {
                posterUrl = meta.poster ?: fallback.poster
                backgroundPosterUrl = meta.background ?: fallback.background
                plot = formatMetadataDescription(meta.description ?: fallback.description)
                year = meta.bestYear() ?: fallback.bestYear()
                tags = meta.displayTags().ifEmpty { fallback.displayTags() }.takeIf { it.isNotEmpty() }
                actors = meta.displayCast().ifEmpty { fallback.displayCast() }
                    .map { ActorData(Actor(it)) }
                    .takeIf { it.isNotEmpty() }
                duration = parseRuntimeMinutes(meta.runtime ?: fallback.runtime)
                score = Score.from10(meta.imdbRating ?: fallback.imdbRating)
                logoUrl = meta.logo ?: fallback.logo
                seasonNames = listOf(SeasonData(season = 1, name = "Videos", displaySeason = null))
            }
        }

        // An explicitly advertised child id is canonical for that child. When
        // there is no child, preserve the exact catalog id: TPB compact jstrg:
        // ids contain multiple quality members and replacing them with a meta
        // object's jstrm: id silently drops the other 4K/1080p member.
        val data = StreamLoadData(
            baseRef = baseRef(base),
            type = resolvedType,
            id = videos.singleOrNull()?.id ?: id
        ).toJson()

        return newMovieLoadResponse(
            fixTitle(responseName),
            url,
            TvType.Others,
            data
        ) {
            posterUrl = meta.poster ?: fallback.poster
            backgroundPosterUrl = meta.background ?: fallback.background
            plot = formatMetadataDescription(meta.description ?: fallback.description)
            year = meta.bestYear() ?: fallback.bestYear()
            tags = meta.displayTags().ifEmpty { fallback.displayTags() }.takeIf { it.isNotEmpty() }
            actors = meta.displayCast().ifEmpty { fallback.displayCast() }
                .map { ActorData(Actor(it)) }
                .takeIf { it.isNotEmpty() }
            duration = parseRuntimeMinutes(meta.runtime ?: fallback.runtime)
            score = Score.from10(meta.imdbRating ?: fallback.imdbRating)
            logoUrl = meta.logo ?: fallback.logo
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<StreamLoadData>(data)
        val base = resolveBase(loadData.baseRef, loadData.baseUrl) ?: return false

        val response = try {
            app.get(
                "$base/stream/${encodePath(loadData.type)}/${encodePath(loadData.id)}.json",
                timeout = 120L
            ).parsedSafe<StreamsResponse>()
        } catch (_: Throwable) {
            null
        } ?: return false

        var emitted = false
        response.streams
            .deduplicatedForPlayback()
            .orderedForPlayback()
            .forEach { stream ->
                if (stream.emit(subtitleCallback, callback)) emitted = true
            }
        return emitted
    }

    internal fun resolveBase(ref: String?, legacyBase: String?): String? {
        if (!legacyBase.isNullOrBlank()) {
            // Legacy v4 items are accepted only if that exact manifest is still configured.
            // Never let an item payload turn TPBBridge into an arbitrary network fetcher.
            return knownBases.firstOrNull { it == legacyBase }
        }
        if (ref.isNullOrBlank()) return knownBases.firstOrNull()
        return knownBases.firstOrNull { baseRef(it) == ref }
    }
}

internal data class Manifest(
    @JsonProperty("catalogs") val catalogs: List<Catalog> = emptyList()
)

internal data class Extra(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("isRequired") val isRequired: Boolean? = null
)

internal data class Catalog(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("id") val id: String = "",
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("types") val types: List<String>? = null,
    @JsonProperty("extra") val extra: List<Extra>? = null,
    @JsonProperty("extraSupported") val extraSupported: List<String>? = null
) {
    fun allTypes(): List<String> = when {
        !types.isNullOrEmpty() -> types.filter { it.isNotBlank() }
        !type.isNullOrBlank() -> listOf(type)
        else -> listOf("Porn")
    }

    fun supportsSearch(): Boolean = hasExtra("search")

    fun supportsSkip(): Boolean = hasExtra("skip")

    internal fun hasExtra(extraName: String): Boolean {
        return extra?.any { it.name.equals(extraName, true) } == true ||
            extraSupported?.any { it.equals(extraName, true) } == true
    }

    fun hasRequiredExtra(): Boolean = extra?.any { it.isRequired == true } == true

    fun isHomeCatalog(): Boolean =
        isTpbHomeCatalogDescriptor(name, id, hasRequiredExtra())

    suspend fun toHomeRow(
        base: String,
        provider: TPBBaseProvider,
        page: Int,
        sourceName: String = deriveSourceName(name, id)
    ): HomeRow {
        val rawEntries = allTypes().amap { t ->
            fetchHomeCatalogEntries(
                base,
                t,
                id,
                page,
                supportsSkip(),
                if (isTpbLiveCatalogDescriptor(name, id)) {
                    LIVE_HOME_CATALOG_CACHE_MS
                } else {
                    HOME_CATALOG_CACHE_MS
                }
            ).orEmpty().map { it.withFallbackType(t) }
        }.flatten().filter { it.id.isNotBlank() && it.name.isNotBlank() }

        val entries = rawEntries.distinctBy { "${it.type}|${it.id}" }
        val items = entries.mapNotNull { it.toSearchResponse(provider, base, sourceName) }
        val landscapeCount = entries.count { it.posterShape.equals("landscape", true) }
        val horizontal = entries.isNotEmpty() && landscapeCount * 2 >= entries.size

        return HomeRow(
            name = sourceName.ifBlank { name ?: id },
            items = items,
            horizontal = horizontal,
            hasNext = supportsSkip() && items.isNotEmpty()
        )
    }
}

internal data class HomeRow(
    val name: String,
    val items: List<SearchResponse>,
    val horizontal: Boolean,
    val hasNext: Boolean
)

internal data class CatalogResponse(
    @JsonProperty("metas") val metas: List<CatalogEntry>? = null,
    @JsonProperty("meta") val meta: CatalogEntry? = null
)

internal data class CatalogEntry(
    @JsonProperty("name") val name: String = "",
    @JsonProperty("id") val id: String = "",
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("posterShape") val posterShape: String? = null,
    @JsonProperty("background") val background: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("genre") val genre: List<String>? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("cast") val cast: List<String>? = null,
    @JsonProperty("runtime") val runtime: String? = null,
    @JsonProperty("imdbRating") val imdbRating: String? = null,
    @JsonProperty("logo") val logo: String? = null,
    @JsonProperty("year") val yearString: String? = null,
    @JsonProperty("releaseInfo") val releaseInfo: String? = null,
    @JsonProperty("released") val released: String? = null,
    @JsonProperty("videos") val videos: List<CatalogVideo>? = null
) {
    fun toSearchResponse(provider: TPBBaseProvider, baseUrl: String, source: String): SearchResponse? {
        if (name.isBlank() || id.isBlank()) return null
        val payload = BridgeItem(
            baseRef = baseRef(baseUrl),
            type = type,
            source = source,
            entry = this
        ).toJson()
        return provider.newMovieSearchResponse(fixTitle(name), payload, TvType.Others) {
            posterUrl = poster
        }
    }

    fun bestYear(): Int? {
        return sequenceOf(yearString, releaseInfo, released)
            .filterNotNull()
            .mapNotNull { Regex("(?:19|20)\\d{2}").find(it)?.value?.toIntOrNull() }
            .firstOrNull()
    }

    fun displayTags(): List<String> = (genre.orEmpty() + genres.orEmpty())
        .mapNotNull(::cleanText)
        .distinctBy { it.lowercase(Locale.ROOT) }

    fun displayCast(): List<String> = cast.orEmpty()
        .mapNotNull(::cleanText)
        .distinctBy { it.lowercase(Locale.ROOT) }

    fun withFallbackType(fallbackType: String): CatalogEntry =
        if (type.isNullOrBlank()) copy(type = fallbackType) else this
}

/** Child media advertised by Stremio metadata (used by TPB MegaPacks). */
internal data class CatalogVideo(
    @JsonProperty("id") val id: String = "",
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("released") val released: String? = null
)

/**
 * New payloads use baseRef so private manifest/config URLs do not get copied into item URLs/history.
 * baseUrl stays nullable only for backward compatibility with v4 items already created on-device.
 */
internal data class BridgeItem(
    val baseRef: String? = null,
    val baseUrl: String? = null,
    val type: String? = null,
    val source: String? = null,
    val entry: CatalogEntry
)

internal data class StreamLoadData(
    val baseRef: String? = null,
    val baseUrl: String? = null,
    val type: String,
    val id: String
)

internal data class Subtitle(
    val url: String? = null,
    val lang: String? = null,
    val id: String? = null
)

internal data class ProxyHeaders(
    val request: Map<String, String>? = null,
    val response: Map<String, String>? = null
)

internal data class BehaviorHints(
    val proxyHeaders: ProxyHeaders? = null,
    val headers: Map<String, String>? = null,
    val notWebReady: Boolean? = null,
    val filename: String? = null,
    val videoSize: Long? = null
)

internal data class Stream(
    val name: String? = null,
    val title: String? = null,
    val url: String? = null,
    val description: String? = null,
    val ytId: String? = null,
    val externalUrl: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val sources: List<String> = emptyList(),
    val behaviorHints: BehaviorHints? = null,
    val subtitles: List<Subtitle> = emptyList()
) {
    fun stableKey(): String = when {
        !url.isNullOrBlank() -> "url:${url.trim()}|headers:${behaviorHints.requestHeadersKey()}"
        !infoHash.isNullOrBlank() -> "hash:${infoHash.lowercase(Locale.ROOT)}:${fileIdx ?: -1}"
        !ytId.isNullOrBlank() -> "yt:$ytId"
        !externalUrl.isNullOrBlank() -> "ext:$externalUrl"
        else -> "meta:${name.orEmpty()}|${title.orEmpty()}|${description.orEmpty()}"
    }

    suspend fun emit(
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        emitSubtitles(subtitleCallback)

        val cleanName = cleanStreamName(
            name,
            title,
            description,
            behaviorHints?.filename,
            behaviorHints?.videoSize
        )
        val quality = inferQuality(name, title, description, behaviorHints?.filename)

        // Stremio stream pointers are alternatives. Prefer a direct/debrid URL when present.
        if (!url.isNullOrBlank()) {
            val requestHeaders = linkedMapOf<String, String>().apply {
                behaviorHints?.headers?.let { putAll(it) }
                behaviorHints?.proxyHeaders?.request?.let { putAll(it) }
            }
            val directType = if (isLikelyTpbHls(url, name, title, description)) {
                ExtractorLinkType.M3U8
            } else {
                INFER_TYPE
            }

            callback(
                newExtractorLink(
                    source = cleanStreamSource(name),
                    name = cleanName,
                    url = url,
                    type = directType
                ) {
                    this.quality = quality
                    headers = requestHeaders
                    val ref = requestHeaders.entries
                        .firstOrNull { it.key.equals("referer", true) }
                        ?.value
                    if (!ref.isNullOrBlank()) referer = ref
                }
            )
            return true
        }

        // TPB returns infoHash + sources for P2P fallback when debrid is unavailable/hidden.
        if (!infoHash.isNullOrBlank()) {
            val magnet = buildMagnet(
                infoHash = infoHash,
                displayName = behaviorHints?.filename ?: title,
                sources = sources,
                fileIndex = fileIdx
            )
            callback(
                newExtractorLink(
                    source = cleanStreamSource(name).let { if (it == "TPB") "TPB P2P" else it },
                    name = cleanName,
                    url = magnet,
                    type = ExtractorLinkType.MAGNET
                ) {
                    this.quality = quality
                }
            )
            return true
        }

        if (!ytId.isNullOrBlank()) {
            loadExtractor("https://www.youtube.com/watch?v=$ytId", subtitleCallback, callback)
            return true
        }

        if (!externalUrl.isNullOrBlank()) {
            loadExtractor(externalUrl, subtitleCallback, callback)
            return true
        }

        return false
    }

    internal suspend fun emitSubtitles(subtitleCallback: (SubtitleFile) -> Unit) {
        subtitles.distinctBy { it.url }.forEach { sub ->
            val subUrl = sub.url?.takeIf { it.isNotBlank() } ?: return@forEach
            val label = SubtitleHelper.fromTagToEnglishLanguageName(sub.lang ?: "")
                ?: sub.lang?.takeIf { it.isNotBlank() }
                ?: "Subtitle"
            subtitleCallback(newSubtitleFile(label, subUrl))
        }
    }
}

private fun BehaviorHints?.requestHeadersKey(): String {
    if (this == null) return ""
    val merged = linkedMapOf<String, String>()
    headers?.forEach { (key, value) -> merged[key.lowercase(Locale.ROOT)] = value }
    proxyHeaders?.request?.forEach { (key, value) -> merged[key.lowercase(Locale.ROOT)] = value }
    return merged.entries
        .sortedBy { it.key }
        .joinToString("|") { (key, value) ->
            "${key.length}:$key${value.length}:$value"
        }
}

/**
 * Collapse only true duplicate playback choices. Raw duplicates contribute all
 * trackers and exact duplicates contribute all subtitles, so deduplication
 * improves the menu without throwing away information needed for playback.
 */
internal fun List<Stream>.deduplicatedForPlayback(): List<Stream> {
    val merged = linkedMapOf<String, Stream>()
    forEach { stream ->
        val key = stream.stableKey()
        val old = merged[key]
        merged[key] = if (old == null) {
            stream
        } else {
            old.copy(
                name = old.name ?: stream.name,
                title = old.title ?: stream.title,
                description = old.description ?: stream.description,
                behaviorHints = old.behaviorHints ?: stream.behaviorHints,
                sources = (old.sources + stream.sources).distinct(),
                subtitles = (old.subtitles + stream.subtitles)
                    .distinctBy { "${it.url.orEmpty()}|${it.lang.orEmpty()}|${it.id.orEmpty()}" }
            )
        }
    }
    return merged.values.toList()
}

/**
 * Keep resolved/cache-backed media above P2P fallback links, then prefer the
 * highest advertised quality inside each class. Indexed tie-breaking preserves
 * TPB's original order for equivalent mirrors.
 */
internal fun List<Stream>.orderedForPlayback(): List<Stream> = withIndex()
    .sortedWith(
        compareBy<IndexedValue<Stream>> { it.value.playbackClass() }
            .thenByDescending {
                inferQuality(
                    it.value.name,
                    it.value.title,
                    it.value.description,
                    it.value.behaviorHints?.filename
                )
            }
            .thenBy { it.index }
    )
    .map { it.value }

private fun Stream.playbackClass(): Int = when {
    !url.isNullOrBlank() -> 0
    !ytId.isNullOrBlank() || !externalUrl.isNullOrBlank() -> 1
    !infoHash.isNullOrBlank() -> 2
    else -> 3
}

internal data class StreamsResponse(
    val streams: List<Stream> = emptyList()
)

internal data class SearchRoute(
    val baseUrl: String,
    val catalogId: String,
    val type: String,
    val supportsSkip: Boolean = false
)

internal data class SearchRouteGroup(
    val sourceName: String,
    val routes: List<SearchRoute>
)
