package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant

data class CalendarTitleCandidate(
    val ids: CalendarIds,
    val type: CalendarTitleType,
    val origin: CalendarOrigin,
    val lastActivity: Instant? = null,
    val name: String? = null,
    val contentId: String? = null,
    val poster: String? = null
)

data class CalendarFollowedTitle(
    val key: String,
    val ids: CalendarIds,
    val type: CalendarTitleType,
    val origins: Set<CalendarOrigin>,
    val lastActivity: Instant?,
    val name: String? = null,
    val contentId: String? = null,
    val poster: String? = null,
    val bellOn: Boolean = false
)

data class CalendarFollowRules(
    val includePersonalLists: Boolean = false,
    val hiddenTitleKeys: Set<String> = emptySet(),
    val bellOn: Set<String> = emptySet(),
    val removedFromContinueWatching: Set<String> = emptySet(),
    val staleAfter: Duration = Duration.ofDays(365),
    val cap: Int = CALENDAR_MAX_TITLES
)

const val CALENDAR_MAX_TITLES = 300

/**
 * Hidden titles are always left out. Unless the bell is on, so are dropped titles, titles removed from Continue
 * Watching, started shows idle for [CalendarFollowRules.staleAfter] and films that were only watched, not saved.
 */
fun buildCalendarFollowedTitles(
    candidates: List<CalendarTitleCandidate>,
    rules: CalendarFollowRules,
    now: Instant
): List<CalendarFollowedTitle> {
    val usable = candidates.filter { !it.ids.isEmpty && (rules.includePersonalLists || !it.origin.personalList) }
    val bellOnly = rules.bellOn.mapNotNull { key ->
        val (type, ids) = parseCalendarTitleKey(key) ?: return@mapNotNull null
        CalendarTitleCandidate(ids, type, CalendarOrigin.BELL)
    }
    val groups = groupBySharedIds(usable + bellOnly)
    val removedAliases = rules.removedFromContinueWatching.flatMapTo(mutableSetOf()) { CalendarIds.fromContentId(it).aliases() }
    val staleBefore = now.minus(rules.staleAfter)

    val titles = groups.mapNotNull { group ->
        val type = group.first().type
        val ids = group.fold(CalendarIds()) { acc, candidate -> acc.merged(candidate.ids) }
        if (rules.hiddenTitleKeys.any { calendarKeyMatches(it, type, ids) }) return@mapNotNull null
        val bell = rules.bellOn.any { calendarKeyMatches(it, type, ids) }
        val origins = group.mapTo(linkedSetOf()) { it.origin }.apply { if (bell) add(CalendarOrigin.BELL) }
        val lastActivity = group.mapNotNull { it.lastActivity }.maxOrNull()
        if (!bell) {
            if (CalendarOrigin.DROPPED in origins) return@mapNotNull null
            if (type == CalendarTitleType.SERIES && ids.aliases().any { it in removedAliases }) return@mapNotNull null
            if (origins.any { it.started } && lastActivity != null && lastActivity.isBefore(staleBefore)) return@mapNotNull null
            if (type == CalendarTitleType.MOVIE && origins.none { it.savesFilms }) return@mapNotNull null
        }
        val named = group.sortedBy { it.origin.ordinal }
        CalendarFollowedTitle(
            key = calendarTitleKey(type, ids) ?: return@mapNotNull null,
            ids = ids,
            type = type,
            origins = origins,
            lastActivity = lastActivity,
            name = named.firstNotNullOfOrNull { it.name?.takeIf(String::isNotBlank) },
            contentId = ids.imdb ?: named.firstNotNullOfOrNull { it.contentId?.takeIf(String::isNotBlank) }
                ?: ids.tmdb?.let { "tmdb:$it" },
            poster = named.firstNotNullOfOrNull { it.poster?.takeIf(String::isNotBlank) },
            bellOn = bell
        )
    }

    val ordered = titles.sortedWith(
        compareByDescending<CalendarFollowedTitle> { it.bellOn }
            .thenByDescending { it.lastActivity ?: Instant.EPOCH }
            .thenBy { it.name.orEmpty().lowercase() }
            .thenBy { it.key }
    )
    val bells = ordered.count { it.bellOn }
    val room = (rules.cap - bells).coerceAtLeast(0)
    return ordered.filter { it.bellOn } + ordered.filterNot { it.bellOn }.take(room)
}

private fun groupBySharedIds(candidates: List<CalendarTitleCandidate>): List<List<CalendarTitleCandidate>> {
    val parent = IntArray(candidates.size) { it }
    fun find(i: Int): Int {
        var root = i
        while (parent[root] != root) root = parent[root]
        var node = i
        while (parent[node] != root) { val next = parent[node]; parent[node] = root; node = next }
        return root
    }
    val owner = HashMap<String, Int>()
    candidates.forEachIndexed { index, candidate ->
        candidate.ids.aliases().forEach { alias ->
            val key = "${candidate.type}|$alias"
            val other = owner.putIfAbsent(key, index)
            if (other != null) {
                val a = find(index)
                val b = find(other)
                if (a != b) parent[maxOf(a, b)] = minOf(a, b)
            }
        }
    }
    return candidates.indices.groupBy(::find).values.map { members -> members.map(candidates::get) }
}

/** A followed title keeps its stored record (and key) when they share an id; a new one is due at once. */
fun reconcileCalendarRecords(
    followed: List<CalendarFollowedTitle>,
    stored: List<CalendarTitleRecord>
): List<CalendarTitleRecord> {
    val used = BooleanArray(stored.size)
    return followed.map { title ->
        val index = stored.indices.firstOrNull { i ->
            !used[i] && stored[i].type == title.type && stored[i].ids.sharesIdWith(title.ids)
        }
        val previous = index?.let { used[it] = true; stored[it] }
        previous?.copy(
            ids = title.ids.merged(previous.ids),
            name = title.name ?: previous.name,
            contentId = title.contentId ?: previous.contentId,
            poster = title.poster ?: previous.poster,
            origins = title.origins,
            lastActivityMs = title.lastActivity?.toEpochMilli()
        ) ?: CalendarTitleRecord(
            key = title.key,
            type = title.type,
            ids = title.ids,
            name = title.name,
            contentId = title.contentId,
            poster = title.poster,
            origins = title.origins,
            lastActivityMs = title.lastActivity?.toEpochMilli()
        )
    }
}
