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
import jakarta.persistence.Table
import java.time.Instant
import java.util.Locale

/**
 * Proprietary data: an internal classification tag. Tags are free text, stored in lowercase and unique per pokemon.
 */
@Entity
@Table(name = "pokemon_tag")
class PokemonTag(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pokemon_id", nullable = false, updatable = false)
    val pokemon: Pokemon,

    tag: String,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(nullable = false, updatable = false)
    val tag: String = tag.trim().lowercase(Locale.ROOT)

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        protected set

    @PrePersist
    internal fun onCreate() {
        createdAt = Instant.now()
    }
}
