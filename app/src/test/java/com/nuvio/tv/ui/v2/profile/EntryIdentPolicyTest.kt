package com.nuvio.tv.ui.v2.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryIdentPolicyTest {
    @Test fun disabledIntroSkipsOverlayWithoutReplayingOnOrdinaryNavigation() {
        val state = ProfileEntryState()
        state.start(enabled = false)
        assertFalse(state.pending)
        assertTrue(state.hasRunThisSession)
        state.start(enabled = true)
        assertTrue(state.pending)
        assertFalse(state.homeReady)
        assertFalse(state.revealing)
    }

    @Test fun remoteRevealWaitsForHomeAndKeepsOverlayUntilFadeCompletes() {
        val state = ProfileEntryState()
        state.start()
        state.requestReveal()
        assertFalse(state.revealRequested)
        state.homeReady = true
        state.requestReveal()
        assertTrue(state.revealRequested)
        assertTrue(state.pending)
        state.finish()
        assertFalse(state.pending)
        state.start()
        assertFalse(state.revealRequested)
    }

    @Test fun cachedHomeCompletesOnlyAfterNaturalReveal() {
        assertFalse(EntryIdentPolicy.shouldFinish(0, true))
        assertFalse(EntryIdentPolicy.shouldFinish(EntryIdentPolicy.MinimumCycleMs - 1, true))
        assertTrue(EntryIdentPolicy.shouldFinish(EntryIdentPolicy.MinimumCycleMs, true))
    }

    @Test fun slowHomeWaitsForReadinessButNeverHidesRecoveryIndefinitely() {
        assertFalse(EntryIdentPolicy.shouldFinish(EntryIdentPolicy.MinimumCycleMs, false))
        assertTrue(EntryIdentPolicy.shouldFinish(EntryIdentPolicy.MinimumCycleMs, true))
        assertFalse(EntryIdentPolicy.shouldFinish(EntryIdentPolicy.RecoveryTimeoutMs - 1, false))
        assertTrue(EntryIdentPolicy.shouldFinish(EntryIdentPolicy.RecoveryTimeoutMs, false))
    }

    @Test fun selectingAnotherProfileInvalidatesPreviousHomeReadiness() {
        val state = ProfileEntryState()
        state.start()
        assertFalse(state.hasRunThisSession)
        state.markShown()
        assertTrue(state.hasRunThisSession)
        state.homeReady = true
        state.finish()
        state.start()
        assertTrue(state.pending)
        assertFalse(state.homeReady)
    }

    @Test fun introCutBeforeItsFirstFrameStartsAgainOnEntry() {
        val state = ProfileEntryState()
        state.start(enabled = true)
        assertFalse(state.shouldStartOnEntry())
        // Home leaves composition and comes back before the intro drew anything.
        state.finish()
        assertFalse(state.hasRunThisSession)
        assertTrue(state.shouldStartOnEntry())
        state.start(enabled = true)
        assertTrue(state.pending)
    }

    @Test fun introThatWasShownIsNotReplayedOnEntry() {
        val state = ProfileEntryState()
        state.start(enabled = true)
        state.markShown()
        state.finish()
        assertTrue(state.hasRunThisSession)
        assertFalse(state.shouldStartOnEntry())
    }

    @Test fun markShownWithoutAPendingIntroChangesNothing() {
        val state = ProfileEntryState()
        state.markShown()
        assertFalse(state.hasRunThisSession)
        assertTrue(state.shouldStartOnEntry())
    }

    @Test fun switchedOffIntroCountsAsRunAtOnce() {
        val state = ProfileEntryState()
        state.start(enabled = false)
        assertFalse(state.shouldStartOnEntry())
    }
}
