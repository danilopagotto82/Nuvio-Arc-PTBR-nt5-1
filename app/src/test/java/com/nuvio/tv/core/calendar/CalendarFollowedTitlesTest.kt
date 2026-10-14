package com.nuvio.tv.core.calendar

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarFollowedTitlesTest {
    private val now = Instant.parse("2026-10-05T23:00:00Z")
    private fun daysAgo(days: Long) = now.minus(Duration.ofDays(days))

    private fun show(origin: CalendarOrigin, ids: CalendarIds, at: Instant? = null, name: String? = null) =
        CalendarTitleCandidate(ids, CalendarTitleType.SERIES, origin, at, name)

    private fun film(origin: CalendarOrigin, ids: CalendarIds, at: Instant? = null) =
        CalendarTitleCandidate(ids, CalendarTitleType.MOVIE, origin, at, "Film")

    @Test fun sourcesSharingAnyIdBecomeOneTitleWithAllIdsAndOrigins() {
        val titles = buildCalendarFollowedTitles(
            listOf(
                show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt1"), daysAgo(40), "Night Shift"),
                show(CalendarOrigin.TRAKT_WATCHED, CalendarIds(imdb = "tt1", trakt = 7), daysAgo(3)),
                show(CalendarOrigin.SIMKL_WATCHING, CalendarIds(trakt = 7, simkl = 99, tmdb = 500), daysAgo(10)),
                show(CalendarOrigin.MDBLIST_WATCHLIST, CalendarIds(tmdb = 500, tvdb = 42))
            ),
            CalendarFollowRules(),
            now
        )
        val title = titles.single()
        assertEquals(CalendarIds(imdb = "tt1", tmdb = 500, tvdb = 42, trakt = 7, simkl = 99), title.ids)
        assertEquals(
            setOf(CalendarOrigin.LIBRARY, CalendarOrigin.TRAKT_WATCHED, CalendarOrigin.SIMKL_WATCHING, CalendarOrigin.MDBLIST_WATCHLIST),
            title.origins
        )
        assertEquals(daysAgo(3), title.lastActivity)
        assertEquals("Night Shift", title.name)
        assertEquals("series:imdb:tt1", title.key)
        assertEquals("tt1", title.contentId)
    }

    @Test fun aFilmAndAShowWithTheSameTmdbNumberStayApart() {
        val titles = buildCalendarFollowedTitles(
            listOf(show(CalendarOrigin.LIBRARY, CalendarIds(tmdb = 1)), film(CalendarOrigin.LIBRARY, CalendarIds(tmdb = 1))),
            CalendarFollowRules(),
            now
        )
        assertEquals(2, titles.size)
    }

    @Test fun startedShowsUntouchedForAYearAreLeftOutUnlessTheBellIsOn() {
        val old = show(CalendarOrigin.PROGRESS, CalendarIds(imdb = "tt1"), daysAgo(400))
        val oldWithBell = show(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt2"), daysAgo(400))
        val recent = show(CalendarOrigin.PROGRESS, CalendarIds(imdb = "tt3"), daysAgo(300))
        val oldButSavedLately = listOf(
            show(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt4"), daysAgo(500)),
            show(CalendarOrigin.TRAKT_WATCHLIST, CalendarIds(imdb = "tt4"), daysAgo(20))
        )
        val savedLongAgo = show(CalendarOrigin.TRAKT_WATCHLIST, CalendarIds(imdb = "tt5"), daysAgo(900))

        val keys = buildCalendarFollowedTitles(
            listOf(old, oldWithBell, recent, savedLongAgo) + oldButSavedLately,
            CalendarFollowRules(bellOn = setOf("series:imdb:tt2")),
            now
        ).map { it.key }.toSet()

        assertEquals(setOf("series:imdb:tt2", "series:imdb:tt3", "series:imdb:tt4", "series:imdb:tt5"), keys)
    }

    @Test fun droppedRemovedFromContinueWatchingAndHiddenTitlesAreLeftOut() {
        val candidates = listOf(
            show(CalendarOrigin.SIMKL_WATCHING, CalendarIds(simkl = 1, imdb = "tt1")),
            show(CalendarOrigin.DROPPED, CalendarIds(simkl = 1)),
            show(CalendarOrigin.PROGRESS, CalendarIds(tmdb = 2)),
            show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt3")),
            show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt4"))
        )
        val titles = buildCalendarFollowedTitles(
            candidates,
            CalendarFollowRules(
                removedFromContinueWatching = setOf("tmdb:2"),
                hiddenTitleKeys = setOf("series:imdb:tt3")
            ),
            now
        )
        assertEquals(listOf("series:imdb:tt4"), titles.map { it.key })
    }

    @Test fun aHiddenTitleStaysHiddenWhenItsKeyUsesAnotherOfItsIds() {
        val titles = buildCalendarFollowedTitles(
            listOf(show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt9", tmdb = 9))),
            CalendarFollowRules(hiddenTitleKeys = setOf("series:tmdb:9")),
            now
        )
        assertTrue(titles.isEmpty())
    }

    @Test fun personalListsCountOnlyWhenTheSettingIncludesThem() {
        val candidates = listOf(
            show(CalendarOrigin.TRAKT_LIST, CalendarIds(imdb = "tt1")),
            show(CalendarOrigin.MDBLIST_LIST, CalendarIds(imdb = "tt2")),
            show(CalendarOrigin.TRAKT_LIST, CalendarIds(imdb = "tt3")),
            show(CalendarOrigin.TRAKT_WATCHLIST, CalendarIds(imdb = "tt3"))
        )
        assertEquals(
            listOf("series:imdb:tt3"),
            buildCalendarFollowedTitles(candidates, CalendarFollowRules(), now).map { it.key }
        )
        assertEquals(3, buildCalendarFollowedTitles(candidates, CalendarFollowRules(includePersonalLists = true), now).size)
    }

    @Test fun filmsCountWhenSavedNotWhenOnlyWatched() {
        val titles = buildCalendarFollowedTitles(
            listOf(film(CalendarOrigin.PROGRESS, CalendarIds(imdb = "tt1")), film(CalendarOrigin.SIMKL_PLAN_TO_WATCH, CalendarIds(imdb = "tt2"))),
            CalendarFollowRules(),
            now
        )
        assertEquals(listOf("movie:imdb:tt2"), titles.map { it.key })
    }

    @Test fun aBellOnATitleNoSourceHasStillFollowsIt() {
        val titles = buildCalendarFollowedTitles(emptyList(), CalendarFollowRules(bellOn = setOf("series:tmdb:77")), now)
        val title = titles.single()
        assertTrue(title.bellOn)
        assertEquals(CalendarIds(tmdb = 77), title.ids)
        assertEquals(setOf(CalendarOrigin.BELL), title.origins)
    }

    @Test fun capKeepsBellTitlesAndTheMostRecentlyActive() {
        val candidates = (1..10).map { show(CalendarOrigin.PROGRESS, CalendarIds(imdb = "tt$it"), daysAgo(it.toLong())) } +
            show(CalendarOrigin.WATCHED, CalendarIds(imdb = "tt99"), daysAgo(200))
        val titles = buildCalendarFollowedTitles(candidates, CalendarFollowRules(bellOn = setOf("series:imdb:tt99"), cap = 4), now)
        assertEquals(listOf("series:imdb:tt99", "series:imdb:tt1", "series:imdb:tt2", "series:imdb:tt3"), titles.map { it.key })
    }

    @Test fun capOfThreeHundredByDefault() {
        val candidates = (1..450).map { show(CalendarOrigin.LIBRARY, CalendarIds(tmdb = it.toLong()), daysAgo(it.toLong())) }
        val titles = buildCalendarFollowedTitles(candidates, CalendarFollowRules(), now)
        assertEquals(CALENDAR_MAX_TITLES, titles.size)
        assertEquals("series:tmdb:1", titles.first().key)
    }

    @Test fun reconcileKeepsTheStoredKeyAndDatesAndDropsTitlesNoLongerFollowed() {
        val stored = listOf(
            CalendarTitleRecord(
                key = "series:tmdb:5", type = CalendarTitleType.SERIES, ids = CalendarIds(tmdb = 5),
                tvmazeId = 12, nextRefreshAtMs = 1234L, status = CalendarShowStatus.RUNNING
            ),
            CalendarTitleRecord(key = "series:imdb:tt8", type = CalendarTitleType.SERIES, ids = CalendarIds(imdb = "tt8"))
        )
        val followed = buildCalendarFollowedTitles(
            listOf(show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt5", tmdb = 5), name = "Five"), show(CalendarOrigin.LIBRARY, CalendarIds(imdb = "tt6"))),
            CalendarFollowRules(),
            now
        )
        val records = reconcileCalendarRecords(followed, stored)
        val five = records.first { it.ids.tmdb == 5L }
        assertEquals("series:tmdb:5", five.key)
        assertEquals(12, five.tvmazeId)
        assertEquals(1234L, five.nextRefreshAtMs)
        assertEquals("tt5", five.ids.imdb)
        assertEquals("Five", five.name)
        val six = records.first { it.ids.imdb == "tt6" }
        assertEquals(0L, six.nextRefreshAtMs)
        assertFalse(records.any { it.key == "series:imdb:tt8" })
    }

    @Test fun titleKeysParseBackAndMatchByAnySharedId() {
        assertEquals(CalendarTitleType.SERIES to CalendarIds(imdb = "tt1"), parseCalendarTitleKey("series:imdb:tt1"))
        assertEquals(CalendarTitleType.MOVIE to CalendarIds(tmdb = 3), parseCalendarTitleKey("movie:tmdb:3"))
        assertNull(parseCalendarTitleKey("anime:tmdb:3"))
        assertNull(parseCalendarTitleKey("series:title:x"))
        assertTrue(calendarKeyMatches("series:tmdb:3", CalendarTitleType.SERIES, CalendarIds(imdb = "tt1", tmdb = 3)))
        assertFalse(calendarKeyMatches("movie:tmdb:3", CalendarTitleType.SERIES, CalendarIds(tmdb = 3)))
    }
}
