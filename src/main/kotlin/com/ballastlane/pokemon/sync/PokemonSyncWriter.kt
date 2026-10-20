package com.ballastlane.pokemon.sync

import com.ballastlane.pokemon.client.dto.PokeApiPokemon
import com.ballastlane.pokemon.mapper.PokemonMapper
import com.ballastlane.pokemon.repository.PokemonRepository
import com.ballastlane.pokemon.web.dto.SyncedPokemon
import java.time.Instant
import kotlin.jvm.optionals.getOrNull
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * Writes the upstream data of one pokemon into PostgreSQL, in its own transaction.
 *
 * Pokemon are matched by PokeAPI id, so a renamed pokemon updates its existing row. Only upstream fields are
 * written; proprietary data is never touched.
 */
@Component
class PokemonSyncWriter(
    private val pokemonRepository: PokemonRepository,
    private val pokemonMapper: PokemonMapper,
    private val transactionTemplate: TransactionTemplate,
) {

    fun upsert(source: PokeApiPokemon): SyncedPokemon = try {
        upsertInTransaction(source)
    } catch (ex: DataIntegrityViolationException) {
        // A concurrent lookup or sync inserted the same pokemon first; the retry takes the update path.
        log.debug("Pokemon {} was stored concurrently, retrying as an update", source.id)
        upsertInTransaction(source)
    }

    private fun upsertInTransaction(source: PokeApiPokemon): SyncedPokemon = checkNotNull(
        transactionTemplate.execute {
            val upstream = pokemonMapper.toEntity(source)
            val existing = pokemonRepository.findByPokeApiId(source.id).getOrNull()
            val pokemon = existing ?: upstream
            existing?.copyUpstreamFrom(upstream)

            val now = Instant.now()
            pokemon.markSynced(now)
            // Flush now so constraint violations surface here, inside the retry above.
            pokemonRepository.saveAndFlush(pokemon)

            val outcome = if (existing != null) SyncOutcome.UPDATED else SyncOutcome.CREATED
            SyncedPokemon(pokemon.pokeApiId, pokemon.name, outcome, now)
        },
    )

    private companion object {
        private val log = LoggerFactory.getLogger(PokemonSyncWriter::class.java)
    }
}
