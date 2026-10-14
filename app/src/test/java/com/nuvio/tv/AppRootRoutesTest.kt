package com.nuvio.tv

import com.nuvio.tv.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRootRoutesTest {
    @Test fun `a hidden Calendar is not a menu page, so Back from it goes Home`() {
        assertTrue(Screen.Calendar.route in appRootRoutes(discoverInSidebar = false, calendarInMenu = true))
        assertFalse(Screen.Calendar.route in appRootRoutes(discoverInSidebar = false, calendarInMenu = false))
    }

    @Test fun `the other menu pages stay root pages`() {
        assertEquals(
            setOf(Screen.Home.route, Screen.Search.route, Screen.Library.route, Screen.Settings.route, Screen.Discover.route),
            appRootRoutes(discoverInSidebar = true, calendarInMenu = false)
        )
        assertFalse(Screen.Discover.route in appRootRoutes(discoverInSidebar = false, calendarInMenu = true))
    }

    @Test fun `the Search button opens search outside the player and before sign-in`() {
        assertTrue(searchButtonOpensSearch(Screen.Home.route, playbackUiActive = false))
        assertTrue(searchButtonOpensSearch(Screen.Library.route, playbackUiActive = false))
        assertTrue(searchButtonOpensSearch(Screen.Detail.route, playbackUiActive = false))
        assertFalse(searchButtonOpensSearch(Screen.Player.route, playbackUiActive = true))
        assertFalse(searchButtonOpensSearch(Screen.Detail.route, playbackUiActive = true))
        assertFalse(searchButtonOpensSearch(Screen.ProfileSelection.route, playbackUiActive = false))
        assertFalse(searchButtonOpensSearch(Screen.AuthQrSignIn.route, playbackUiActive = false))
        assertFalse(searchButtonOpensSearch(null, playbackUiActive = false))
    }
}
