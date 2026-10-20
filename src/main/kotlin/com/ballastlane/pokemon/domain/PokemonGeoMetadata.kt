package com.ballastlane.pokemon.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant

/**
 * Proprietary data: a geographical fact about a pokemon (a region, area or habitat), with an optional note.
 */
@Entity
@Table(name = "pokemon_geo_metadata")
class PokemonGeoMetadata(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pokemon_id", nullable = false, updatable = false)
    val pokemon: Pokemon,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val kind: GeoKind,

    @Column(nullable = false)
    val value: String,

    val note: String?,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
        protected set

    @PrePersist
    internal fun onCreate() {
        createdAt = Instant.now()
        updatedAt = createdAt
    }

    @PreUpdate
    internal fun onUpdate() {
        updatedAt = Instant.now()
    }
}
