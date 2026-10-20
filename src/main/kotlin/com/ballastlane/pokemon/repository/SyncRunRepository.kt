package com.ballastlane.pokemon.repository

import com.ballastlane.pokemon.domain.SyncRun
import com.ballastlane.pokemon.domain.SyncRunStatus
import java.time.Instant
import java.util.Optional
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SyncRunRepository : JpaRepository<SyncRun, Long> {

    fun findFirstByStatus(status: SyncRunStatus): Optional<SyncRun>

    fun findTopByOrderByIdDesc(): Optional<SyncRun>

    /**
     * Moves every run in status `from` to status `to`, finishing it at `finishedAt`.
     */
    @Modifying
    @Query("update SyncRun r set r.status = :to, r.finishedAt = :finishedAt where r.status = :from")
    fun finishAll(
        @Param("from") from: SyncRunStatus,
        @Param("to") to: SyncRunStatus,
        @Param("finishedAt") finishedAt: Instant,
    ): Int
}
