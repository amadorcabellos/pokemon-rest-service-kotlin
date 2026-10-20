package com.ballastlane.pokemon.service

import com.ballastlane.pokemon.client.PokeApiClient
import com.ballastlane.pokemon.client.dto.PokeApiPokemonList
import com.ballastlane.pokemon.config.CacheConfig
import com.ballastlane.pokemon.exception.PokeApiException
import com.ballastlane.pokemon.repository.PokemonRepository
import com.ballastlane.pokemon.web.dto.PokemonSummary
import com.ballastlane.pokemon.web.dto.PokemonSummaryPage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service

/**
 * Pages through the full PokeAPI catalogue.
 *
 * Each page costs one catalogue call plus one detail call per entry that is not already cached. Entries are loaded
 * in parallel; if any entry fails the whole page fails (and, since exceptions are never cached, is not cached).
 *
 * Name searches match against the pokemon stored in PostgreSQL (filled by the sync and by lookups), so they only
 * find what has been stored locally. Search pages are not cached; their entries are.
 */
@Service
class PokemonCatalogService(
    private val pokeApiClient: PokeApiClient,
    private val summaryLoader: PokemonSummaryLoader,
    private val pokemonRepository: PokemonRepository,
    @Qualifier("applicationTaskExecutor") private val executor: Executor,
) {

    @Cacheable(cacheNames = [CacheConfig.POKEMON_PAGE_CACHE], key = "#page + ':' + #size", sync = true)
    fun getPage(page: Int, size: Int): PokemonSummaryPage {
        val offset = page.toLong() * size
        if (offset > Int.MAX_VALUE) {
            // Far past the end of the catalogue: only the total count is needed.
            return toPage(emptyList(), page, size, pokeApiClient.fetchPokemonPage(0, 1).count.toLong())
        }

        val listing = pokeApiClient.fetchPokemonPage(offset.toInt(), size)
        return toPage(loadAll(listing.results), page, size, listing.count.toLong())
    }

    /**
     * Pages through the stored pokemon whose name contains `name` (case-insensitive), ordered by PokeAPI id.
     */
    fun searchByName(name: String, page: Int, size: Int): PokemonSummaryPage {
        val ids = pokemonRepository.findPokeApiIdsByNameLike("%${escapeLike(name)}%", PageRequest.of(page, size))
        return toPage(loadAllById(ids.content), page, size, ids.totalElements)
    }

    private fun loadAll(entries: List<PokeApiPokemonList.Entry>): List<PokemonSummary> =
        loadAllById(entries.map { it.id })

    private fun loadAllById(ids: List<Int>): List<PokemonSummary> {
        val futures = ids.map { id -> CompletableFuture.supplyAsync({ summaryLoader.load(id) }, executor) }
        try {
            CompletableFuture.allOf(*futures.toTypedArray()).join()
        } catch (ex: CompletionException) {
            throw unwrap(ex)
        }
        return futures.map { it.join() }
    }

    private companion object {

        /**
         * Makes `%`, `_` and `\` match literally (backslash is PostgreSQL's default LIKE escape).
         */
        fun escapeLike(value: String): String = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

        /**
         * Rethrows the original failure so the web layer maps it as usual (502, 503 or 429).
         */
        fun unwrap(ex: CompletionException): RuntimeException =
            ex.cause as? RuntimeException ?: PokeApiException("Failed to load pokemon catalogue page", ex.cause)

        fun toPage(content: List<PokemonSummary>, page: Int, size: Int, total: Long): PokemonSummaryPage {
            val totalPages = ((total + size - 1) / size).toInt()
            return PokemonSummaryPage(content, page, size, total, totalPages)
        }
    }
}
