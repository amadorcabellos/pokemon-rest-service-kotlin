package com.ballastlane.pokemon.sync;

import com.ballastlane.pokemon.domain.SyncRun;
import com.ballastlane.pokemon.domain.SyncRunStatus;
import com.ballastlane.pokemon.domain.SyncTrigger;
import com.ballastlane.pokemon.exception.SyncAlreadyRunningException;
import com.ballastlane.pokemon.repository.SyncRunRepository;
import com.ballastlane.pokemon.web.dto.SyncFailure;
import com.ballastlane.pokemon.web.dto.SyncRunReport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records full synchronization runs. Each method is one short transaction.
 */
@Component
public class SyncRunStore {

	private final SyncRunRepository repository;

	public SyncRunStore(SyncRunRepository repository) {
		this.repository = repository;
	}

	/**
	 * Records a new running run. Not transactional on purpose: when the "one running run" index rejects the
	 * insert, the running run is looked up in a fresh transaction.
	 *
	 * @throws SyncAlreadyRunningException if another run is still running
	 */
	public SyncRunReport start(SyncTrigger trigger) {
		try {
			return toReport(repository.saveAndFlush(new SyncRun(trigger, Instant.now())));
		}
		catch (DataIntegrityViolationException ex) {
			Long runningId = repository.findFirstByStatus(SyncRunStatus.RUNNING).map(SyncRun::getId).orElse(null);
			throw new SyncAlreadyRunningException(runningId);
		}
	}

	@Transactional
	public void recordProgress(long runId, SyncProgress progress, List<SyncFailure> newFailures) {
		update(runId, progress, newFailures);
	}

	@Transactional
	public void finish(long runId, SyncRunStatus status, SyncProgress progress, List<SyncFailure> newFailures) {
		update(runId, progress, newFailures).finish(status, Instant.now());
	}

	@Transactional(readOnly = true)
	public Optional<SyncRunReport> find(long runId) {
		return repository.findById(runId).map(SyncRunStore::toReport);
	}

	@Transactional(readOnly = true)
	public Optional<SyncRunReport> findLatest() {
		return repository.findTopByOrderByIdDesc().map(SyncRunStore::toReport);
	}

	/**
	 * Marks runs left running by a previous application instance as interrupted.
	 *
	 * @return the number of runs marked
	 */
	@Transactional
	public int interruptRunning() {
		return repository.finishAll(SyncRunStatus.RUNNING, SyncRunStatus.INTERRUPTED, Instant.now());
	}

	private SyncRun update(long runId, SyncProgress progress, List<SyncFailure> newFailures) {
		SyncRun run = repository.findById(runId)
				.orElseThrow(() -> new IllegalStateException("Synchronization run %d no longer exists".formatted(runId)));
		run.recordProgress(progress.catalogueCount(), progress.created(), progress.updated(), progress.failed());
		newFailures.forEach(failure -> run.addFailure(failure.name(), failure.reason()));
		return run;
	}

	private static SyncRunReport toReport(SyncRun run) {
		List<SyncFailure> failures = run.getFailures().stream()
				.map(failure -> new SyncFailure(failure.getPokemonName(), failure.getReason()))
				.toList();
		return new SyncRunReport(run.getId(), run.getTrigger(), run.getStatus(), run.getStartedAt(),
				run.getFinishedAt(), run.getCatalogueCount(), run.getCreatedCount(), run.getUpdatedCount(),
				run.getFailedCount(), failures);
	}

}
