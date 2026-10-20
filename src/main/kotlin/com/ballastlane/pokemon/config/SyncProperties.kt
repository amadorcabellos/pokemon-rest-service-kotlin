package com.ballastlane.pokemon.config

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Settings for synchronizing PokeAPI into PostgreSQL (`app.sync.*`).
 *
 * @property batchSize catalogue entries requested per PokeAPI page during a full sync
 * @property delayBetweenPokemon pause after each pokemon, so a full sync leaves rate-limit room for API traffic
 * @property pauseWhenRejected wait before retrying when the circuit breaker or rate limiter rejects a call
 * @property maxConsecutivePauses rejections in a row after which a full sync gives up (status `FAILED`)
 * @property schedule optional scheduled full sync
 */
@JvmRecord
@Validated
@ConfigurationProperties(prefix = "app.sync")
final data class SyncProperties(
    @field:Min(1) @field:Max(100) val batchSize: Int = 50,
    val delayBetweenPokemon: Duration = Duration.ofMillis(100),
    val pauseWhenRejected: Duration = Duration.ofSeconds(5),
    @field:Min(0) val maxConsecutivePauses: Int = 12,
    @field:Valid val schedule: Schedule = Schedule(),
) {

    /**
     * @property enabled whether a full sync runs on the cron schedule (disabled by default)
     * @property cron Spring cron expression for the scheduled full sync
     */
    @JvmRecord
    data class Schedule(
        val enabled: Boolean = false,
        @field:NotBlank val cron: String = "0 0 3 * * *",
    )
}
