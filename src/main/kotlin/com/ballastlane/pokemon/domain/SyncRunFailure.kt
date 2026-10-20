package com.ballastlane.pokemon.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

/**
 * A pokemon that a full synchronization run could not synchronize, and why.
 */
@Entity
@Table(name = "sync_run_failure")
class SyncRunFailure internal constructor(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sync_run_id", nullable = false, updatable = false)
    private val syncRun: SyncRun,

    @Column(name = "pokemon_name", nullable = false)
    val pokemonName: String,

    reason: String,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(nullable = false)
    val reason: String = reason.take(MAX_REASON_LENGTH)

    internal companion object {
        const val MAX_REASON_LENGTH = 500
    }
}
