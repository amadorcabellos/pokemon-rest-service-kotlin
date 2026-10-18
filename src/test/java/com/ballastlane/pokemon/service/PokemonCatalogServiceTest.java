package com.ballastlane.pokemon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyInt;

import com.ballastlane.pokemon.client.PokeApiClient;
import com.ballastlane.pokemon.client.dto.PokeApiPokemonList;
import com.ballastlane.pokemon.client.dto.PokeApiPokemonList.Entry;
import com.ballastlane.pokemon.exception.PokeApiException;
import com.ballastlane.pokemon.web.dto.PokemonSummary;
import com.ballastlane.pokemon.web.dto.PokemonSummaryPage;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PokemonCatalogServiceTest {

	@Mock
	private PokeApiClient pokeApiClient;

	@Mock
	private PokemonSummaryLoader summaryLoader;

	private PokemonCatalogService service;

	@BeforeEach
	void setUp() {
		service = new PokemonCatalogService(pokeApiClient, summaryLoader, Executors.newVirtualThreadPerTaskExecutor());
	}

	@Test
	void buildsPageFromListingAndLoadedEntries() {
		given(pokeApiClient.fetchPokemonPage(0, 2)).willReturn(listing(1302, 1, 2));
		given(summaryLoader.load(1)).willReturn(summary(1, "bulbasaur"));
		given(summaryLoader.load(2)).willReturn(summary(2, "ivysaur"));

		PokemonSummaryPage page = service.getPage(0, 2);

		assertThat(page.content()).extracting(PokemonSummary::name).containsExactly("bulbasaur", "ivysaur");
		assertThat(page.page()).isZero();
		assertThat(page.size()).isEqualTo(2);
		assertThat(page.totalElements()).isEqualTo(1302);
		assertThat(page.totalPages()).isEqualTo(651);
	}

	@Test
	void usesOffsetFromPageAndSizeAndHandlesPartialLastPage() {
		given(pokeApiClient.fetchPokemonPage(20, 10)).willReturn(listing(21, 21));
		given(summaryLoader.load(21)).willReturn(summary(21, "spearow"));

		PokemonSummaryPage page = service.getPage(2, 10);

		assertThat(page.content()).hasSize(1);
		assertThat(page.totalPages()).isEqualTo(3);
	}

	@Test
	void returnsEmptyPagePastTheEnd() {
		given(pokeApiClient.fetchPokemonPage(2000, 20)).willReturn(listing(1302));

		PokemonSummaryPage page = service.getPage(100, 20);

		assertThat(page.content()).isEmpty();
		assertThat(page.totalElements()).isEqualTo(1302);
		assertThat(page.totalPages()).isEqualTo(66);
		verify(summaryLoader, never()).load(anyInt());
	}

	@Test
	void onlyFetchesCountWhenOffsetOverflows() {
		given(pokeApiClient.fetchPokemonPage(0, 1)).willReturn(listing(1302, 1));

		PokemonSummaryPage page = service.getPage(Integer.MAX_VALUE, 50);

		assertThat(page.content()).isEmpty();
		assertThat(page.totalElements()).isEqualTo(1302);
		verify(summaryLoader, never()).load(anyInt());
	}

	@Test
	void failsWholePageWithOriginalExceptionWhenAnEntryFails() {
		given(pokeApiClient.fetchPokemonPage(0, 2)).willReturn(listing(1302, 1, 2));
		given(summaryLoader.load(1)).willReturn(summary(1, "bulbasaur"));
		given(summaryLoader.load(2)).willThrow(new PokeApiException("boom", new RuntimeException()));

		assertThatThrownBy(() -> service.getPage(0, 2)).isInstanceOf(PokeApiException.class).hasMessage("boom");
	}

	@Test
	void propagatesOpenCircuitFromEntryLoading() {
		given(pokeApiClient.fetchPokemonPage(0, 1)).willReturn(listing(1302, 1));
		given(summaryLoader.load(1)).willThrow(
				CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("pokeapi")));

		assertThatThrownBy(() -> service.getPage(0, 1)).isInstanceOf(CallNotPermittedException.class);
	}

	private static PokeApiPokemonList listing(int count, int... ids) {
		List<Entry> entries = java.util.Arrays.stream(ids)
				.mapToObj(id -> new Entry("pokemon-" + id, "https://pokeapi.co/api/v2/pokemon/" + id + "/"))
				.toList();
		return new PokeApiPokemonList(count, entries);
	}

	private static PokemonSummary summary(int id, String name) {
		return new PokemonSummary(id, name, null, List.of("normal"), BigDecimal.ONE, List.of());
	}

}
