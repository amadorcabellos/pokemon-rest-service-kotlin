package com.ballastlane.pokemon.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for synchronizing PokeAPI into PostgreSQL ({@code app.sync.*}).
 *
 * @param batchSize catalogue entries requested per PokeAPI page during a full sync
 * @param delayBetweenPokemon pause after each pokemon, so a full sync leaves rate-limit room for API traffic
 * @param pauseWhenRejected wait before retrying when the circuit breaker or rate limiter rejects a call
 * @param maxConsecutivePauses rejections in a row after which a full sync gives up (status {@code FAILED})
 * @param schedule optional scheduled full sync
 */
@Validated
@ConfigurationProperties(prefix = "app.sync")
public record SyncProperties(
		@DefaultValue("50") @Min(1) @Max(100) int batchSize,
		@DefaultValue("100ms") Duration delayBetweenPokemon,
		@DefaultValue("5s") Duration pauseWhenRejected,
		@DefaultValue("12") @Min(0) int maxConsecutivePauses,
		@DefaultValue @Valid Schedule schedule) {

	/**
	 * @param enabled whether a full sync runs on the cron schedule (disabled by default)
	 * @param cron Spring cron expression for the scheduled full sync
	 */
	public record Schedule(
			@DefaultValue("false") boolean enabled,
			@DefaultValue("0 0 3 * * *") @NotBlank String cron) {
	}

}
