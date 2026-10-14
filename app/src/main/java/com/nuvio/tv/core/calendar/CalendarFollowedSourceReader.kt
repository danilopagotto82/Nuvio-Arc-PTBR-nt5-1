package com.nuvio.tv.core.calendar

import android.util.Log
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.mdblist.MDBLIST_WATCHLIST_KEY
import com.nuvio.tv.data.mdblist.MdbListIds
import com.nuvio.tv.data.mdblist.MdbListItemType
import com.nuvio.tv.data.mdblist.MdbListSyncRepository
import com.nuvio.tv.data.repository.TraktLibraryService
import com.nuvio.tv.data.repository.TraktProgressService
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.data.simkl.SimklListStatus
import com.nuvio.tv.data.simkl.SimklSyncRepository
import com.nuvio.tv.data.simkl.idValue
import com.nuvio.tv.data.simkl.simklIdValue
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

data class CalendarFollowedSnapshot(
    val candidates: List<CalendarTitleCandidate> = emptyList(),
    val removedFromContinueWatching: Set<String> = emptySet(),
    val addonEntries: List<Pair<CalendarIds, CalendarEntry>> = emptyList(),
    val watched: CalendarWatchedIndex = CalendarWatchedIndex(),
    /** False while Trakt is signed in but its watched shows are not loaded yet: their 12-month check cannot be made. */
    val traktWatchedLoaded: Boolean = true,
    /** Posters the app already holds for titles their sources left without one, by id alias. */
    val cachedPosters: Map<String, String> = emptyMap()
)

class CalendarWatchedIndex(private val keys: Set<String> = emptySet()) {
    fun isWatched(ids: CalendarIds, season: Int?, episode: Int?): Boolean {
        if (season == null || episode == null) return false
        return ids.aliases().any { "$it|$season|$episode" in keys }
    }

    fun isWatched(title: CalendarTitleRecord, entry: CalendarEntry): Boolean =
        entry.kind == CalendarEntryKind.EPISODE && isWatched(title.ids, entry.season, entry.episode)

    class Builder {
        private val keys = HashSet<String>()

        fun add(contentId: String, season: Int?, episode: Int?) = apply {
            if (season == null || episode == null) return@apply
            CalendarIds.fromContentId(contentId).aliases().forEach { keys += "$it|$season|$episode" }
        }

        fun addAll(byContent: Map<String, Set<Pair<Int, Int>>>) = apply {
            byContent.forEach { (contentId, episodes) -> episodes.forEach { (s, e) -> add(contentId, s, e) } }
        }

        fun build() = CalendarWatchedIndex(keys)
    }
}

