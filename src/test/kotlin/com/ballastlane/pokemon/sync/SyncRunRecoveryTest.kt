package com.ballastlane.pokemon.sync

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class SyncRunRecoveryTest {

    private val store = mockk<SyncRunStore>()

    private val recovery = SyncRunRecovery(store)

    @Test
    fun marksLeftoverRunningRunsAsInterrupted() {
        every { store.interruptRunning() } returns 1

        recovery.interruptLeftoverRuns()

        verify(exactly = 1) { store.interruptRunning() }
    }
}
