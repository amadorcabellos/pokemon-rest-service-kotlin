package com.ballastlane.pokemon.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
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
 * Proprietary data: the name of a pokemon in one locale (e.g. `es-ES`). One name per locale.
 */
@Entity
@Table(name = "pokemon_localized_name")
class PokemonLocalizedName(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pokemon_id", nullable = false, updatable = false)
    val pokemon: Pokemon,

    @Column(nullable = false)
    val locale: String,

    name: String,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(nullable = false)
    var name: String = name
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

    fun rename(name: String) {
        this.name = name
    }
}
