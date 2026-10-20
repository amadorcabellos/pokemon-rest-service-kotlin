package com.ballastlane.pokemon.repository

import com.ballastlane.pokemon.domain.Pokemon
import java.time.Instant
import java.util.Optional
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface PokemonRepository : JpaRepository<Pokemon, Long> {

    fun findByNameIgnoreCase(name: String): Optional<Pokemon>

    fun findByPokeApiId(pokeApiId: Int): Optional<Pokemon>

    /**
     * PokeAPI ids of the stored pokemon whose name matches `pattern` case-insensitively, ordered by id.
     * `pattern` is an ILIKE pattern with `\` as the escape character.
     */
    @Query(
        value = "SELECT poke_api_id FROM pokemon WHERE name ILIKE :pattern ORDER BY poke_api_id",
        countQuery = "SELECT count(*) FROM pokemon WHERE name ILIKE :pattern",
        nativeQuery = true,
    )
    fun findPokeApiIdsByNameLike(pattern: String, pageable: Pageable): Page<Int>

    @Query(
        """
        select new com.ballastlane.pokemon.repository.LocalDataHeader(p.id, p.localDataVersion, p.localDataModifiedAt)
        from Pokemon p where p.pokeApiId = :pokeApiId""",
    )
    fun findLocalDataHeaderByPokeApiId(pokeApiId: Int): Optional<LocalDataHeader>

    @Query(
        """
        select new com.ballastlane.pokemon.repository.LocalDataHeader(p.id, p.localDataVersion, p.localDataModifiedAt)
        from Pokemon p where lower(p.name) = lower(:name)""",
    )
    fun findLocalDataHeaderByName(name: String): Optional<LocalDataHeader>

    @Query(
        """
        select new com.ballastlane.pokemon.repository.LocalDataHeader(p.id, p.localDataVersion, p.localDataModifiedAt)
        from Pokemon p where p.id = :id""",
    )
    fun findLocalDataHeaderById(id: Long): Optional<LocalDataHeader>

    /**
     * Locks the pokemon row until the end of the transaction, so updates of one pokemon's proprietary data run one
     * after the other. `NO KEY UPDATE` still lets other transactions insert rows that reference it.
     *
     * @return the current version of its proprietary data
     */
    @Query(value = "SELECT local_data_version FROM pokemon WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
    fun lockLocalDataVersion(id: Long): Long

    /**
     * The only writer of the version columns, which are read only on [Pokemon]. Leaves `updated_at` alone: that
     * one tracks upstream changes.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        value = """
        UPDATE pokemon SET local_data_version = local_data_version + 1, local_data_modified_at = :modifiedAt
        WHERE id = :id""",
        nativeQuery = true,
    )
    fun bumpLocalDataVersion(id: Long, modifiedAt: Instant): Int
}
