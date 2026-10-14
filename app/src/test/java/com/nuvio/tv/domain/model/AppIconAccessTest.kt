package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppIconAccessTest {
    @Test
    fun supporterColoursNeedTheirTheme() {
        val icons = availableAppIcons(CosmeticEntitlements.None, AppIconOption.ORIGINAL)
        assertEquals(
            listOf(AppIconOption.ORIGINAL, AppIconOption.EMERALD, AppIconOption.COPPER, AppIconOption.GLASS) +
                listOf(AppIconOption.CRIMSON, AppIconOption.OCEAN, AppIconOption.VIOLET, AppIconOption.EMERALD_GREEN,
                    AppIconOption.AMBER, AppIconOption.ROSE, AppIconOption.WHITE),
            icons
        )
    }

    @Test
    fun unlockedSupporterThemeUnlocksItsIcon() {
        val icons = availableAppIcons(CosmeticEntitlements(setOf(CosmeticEntitlement.GOLD_THEME)), AppIconOption.ORIGINAL)
        assertTrue(AppIconOption.GOLD in icons)
        assertFalse(AppIconOption.JADE in icons)
    }

    @Test
    fun iconInUseStaysListed() {
        val icons = availableAppIcons(CosmeticEntitlements.None, AppIconOption.ROSE_GOLD)
        assertTrue(AppIconOption.ROSE_GOLD in icons)
        assertFalse(AppIconOption.GRAPHITE in icons)
    }
}
