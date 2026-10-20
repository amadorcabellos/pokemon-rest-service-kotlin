package com.ballastlane.pokemon.domain

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.time.Instant

/**
 * One full synchronization of the PokeAPI catalogue: its status and counts.
 *
 * The database allows at most one run in [SyncRunStatus.RUNNING] at a time (partial unique index).
 */
@Entity
@Table(name = "sync_run")
class SyncRun(
    @Enumerated(EnumType.STRING)
    @Column(name = "triggered_by", nullable = false, updatable = false)
    val trigger: SyncTrigger,

    @Column(name = "started_at", nullable = false, updatable = false)
    val startedAt: Instant,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: SyncRunStatus = SyncRunStatus.RUNNING
        protected set

    @Column(name = "finished_at")
    var finishedAt: Instant? = null
        protected set

    @Column(name = "catalogue_count", nullable = false)
    var catalogueCount: Int = 0
        protected set

    @Column(name = "created_count", nullable = false)
    var createdCount: Int = 0
        protected set

    @Column(name = "updated_count", nullable = false)
    var updatedCount: Int = 0
        protected set

    @Column(name = "failed_count", nullable = false)
    var failedCount: Int = 0
        protected set

    @OneToMany(mappedBy = "syncRun", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("id")
    private val _failures: MutableList<SyncRunFailure> = mutableListOf()

    val failures: List<SyncRunFailure>
        get() = _failures.toList()

    fun recordProgress(catalogueCount: Int, createdCount: Int, updatedCount: Int, failedCount: Int) {
        this.catalogueCount = catalogueCount
        this.createdCount = createdCount
        this.updatedCount = updatedCount
        this.failedCount = failedCount
    }

    fun addFailure(pokemonName: String, reason: String) {
        _failures.add(SyncRunFailure(this, pokemonName, reason))
    }

    fun finish(status: SyncRunStatus, finishedAt: Instant) {
        this.status = status
        this.finishedAt = finishedAt
    }
}
