package com.nuvio.tv.core.calendar

import android.content.res.Resources
import android.util.Log
import com.nuvio.tv.core.player.ScreensaverController
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingProviderRegistry
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CalendarClock

enum class CalendarRefreshReason { HOME_SETTLED, CALENDAR_OPENED, SETTINGS_CHANGED }

data class CalendarState(
    val profileId: Int? = null,
    /** Followed titles with their releases, hidden titles left out. Empty until [loaded]. */
    val titles: List<CalendarTitleRecord> = emptyList(),
    /** The cache and the profile's settings are both read. */
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    val remaining: Int = 0,
    val seenReminders: Set<String> = emptySet(),
    /** Watched episodes as last read by a refresh; null until one has run for this profile. */
    val watched: CalendarWatchedIndex? = null
)

/** What the refresher reads from the rest of the app. */
interface CalendarRefreshInputs {
    val activeProfileId: StateFlow<Int>
    val playbackActive: StateFlow<Boolean>
    val settings: StateFlow<CalendarSettings?>
    suspend fun settings(profileId: Int): CalendarSettings
    suspend fun followed(profileId: Int): CalendarFollowedSnapshot
    fun traktSignedIn(): Boolean
    suspend fun traktCalendar(today: LocalDate): List<TraktCalendarItem>?
    suspend fun refreshConcurrency(): Int
    fun region(): String
}

@Singleton
class DefaultCalendarRefreshInputs @Inject constructor(
    private val settingsStore: CalendarSettingsDataStore,
    private val sourceReader: CalendarFollowedSourceReader,
    private val traktCalendarClient: TraktCalendarClient,
    private val trackingProviders: TrackingProviderRegistry,
    private val deviceTier: CalendarDeviceTier,
    profileManager: ProfileManager,
    screensaverController: ScreensaverController
) : CalendarRefreshInputs {
    override val activeProfileId = profileManager.activeProfileId
    override val playbackActive = screensaverController.playbackActive
    override val settings = settingsStore.settings
    override suspend fun settings(profileId: Int) = settingsStore.current(profileId)
    override suspend fun followed(profileId: Int) = sourceReader.read(profileId)
    override fun traktSignedIn() = trackingProviders.isAuthenticated(TrackingProviderId.TRAKT)
    override suspend fun traktCalendar(today: LocalDate) = traktCalendarClient.myShows(today)
    override suspend fun refreshConcurrency() = deviceTier.refreshConcurrency()

    /** The box's own region, not the app language, for film release dates. */
    override fun region(): String =
        Resources.getSystem().configuration.locales[0]?.country?.takeIf { it.length == 2 } ?: "US"
}

/**
 * Keeps the Calendar of the active profile. A refresh reads the followed titles from what the app already has, asks
 * the Trakt calendar when signed in, then refreshes only the titles that are due, 2 or 4 at a time, waiting while
 * something plays. Every finished title is kept, so a cancelled refresh carries on where it stopped next time.
 * Nothing is read before the first call.
 */
