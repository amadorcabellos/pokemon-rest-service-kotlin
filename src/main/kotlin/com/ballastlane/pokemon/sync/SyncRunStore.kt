package com.ballastlane.pokemon.sync

import com.ballastlane.pokemon.domain.SyncRun
import com.ballastlane.pokemon.domain.SyncRunStatus
import com.ballastlane.pokemon.domain.SyncTrigger
import com.ballastlane.pokemon.exception.SyncAlreadyRunningException
import com.ballastlane.pokemon.repository.SyncRunRepository
import com.ballastlane.pokemon.web.dto.SyncFailure
import com.ballastlane.pokemon.web.dto.SyncRunReport
import java.time.Instant
import kotlin.jvm.optionals.getOrNull
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Records full synchronization runs. Each method is one short transaction.
 */
@Component
class SyncRunStore(private val repository: SyncRunRepository) {

    /**
     * Records a new running run. Not transactional on purpose: when the "one running run" index rejects the insert,
     * the running run is looked up in a fresh transaction.
     *
     * @throws SyncAlreadyRunningException if another run is still running
     */
    fun start(trigger: SyncTrigger): SyncRunReport = try {
        toReport(repository.saveAndFlush(SyncRun(trigger, Instant.now())))
    } catch (ex: DataIntegrityViolationException) {
        throw SyncAlreadyRunningException(repository.findFirstByStatus(SyncRunStatus.RUNNING).getOrNull()?.id)
    }

    @Transactional
    fun recordProgress(runId: Long, progress: SyncProgress, newFailures: List<SyncFailure>) {
        update(runId, progress, newFailures)
    }

    @Transactional
    fun finish(runId: Long, status: SyncRunStatus, progress: SyncProgress, newFailures: List<SyncFailure>) {
        update(runId, progress, newFailures).finish(status, Instant.now())
    }

    @Transactional(readOnly = true)
    fun find(runId: Long): SyncRunReport? = repository.findById(runId).getOrNull()?.let(::toReport)

    @Transactional(readOnly = true)
    fun findLatest(): SyncRunReport? = repository.findTopByOrderByIdDesc().getOrNull()?.let(::toReport)

    /**
     * Marks runs left running by a previous application instance as interrupted.
     *
     * @return the number of runs marked
     */
    @Transactional
    fun interruptRunning(): Int = repository.finishAll(SyncRunStatus.RUNNING, SyncRunStatus.INTERRUPTED, Instant.now())

    private fun update(runId: Long, progress: SyncProgress, newFailures: List<SyncFailure>): SyncRun {
        val run = repository.findById(runId).getOrNull()
            ?: throw IllegalStateException("Synchronization run $runId no longer exists")
        run.recordProgress(progress.catalogueCount, progress.created, progress.updated, progress.failed)
        newFailures.forEach { run.addFailure(it.name, it.reason) }
        return run
    }

    private companion object {

        fun toReport(run: SyncRun): SyncRunReport = SyncRunReport(
            run.id, run.trigger, run.status, run.startedAt, run.finishedAt, run.catalogueCount, run.createdCount,
            run.updatedCount, run.failedCount, run.failures.map { SyncFailure(it.pokemonName, it.reason) },
        )
    }
}
