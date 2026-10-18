package com.ballastlane.pokemon.service;

import com.ballastlane.pokemon.client.PokeApiClient;
import com.ballastlane.pokemon.client.dto.PokeApiPokemonList;
import com.ballastlane.pokemon.config.CacheConfig;
import com.ballastlane.pokemon.exception.PokeApiException;
import com.ballastlane.pokemon.repository.PokemonRepository;
import com.ballastlane.pokemon.web.dto.PokemonSummary;
import com.ballastlane.pokemon.web.dto.PokemonSummaryPage;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Pages through the full PokeAPI catalogue.
 * <p>
 * Each page costs one catalogue call plus one detail call per entry that is not already cached. Entries are
 * loaded in parallel; if any entry fails the whole page fails (and, since exceptions are never cached, is
 * not cached).
 * <p>
 * Name searches match against the pokemon stored in PostgreSQL (filled by the sync and by lookups), so they only
 * find what has been stored locally. Search pages are not cached; their entries are.
 */
@Service
public class PokemonCatalogService {

	private final PokeApiClient pokeApiClient;

	private final PokemonSummaryLoader summaryLoader;

	private final PokemonRepository pokemonRepository;

	private final Executor executor;

	public PokemonCatalogService(PokeApiClient pokeApiClient, PokemonSummaryLoader summaryLoader,
			PokemonRepository pokemonRepository, @Qualifier("applicationTaskExecutor") Executor executor) {
		this.pokeApiClient = pokeApiClient;
		this.summaryLoader = summaryLoader;
		this.pokemonRepository = pokemonRepository;
		this.executor = executor;
	}

	@Cacheable(cacheNames = CacheConfig.POKEMON_PAGE_CACHE, key = "#page + ':' + #size", sync = true)
	public PokemonSummaryPage getPage(int page, int size) {
		long offset = (long) page * size;
		if (offset > Integer.MAX_VALUE) {
			// Far past the end of the catalogue: only the total count is needed.
			return toPage(List.of(), page, size, pokeApiClient.fetchPokemonPage(0, 1).count());
		}

		PokeApiPokemonList listing = pokeApiClient.fetchPokemonPage((int) offset, size);
		return toPage(loadAll(listing.results()), page, size, listing.count());
	}

	/**
	 * Pages through the stored pokemon whose name contains {@code name} (case-insensitive), ordered by PokeAPI id.
	 */
	public PokemonSummaryPage searchByName(String name, int page, int size) {
		Page<Integer> ids = pokemonRepository.findPokeApiIdsByNameLike("%" + escapeLike(name) + "%",
				PageRequest.of(page, size));
		return toPage(loadAllById(ids.getContent()), page, size, ids.getTotalElements());
	}

	/**
	 * Makes {@code %}, {@code _} and {@code \} match literally (backslash is PostgreSQL's default LIKE escape).
	 */
	private static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private List<PokemonSummary> loadAll(List<PokeApiPokemonList.Entry> entries) {
		return loadAllById(entries.stream().map(PokeApiPokemonList.Entry::id).toList());
	}

	private List<PokemonSummary> loadAllById(List<Integer> ids) {
		List<CompletableFuture<PokemonSummary>> futures = ids.stream()
				.map(id -> CompletableFuture.supplyAsync(() -> summaryLoader.load(id), executor))
				.toList();
		try {
			CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
		}
		catch (CompletionException ex) {
			throw unwrap(ex);
		}
		return futures.stream().map(CompletableFuture::join).toList();
	}

	/**
	 * Rethrows the original failure so the web layer maps it as usual (502, 503 or 429).
	 */
	private static RuntimeException unwrap(CompletionException ex) {
		if (ex.getCause() instanceof RuntimeException cause) {
			return cause;
		}
		return new PokeApiException("Failed to load pokemon catalogue page", ex.getCause());
	}

	private static PokemonSummaryPage toPage(List<PokemonSummary> content, int page, int size, long total) {
		int totalPages = (int) ((total + size - 1) / size);
		return new PokemonSummaryPage(content, page, size, total, totalPages);
	}

}
