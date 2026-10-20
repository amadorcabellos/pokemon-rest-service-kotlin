package com.ballastlane.pokemon.sync

import com.ballastlane.pokemon.domain.SyncTrigger
import com.ballastlane.pokemon.exception.SyncAlreadyRunningException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Starts a full synchronization on the `app.sync.schedule.cron` schedule. Only active when
 * `app.sync.schedule.enabled=true`.
 */
@Component
@ConditionalOnProperty(prefix = "app.sync.schedule", name = ["enabled"], havingValue = "true")
class ScheduledSync(private val syncService: PokemonSyncService) {

    @Scheduled(cron = "\${app.sync.schedule.cron}")
    fun startScheduledSync() {
        try {
            val run = syncService.startFullSync(SyncTrigger.SCHEDULED)
            log.info("Started scheduled synchronization run {}", run.id)
        } catch (ex: SyncAlreadyRunningException) {
            log.info("Skipping scheduled synchronization: {}", ex.message)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ScheduledSync::class.java)
    }
}
