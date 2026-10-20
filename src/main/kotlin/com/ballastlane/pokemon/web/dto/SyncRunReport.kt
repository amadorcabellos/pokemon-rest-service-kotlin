package com.ballastlane.pokemon.web.dto

import com.ballastlane.pokemon.domain.SyncRunStatus
import com.ballastlane.pokemon.domain.SyncTrigger
import java.time.Instant

/**
 * Status and counts of a full synchronization run. `finishedAt` is null while the run is in progress.
 */
data class SyncRunReport(
    val id: Long?,
    val trigger: SyncTrigger,
    val status: SyncRunStatus,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val catalogueCount: Int,
    val created: Int,
    val updated: Int,
    val failed: Int,
    val failures: List<SyncFailure>,
)
