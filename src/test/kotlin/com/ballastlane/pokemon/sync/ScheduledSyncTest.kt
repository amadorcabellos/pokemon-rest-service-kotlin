package com.ballastlane.pokemon.sync

import com.ballastlane.pokemon.domain.SyncRunStatus
import com.ballastlane.pokemon.domain.SyncTrigger
import com.ballastlane.pokemon.exception.SyncAlreadyRunningException
import com.ballastlane.pokemon.web.dto.SyncRunReport
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldHaveSize
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ScheduledSyncTest {

    private val syncService = mockk<PokemonSyncService>()

    private val scheduledSync = ScheduledSync(syncService)

    @Test
    fun startsAScheduledFullSync() {
        every { syncService.startFullSync(SyncTrigger.SCHEDULED) } returns SyncRunReport(
            1L, SyncTrigger.SCHEDULED, SyncRunStatus.RUNNING, Instant.now(), null, 0, 0, 0, 0, listOf(),
        )

        scheduledSync.startScheduledSync()

        verify(exactly = 1) { syncService.startFullSync(SyncTrigger.SCHEDULED) }
    }

    @Test
    fun skipsWhenAFullSyncIsAlreadyRunning() {
        every { syncService.startFullSync(SyncTrigger.SCHEDULED) } throws SyncAlreadyRunningException(3L)

        shouldNotThrowAny { scheduledSync.startScheduledSync() }
    }

    @Test
    fun isDisabledByDefault() {
        val contextRunner = ApplicationContextRunner()
            .withBean(PokemonSyncService::class.java, { syncService })
            .withUserConfiguration(ScheduledSync::class.java)

        contextRunner.run { context -> context.getBeansOfType(ScheduledSync::class.java).shouldBeEmpty() }
        contextRunner.withPropertyValues("app.sync.schedule.enabled=true")
            .run { context -> context.getBeansOfType(ScheduledSync::class.java) shouldHaveSize 1 }
    }
}
