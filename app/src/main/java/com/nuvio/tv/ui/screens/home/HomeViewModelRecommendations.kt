package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Nuvio Plus: "Recommended for you" on Home, right under Continue Watching: titles like the ones
 * you're watching (TMDB's recommendations for each, mixed together, ones you're already watching
 * left out). It doesn't depend on which addons have catalogs, so Home always has suggestions;
 * highlighting one fills the large picture at the top like any other card.
 */
internal object NpHomeRecommendations {
    const val CATALOG_ID = "np-recommended"
    val row = MutableStateFlow<CatalogRow?>(null)
    /** The featured banner at the top of Home: recommendations with a wide backdrop, logo and description. */
    val banner = MutableStateFlow<List<MetaPreview>>(emptyList())
    @Volatile var basis: String? = null
}

@OptIn(FlowPreview::class)
internal fun HomeViewModel.observeNpRecommendationsPipeline() {
    viewModelScope.launch {
        _uiState
            .map { st ->
                st.continueWatchingItems.mapNotNull { item ->
                    when (item) {
                        is ContinueWatchingItem.InProgress -> item.progress.contentId to item.progress.contentType
                        is ContinueWatchingItem.NextUp -> item.info.contentId to item.info.contentType
                    }
                }.distinctBy { it.first }.take(5)
            }
            .distinctUntilChanged()
            .debounce(2_000)
            .collectLatest { seeds ->
                if (seeds.isEmpty()) return@collectLatest
                val key = seeds.joinToString { it.first }
                if (key == NpHomeRecommendations.basis && NpHomeRecommendations.row.value != null) return@collectLatest
                val watching = seeds.map { it.first }.toSet()
                val lists = seeds.mapNotNull { (id, type) ->
                    runCatching {
                        val tmdb = tmdbService.ensureTmdbId(id, type) ?: return@runCatching null
                        val ct = if (type.equals("series", true) || type.equals("tv", true)) ContentType.SERIES else ContentType.MOVIE
                        tmdbMetadataService.fetchMoreLikeThis(tmdb, ct, maxItems = 10)
                    }.getOrNull()
                }
                // Take turns from each title's list, so one show doesn't fill the row.
                val out = LinkedHashMap<String, MetaPreview>()
                val longest = lists.maxOfOrNull { it.size } ?: 0
                for (i in 0 until longest) {
                    for (l in lists) {
                        val m = l.getOrNull(i) ?: continue
                        if (m.id in watching || m.imdbId in watching) continue
                        if (m.background.isNullOrBlank() && m.poster.isNullOrBlank()) continue
                        out.putIfAbsent(m.id, m)
                    }
                }
                val items = out.values.take(24)
                NpHomeRecommendations.basis = key
                NpHomeRecommendations.row.value = if (items.size < 4) null else CatalogRow(
                    addonId = "np-recommendations",
                    addonName = "avonioTV Home",
                    addonBaseUrl = "",
                    catalogId = NpHomeRecommendations.CATALOG_ID,
                    catalogName = "Recommended for you",
                    type = ContentType.MOVIE,
                    rawType = "movie",
                    items = items,
                    isLoading = false,
                    hasMore = false
                )
                updateCatalogRowsPipeline()
                // The banner: the first recommendations that have a wide backdrop, with their
                // logo and description from TMDB.
                val featured = items.filter { !it.background.isNullOrBlank() }.take(8).map { m ->
                    val id = m.id.removePrefix("tmdb:")
                    val e = runCatching { tmdbMetadataService.fetchEnrichment(tmdbId = id, contentType = m.type) }.getOrNull()
                    if (e == null) m else m.copy(
                        logo = e.logo ?: m.logo,
                        background = e.backdrop ?: m.background,
                        description = e.description ?: m.description,
                        genres = e.genres.takeIf { it.isNotEmpty() } ?: m.genres,
                        imdbRating = e.rating?.toFloat() ?: m.imdbRating,
                        releaseInfo = e.releaseInfo ?: m.releaseInfo
                    )
                }
                NpHomeRecommendations.banner.value = featured
            }
    }
}
