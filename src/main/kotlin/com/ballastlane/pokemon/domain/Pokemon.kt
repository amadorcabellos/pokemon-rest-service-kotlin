package com.ballastlane.pokemon.domain

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import java.time.Instant

/**
 * Local copy of a PokeAPI pokemon. Every field here is upstream data, owned by PokeAPI and refreshed by the sync.
 *
 * Proprietary data ([PokemonLocalizedName], [PokemonGeoMetadata], [PokemonTag]) lives in its own tables and
 * points at this entity. It is deliberately not mapped here, so saving a pokemon can never cascade into it.
 */
@Entity
@Table(name = "pokemon")
class Pokemon(
    pokeApiId: Int,
    name: String,
    height: Int?,
    weight: Int?,
    baseExperience: Int?,
    spriteUrl: String?,
    types: List<String>,
    stats: List<PokemonStat> = emptyList(),
    abilities: List<PokemonAbility> = emptyList(),
    speciesId: Int? = null,
    speciesName: String? = null,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(name = "poke_api_id", nullable = false, unique = true)
    var pokeApiId: Int = pokeApiId
        protected set

    @Column(nullable = false, unique = true)
    var name: String = name
        protected set

    var height: Int? = height
        protected set

    var weight: Int? = weight
        protected set

    @Column(name = "base_experience")
    var baseExperience: Int? = baseExperience
        protected set

    @Column(name = "sprite_url")
    var spriteUrl: String? = spriteUrl
        protected set

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "pokemon_type", joinColumns = [JoinColumn(name = "pokemon_id")])
    @OrderColumn(name = "slot")
    @Column(name = "type_name", nullable = false)
    private val _types: MutableList<String> = types.toMutableList()

    val types: List<String>
        get() = _types.toList()

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "pokemon_stat", joinColumns = [JoinColumn(name = "pokemon_id")])
    @OrderColumn(name = "slot")
    private val _stats: MutableList<PokemonStat> = stats.toMutableList()

    val stats: List<PokemonStat>
        get() = _stats.toList()

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "pokemon_ability", joinColumns = [JoinColumn(name = "pokemon_id")])
    @OrderColumn(name = "slot")
    private val _abilities: MutableList<PokemonAbility> = abilities.toMutableList()

    val abilities: List<PokemonAbility>
        get() = _abilities.toList()

    @Column(name = "species_id")
    var speciesId: Int? = speciesId
        protected set

    @Column(name = "species_name")
    var speciesName: String? = speciesName
        protected set

    /**
     * When the sync last refreshed this row; `null` if it was only recorded by a lookup.
     */
    @Column(name = "last_synced_at")
    var lastSyncedAt: Instant? = null
        protected set

    /*
     * Version of the proprietary data, written only by PokemonRepository#bumpLocalDataVersion. Read only here on
     * purpose: the sync saves whole Pokemon entities, and an updatable mapping would let it write a stale version
     * back over a newer one.
     */
    @Column(name = "local_data_version", insertable = false, updatable = false)
    var localDataVersion: Long = 0
        protected set

    @Column(name = "local_data_modified_at", insertable = false, updatable = false)
    var localDataModifiedAt: Instant? = null
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

    /**
     * Overwrites the upstream fields with those of `source`. Identity (`id`, `pokeApiId`) and audit timestamps
     * are kept.
     */
    fun copyUpstreamFrom(source: Pokemon) {
        name = source.name
        height = source.height
        weight = source.weight
        baseExperience = source.baseExperience
        spriteUrl = source.spriteUrl
        _types.replaceWith(source.types)
        _stats.replaceWith(source.stats)
        _abilities.replaceWith(source.abilities)
        speciesId = source.speciesId
        speciesName = source.speciesName
    }

    fun markSynced(syncedAt: Instant) {
        lastSyncedAt = syncedAt
    }

    // Element collections are updated in place so Hibernate keeps tracking the same collection instance.
    private fun <T> MutableList<T>.replaceWith(source: List<T>) {
        clear()
        addAll(source)
    }
}
