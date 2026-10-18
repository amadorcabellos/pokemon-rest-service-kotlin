package com.ballastlane.pokemon.web.dto;

import com.ballastlane.pokemon.domain.SyncRunStatus;
import com.ballastlane.pokemon.domain.SyncTrigger;
import java.time.Instant;
import java.util.List;

/**
 * Status and counts of a full synchronization run. {@code finishedAt} is null while the run is in progress.
 */
public record SyncRunReport(
		Long id,
		SyncTrigger trigger,
		SyncRunStatus status,
		Instant startedAt,
		Instant finishedAt,
		int catalogueCount,
		int created,
		int updated,
		int failed,
		List<SyncFailure> failures) {
}
