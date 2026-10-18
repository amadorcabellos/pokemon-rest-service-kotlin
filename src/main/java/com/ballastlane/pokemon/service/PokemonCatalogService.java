package com.ballastlane.pokemon.service;

import com.ballastlane.pokemon.client.PokeApiClient;
import com.ballastlane.pokemon.client.dto.PokeApiPokemonList;
import com.ballastlane.pokemon.config.CacheConfig;
import com.ballastlane.pokemon.exception.PokeApiException;
import com.ballastlane.pokemon.web.dto.PokemonSummary;
import com.ballastlane.pokemon.web.dto.PokemonSummaryPage;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Pages through the full PokeAPI catalogue.
 * <p>
 * Each page costs one catalogue call plus one detail call per entry that is not already cached. Entries are
 * loaded in parallel; if any entry fails the whole page fails (and, since exceptions are never cached, is
 * not cached).
 */
@Service
public class PokemonCatalogService {

	private final PokeApiClient pokeApiClient;

	private final PokemonSummaryLoader summaryLoader;

	private final Executor executor;

	public PokemonCatalogService(PokeApiClient pokeApiClient, PokemonSummaryLoader summaryLoader,
			@Qualifier("applicationTaskExecutor") Executor executor) {
		this.pokeApiClient = pokeApiClient;
		this.summaryLoader = summaryLoader;
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

	private List<PokemonSummary> loadAll(List<PokeApiPokemonList.Entry> entries) {
		List<CompletableFuture<PokemonSummary>> futures = entries.stream()
				.map(entry -> CompletableFuture.supplyAsync(() -> summaryLoader.load(entry.id()), executor))
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