@Singleton
class CalendarRefresher internal constructor(
    private val storage: CalendarStorage,
    private val inputs: CalendarRefreshInputs,
    private val remote: CalendarRemoteSources,
    private val clock: Clock,
    private val scope: CoroutineScope
) {
    @Inject
    constructor(
        storage: CalendarStorage,
        inputs: CalendarRefreshInputs,
        remote: CalendarRemoteSources,
        @CalendarClock clock: Clock
    ) : this(storage, inputs, remote, clock, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    /** The profile and storage generation a refresh started with; anything it writes after either changed is dropped. */
    private data class Owner(val profileId: Int, val generation: Int)

    private val started = AtomicBoolean(false)
    private val dataMutex = Mutex()
    private val jobLock = Any()
    private var job: Job? = null
    private var runAgain: CalendarRefreshReason? = null

    private var data = CalendarSnapshot()
    private var owner: Owner? = null
    private var watched: CalendarWatchedIndex? = null

    private val _state = MutableStateFlow(CalendarState())
    val state: StateFlow<CalendarState> = _state.asStateFlow()

    fun requestRefresh(reason: CalendarRefreshReason) {
        ensureStarted()
        synchronized(jobLock) {
            val previous = job
            if (previous?.isActive == true) {
                if (reason == CalendarRefreshReason.SETTINGS_CHANGED) runAgain = reason
                return
            }
            job = scope.launch(start = CoroutineStart.LAZY) {
                previous?.join()
                var next: CalendarRefreshReason? = reason
                while (next != null) {
                    runRefresh(next)
                    next = synchronized(jobLock) { runAgain.also { runAgain = null } }
                }
            }.also { it.start() }
        }
    }

    /** Reads the cached Calendar without asking for anything new. */
    fun start() = ensureStarted()

    fun cancelRefresh() {
        synchronized(jobLock) {
            runAgain = null
            job?.cancel()
        }
    }

    suspend fun dueReminders(): CalendarDueReminders {
        ensureStarted()
        val current = ensureLoaded(inputs.activeProfileId.value)
        val settings = inputs.settings(current.profileId)
        val index = watchedIndex(current)
        val (titles, seen) = dataMutex.withLock { data.titles.visible(settings) to data.seenReminders.keys }
        return com.nuvio.tv.core.calendar.dueReminders(titles, settings.bellOn, seen, index::isWatched, clock.instant())
    }

    suspend fun markRemindersSeen(episodeKeys: Collection<String>) {
        if (episodeKeys.isEmpty()) return
        ensureStarted()
        val current = ensureLoaded(inputs.activeProfileId.value)
        withOwner(current) {
            data = data.copy(seenReminders = data.seenReminders.withSeen(episodeKeys, clock.instant()))
            publishLocked()
        }
        save(current, force = true)
    }

    private fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            var previous: Pair<Int, Int>? = null
            combine(inputs.activeProfileId, storage.generations) { profileId, generation -> profileId to generation }
                .distinctUntilChanged()
                .collect { key ->
                    if (previous != null) cancelRefresh()
                    previous = key
                    ensureLoaded(key.first)
                }
        }
        scope.launch {
            inputs.settings.collect { dataMutex.withLock { publishLocked() } }
        }
    }

    private suspend fun runRefresh(reason: CalendarRefreshReason) {
        val me = currentCoroutineContext()[Job]
        val current = ensureLoaded(inputs.activeProfileId.value)
        setRefreshing(current, me, refreshing = true, remaining = 0)
        try {
            val profileId = current.profileId
            val settings = inputs.settings(profileId)
            val now = clock.instant()
            val sources = inputs.followed(profileId)
            withOwner(current) {
                watched = sources.watched
                publishLocked()
            } ?: return

            val traktSignedIn = inputs.traktSignedIn()
            val snapshot = dataMutex.withLock { data }
            val traktDue = now.toEpochMilli() - snapshot.traktCalendarFetchedAtMs >= CALENDAR_TRAKT_REFRESH.toMillis()
            val traktItems = if (traktSignedIn && traktDue) {
                awaitNoPlayback()
                inputs.traktCalendar(now.atZone(ZoneOffset.UTC).toLocalDate())
            } else {
                null
            }
            val traktCandidates = when {
                !traktSignedIn || !sources.traktWatchedLoaded -> emptyList()
                traktItems != null -> traktCalendarCandidates(traktItems)
                else -> snapshot.titles.filter { CalendarOrigin.TRAKT_CALENDAR in it.origins }.map {
                    CalendarTitleCandidate(it.ids, it.type, CalendarOrigin.TRAKT_CALENDAR, name = it.name)
                }
            }
            val rules = CalendarFollowRules(
                includePersonalLists = settings.includePersonalLists,
                hiddenTitleKeys = settings.hiddenTitleKeys,
                bellOn = settings.bellOn,
                removedFromContinueWatching = sources.removedFromContinueWatching
            )
            val followed = buildCalendarFollowedTitles(sources.candidates + traktCandidates, rules, now)
                .withCachedPosters(sources.cachedPosters)

            val due = withOwner(current) {
                var records = applyAddonEntries(reconcileCalendarRecords(followed, data.titles), sources.addonEntries, now)
                records = when {
                    !traktSignedIn -> records.map { it.withoutSources(CalendarSource.TRAKT, CalendarSource.TRAKT_NEXT) }
                    traktItems != null -> applyTraktCalendar(records, traktItems, now)
                    else -> records
                }
                data = data.copy(
                    titles = records,
                    traktCalendarFetchedAtMs = when {
                        traktItems != null -> now.toEpochMilli()
                        traktSignedIn -> data.traktCalendarFetchedAtMs
                        else -> 0L
                    }
                )
                publishLocked()
                records.filter { it.isDue(now) }
                    .sortedWith(compareByDescending<CalendarTitleRecord> { CalendarOrigin.BELL in it.origins }.thenBy { it.nextRefreshAtMs })
            } ?: return
            save(current, force = true)
            Log.i(TAG, "refresh reason=$reason titles=${followed.size} due=${due.size} trakt=${traktItems?.size ?: "-"}")
            if (due.isEmpty()) return

            setRefreshing(current, me, refreshing = true, remaining = due.size)
            val fetcher = CalendarTitleFetcher(remote, inputs.region())
            val queue = ArrayDeque(due.map { it.key })
            supervisorScope {
                repeat(inputs.refreshConcurrency().coerceAtLeast(1)) {
                    launch {
                        while (true) {
                            awaitNoPlayback()
                            val key = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                            refreshOne(current, key, fetcher)
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Calendar refresh stopped: ${error.message}")
        } finally {
            withContext(NonCancellable) {
                save(current, force = true)
                setRefreshing(current, me, refreshing = false, remaining = 0)
            }
        }
    }

    private suspend fun refreshOne(current: Owner, key: String, fetcher: CalendarTitleFetcher) {
        val (record, tvMazeIds, traktFetchedAt) = withOwner(current) {
            Triple(data.titles.firstOrNull { it.key == key }, data.tvmazeIds, data.traktCalendarFetchedAtMs)
        } ?: return
        if (record == null) return
        val now = clock.instant()
        val outcome = try {
            fetcher.refresh(record, tvMazeIds, record.isCoveredByTrakt(traktFetchedAt, now), now)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CalendarFetchOutcome(fetcher.failed(record, now))
        }
        withOwner(current) {
            data = data.copy(
                titles = data.titles.map { if (it.key == key) outcome.record else it },
                tvmazeIds = data.tvmazeIds + outcome.tvMazeIds
            )
            publishLocked(remaining = (_state.value.remaining - 1).coerceAtLeast(0))
        }
        save(current, force = false)
    }

    private suspend fun ensureLoaded(profileId: Int): Owner = dataMutex.withLock {
        val wanted = Owner(profileId, storage.generation())
        if (owner != wanted) {
            data = storage.load(profileId)
            owner = wanted
            watched = null
            publishLocked(refreshing = false, remaining = 0)
        }
        wanted
    }

    /** Runs [block] under the lock only while [current] still owns the data; null otherwise. */
    private suspend inline fun <T> withOwner(current: Owner, block: () -> T): T? = dataMutex.withLock {
        currentCoroutineContext().ensureActive()
        if (owner != current) null else block()
    }

    private suspend fun watchedIndex(current: Owner): CalendarWatchedIndex {
        dataMutex.withLock { if (owner == current) watched?.let { return it } }
        return readWatched(current)
    }

    private suspend fun readWatched(current: Owner): CalendarWatchedIndex {
        val index = inputs.followed(current.profileId).watched
        withOwner(current) {
            watched = index
            publishLocked()
        }
        return index
    }

    /** Reads what has been watched again, for when watch progress changes outside a refresh. */
    suspend fun reloadWatched() {
        ensureStarted()
        readWatched(ensureLoaded(inputs.activeProfileId.value))
    }

    private suspend fun save(current: Owner, force: Boolean) {
        val snapshot = dataMutex.withLock { if (owner == current) data else null } ?: return
        storage.save(current.profileId, snapshot, current.generation, force)
    }

    /** Only the refresh that is current may change the refreshing flag. */
    private suspend fun setRefreshing(current: Owner, me: Job?, refreshing: Boolean, remaining: Int) {
        if (synchronized(jobLock) { job !== me }) return
        dataMutex.withLock { if (owner == current) publishLocked(refreshing = refreshing, remaining = remaining) }
    }

    private fun List<CalendarTitleRecord>.visible(settings: CalendarSettings): List<CalendarTitleRecord> {
        val hidden = settings.hiddenTitleKeys
        return if (hidden.isEmpty()) this else filterNot { title -> hidden.any(title::matchesKey) }
    }

    private fun publishLocked(
        refreshing: Boolean = _state.value.refreshing,
        remaining: Int = _state.value.remaining
    ) {
        val profileId = owner?.profileId
        val settings = inputs.settings.value?.takeIf { profileId != null && it.profileId == profileId }
        _state.value = CalendarState(
            profileId = profileId,
            titles = settings?.let { data.titles.visible(it) }.orEmpty(),
            loaded = settings != null,
            refreshing = refreshing,
            remaining = remaining,
            seenReminders = data.seenReminders.keys,
            watched = watched
        )
    }

    private suspend fun awaitNoPlayback() {
        inputs.playbackActive.first { active -> !active }
    }

    private companion object {
        const val TAG = "CalendarRefresher"
    }
}
