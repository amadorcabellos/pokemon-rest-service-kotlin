package com.ballastlane.poke.web.dto;

import com.ballastlane.poke.domain.SyncRunStatus;
import com.ballastlane.poke.domain.SyncTrigger;
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