/** Reads only what the app already holds; it never starts a sync of its own. */
@Singleton
class CalendarFollowedSourceReader @Inject constructor(
    private val libraryPreferences: LibraryPreferences,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val traktLibraryService: TraktLibraryService,
    private val traktProgressService: TraktProgressService,
    private val simklSyncRepository: SimklSyncRepository,
    private val mdbListSyncRepository: MdbListSyncRepository,
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val enrichmentCache: ContinueWatchingEnrichmentCache,
    private val trackingProviders: TrackingProviderRegistry,
    private val metaRepository: MetaRepository
) {
    suspend fun read(profileId: Int): CalendarFollowedSnapshot {
        val candidates = mutableListOf<CalendarTitleCandidate>()
        val watched = CalendarWatchedIndex.Builder()

        part("library") {
            libraryPreferences.libraryItems.firstOrEmpty().forEach { item ->
                val type = calendarTitleType(item.type) ?: return@forEach
                candidates += CalendarTitleCandidate(
                    CalendarIds.fromContentId(item.id), type, CalendarOrigin.LIBRARY,
                    item.addedAt.toInstantOrNull(), item.name, item.id, item.poster
                )
            }
        }
        part("progress") {
            watchProgressPreferences.observeAllProgress(profileId).firstOrEmpty()
                .filter { calendarTitleType(it.contentType) == CalendarTitleType.SERIES }
                .forEach { progress ->
                    candidates += CalendarTitleCandidate(
                        CalendarIds.fromContentId(progress.contentId), CalendarTitleType.SERIES, CalendarOrigin.PROGRESS,
                        progress.lastWatched.toInstantOrNull(), progress.name, progress.contentId, progress.poster
                    )
                    if (progress.isCompleted()) watched.add(progress.contentId, progress.season, progress.episode)
                }
        }
        part("watched") {
            watchedItemsPreferences.allItems.firstOrEmpty()
                .filter { it.season != null || calendarTitleType(it.contentType) == CalendarTitleType.SERIES }
                .groupBy { it.contentId }
                .forEach { (contentId, items) ->
                    items.forEach { watched.add(contentId, it.season, it.episode) }
                    candidates += CalendarTitleCandidate(
                        CalendarIds.fromContentId(contentId), CalendarTitleType.SERIES, CalendarOrigin.WATCHED,
                        items.maxOf { it.watchedAt }.toInstantOrNull(), items.first().title, contentId, items.first().poster
                    )
                }
        }
        var traktWatchedLoaded = true
        if (trackingProviders.isAuthenticated(TrackingProviderId.TRAKT)) part("trakt") {
            val seeds = traktProgressService.currentWatchedShowSeeds()
            traktWatchedLoaded = seeds != null
            traktProgressService.currentDroppedShowIds().forEach { contentId ->
                candidates += CalendarTitleCandidate(CalendarIds.fromContentId(contentId), CalendarTitleType.SERIES, CalendarOrigin.DROPPED)
            }
            seeds.orEmpty().forEach { seed ->
                candidates += CalendarTitleCandidate(
                    CalendarIds.fromContentId(seed.contentId).merged(CalendarIds(trakt = seed.traktShowId?.toLong())),
                    CalendarTitleType.SERIES, CalendarOrigin.TRAKT_WATCHED,
                    seed.lastWatched.toInstantOrNull(), seed.name, seed.contentId, seed.poster
                )
            }
            watched.addAll(traktProgressService.getWatchedShowEpisodes())
            traktLibraryService.currentItems().forEach { entry ->
                val type = calendarTitleType(entry.type) ?: return@forEach
                val ids = CalendarIds.fromContentId(entry.id).merged(
                    CalendarIds(imdb = entry.imdbId, tmdb = entry.tmdbId?.toLong(), trakt = entry.traktId?.toLong())
                )
                val origins = buildList {
                    if (TraktLibraryService.WATCHLIST_KEY in entry.listKeys) add(CalendarOrigin.TRAKT_WATCHLIST)
                    if (entry.listKeys.any { it.startsWith(TraktLibraryService.PERSONAL_KEY_PREFIX) }) add(CalendarOrigin.TRAKT_LIST)
                }
                origins.forEach { origin ->
                    candidates += CalendarTitleCandidate(
                        ids, type, origin, entry.listedAt.toInstantOrNull(), entry.name, entry.id, entry.poster
                    )
                }
            }
        }
        if (trackingProviders.isAuthenticated(TrackingProviderId.SIMKL)) part("simkl") {
            simklSyncRepository.ensureLoaded()
            simklSyncRepository.state.value.snapshot.entries.forEach { entry ->
                val media = entry.media ?: return@forEach
                val origin = when (entry.status) {
                    SimklListStatus.WATCHING -> CalendarOrigin.SIMKL_WATCHING
                    SimklListStatus.PLAN_TO_WATCH -> CalendarOrigin.SIMKL_PLAN_TO_WATCH
                    SimklListStatus.ON_HOLD -> CalendarOrigin.SIMKL_ON_HOLD
                    SimklListStatus.COMPLETED -> CalendarOrigin.SIMKL_COMPLETED
                    SimklListStatus.DROPPED -> CalendarOrigin.DROPPED
                    null -> return@forEach
                }
                val ids = CalendarIds(
                    imdb = media.ids.idValue("imdb")?.takeIf { it.startsWith("tt") },
                    tmdb = media.ids.idValue("tmdb")?.toLongOrNull(),
                    tvdb = media.ids.idValue("tvdb")?.toLongOrNull(),
                    simkl = media.ids.simklIdValue()?.toLongOrNull()
                )
                val type = if (entry.isMovieEntry()) CalendarTitleType.MOVIE else CalendarTitleType.SERIES
                val activity = (entry.lastWatchedAt ?: entry.addedToWatchlistAt)?.toInstantOrNull()
                candidates += CalendarTitleCandidate(ids, type, origin, activity, media.title, ids.imdb, entry.localPosterUrl)
            }
            watched.addAll(simklSyncRepository.projection.value.watchedShowEpisodes)
        }
        if (trackingProviders.isAuthenticated(TrackingProviderId.MDBLIST)) part("mdblist") {
            val snapshot = mdbListSyncRepository.currentSnapshot() ?: return@part
            fun add(ids: MdbListIds, type: MdbListItemType, origin: CalendarOrigin, at: Instant?, name: String?, poster: String? = null) {
                val calendarIds = ids.toCalendarIds()
                candidates += CalendarTitleCandidate(
                    calendarIds,
                    if (type == MdbListItemType.MOVIE) CalendarTitleType.MOVIE else CalendarTitleType.SERIES,
                    origin, at, name, calendarIds.imdb ?: calendarIds.tmdb?.let { "tmdb:$it" }, poster
                )
            }
            snapshot.watched.filter { it.type != MdbListItemType.MOVIE }
                .groupBy { it.media.ids.toCalendarIds().primaryAlias() }
                .forEach { (alias, records) ->
                    if (alias == null) return@forEach
                    val latest = records.maxBy { it.watchedAt }
                    add(latest.media.ids, MdbListItemType.SHOW, CalendarOrigin.MDBLIST_WATCHED, latest.watchedAt.toInstantOrNull(), latest.media.title)
                }
            snapshot.playback.filter { it.type != MdbListItemType.MOVIE }.forEach {
                add(it.media.ids, MdbListItemType.SHOW, CalendarOrigin.MDBLIST_PLAYBACK, it.updatedAt.toInstantOrNull(), it.media.title)
            }
            snapshot.dropped.forEach { add(it.ids, MdbListItemType.SHOW, CalendarOrigin.DROPPED, null, null) }
            snapshot.library?.visible()?.itemsByList?.forEach { (listKey, items) ->
                val origin = if (listKey == MDBLIST_WATCHLIST_KEY) CalendarOrigin.MDBLIST_WATCHLIST else CalendarOrigin.MDBLIST_LIST
                items.filter { it.type == MdbListItemType.MOVIE || it.type == MdbListItemType.SHOW }.forEach { item ->
                    add(item.media.ids, item.type, origin, item.listedAt.toInstantOrNull(), item.media.title, item.media.poster)
                }
            }
            watched.addAll(mdbListSyncRepository.currentProjection().watchedShowEpisodes)
        }

        val removed = runPart("hidden") { traktSettingsDataStore.dismissedNextUpKeys.first() }.orEmpty()
        val nextUp = runPart("next up") { enrichmentCache.getNextUpSnapshot(profileId) }.orEmpty()
        val addon = runPart("next up dates") {
            nextUp.mapNotNull { item ->
                if (item.hasAired) return@mapNotNull null
                val date = item.released?.let(::plainReleaseDate) ?: return@mapNotNull null
                CalendarIds.fromContentId(item.contentId) to CalendarEntry(
                    season = item.season,
                    episode = item.episode,
                    title = item.episodeTitle,
                    airDate = date,
                    label = deriveEpisodeLabel(item.season, item.episode),
                    source = CalendarSource.ADDON
                )
            }
        }.orEmpty()
        val usable = candidates.filterNot { it.ids.isEmpty }
        val posters = runPart("posters") {
            calendarCachedPosters(
                usable,
                metaPoster = { type, id -> metaRepository.getCachedMeta(type, id)?.poster },
                nextUpPosters = nextUp.map { it.contentId to it.poster }
            )
        }.orEmpty()
        return CalendarFollowedSnapshot(usable, removed, addon, watched.build(), traktWatchedLoaded, posters)
    }

    private suspend fun part(name: String, block: suspend () -> Unit) {
        runPart(name, block)
    }

    private suspend fun <T> runPart(name: String, block: suspend () -> T): T? = try {
        withTimeoutOrNull(PART_TIMEOUT_MS) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "Skipped $name: ${error.message}")
        null
    }

    private suspend fun <T> Flow<List<T>>.firstOrEmpty(): List<T> =
        withTimeoutOrNull(PART_TIMEOUT_MS) { first() }.orEmpty()

    private companion object {
        const val TAG = "CalendarSources"
        const val PART_TIMEOUT_MS = 5_000L
    }
}

fun calendarTitleType(contentType: String?): CalendarTitleType? = when (contentType?.trim()?.lowercase()) {
    "movie", "film" -> CalendarTitleType.MOVIE
    "series", "tv", "show", "anime" -> CalendarTitleType.SERIES
    else -> null
}

private fun MdbListIds.toCalendarIds() =
    CalendarIds(imdb = imdb?.takeIf { it.startsWith("tt") }, tmdb = tmdb, tvdb = tvdb, trakt = trakt)

private fun Long.toInstantOrNull(): Instant? = takeIf { it > 0L }?.let(Instant::ofEpochMilli)

private fun String.toInstantOrNull(): Instant? =
    runCatching { Instant.parse(this) }.getOrNull() ?: runCatching { OffsetDateTime.parse(this).toInstant() }.getOrNull()

private val PLAIN_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}")

/** The date part of an add-on `released` value, as given; the time in it is not a real air time. */
fun plainReleaseDate(value: String): String? = PLAIN_DATE.find(value.trim())?.value
