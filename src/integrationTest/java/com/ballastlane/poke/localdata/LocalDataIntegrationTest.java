package com.ballastlane.poke.localdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ballastlane.poke.domain.Role;
import com.ballastlane.poke.domain.SyncRunStatus;
import com.ballastlane.poke.domain.SyncTrigger;
import com.ballastlane.poke.repository.LocalDataHeader;
import com.ballastlane.poke.repository.PokemonGeoMetadataRepository;
import com.ballastlane.poke.repository.PokemonLocalizedNameRepository;
import com.ballastlane.poke.repository.PokemonTagRepository;
import com.ballastlane.poke.support.AbstractApiIntegrationTest;
import com.ballastlane.poke.sync.PokemonSyncService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Local data updates against PostgreSQL, Redis and the WireMock-backed PokeAPI. Pikachu is stored locally by
 * looking it up first, as a client would.
 */
class LocalDataIntegrationTest extends AbstractApiIntegrationTest {

	private static final MediaType MERGE_PATCH = MediaType.parseMediaType("application/merge-patch+json");

	private static final String PIKACHU_DATA = """
			{
			  "localizedNames": [ { "locale": "ja-jp", "name": "ピカチュウ" }, { "locale": "es", "name": "Pikachu" } ],
			  "geoMetadata": [ { "kind": "Region", "value": "kanto", "note": "Viridian Forest" } ],
			  "tags": [ "Mascot", "starter" ]
			}""";

	@Autowired
	private PokemonSyncService syncService;

	@Autowired
	private PokemonLocalizedNameRepository localizedNameRepository;

	@Autowired
	private PokemonGeoMetadataRepository geoMetadataRepository;

	@Autowired
	private PokemonTagRepository tagRepository;

	/** Every request in this class is made by an administrator (ADMIN implies USER). */
	private String adminToken;

	@BeforeEach
	void signIn() {
		adminToken = bearerTokenFor("test-admin", Role.USER, Role.ADMIN);
	}

	@Test
	void anUpdateShowsInTheDetailedViewEvenWhenItIsCached() throws Exception {
		lookUp("pikachu");

		mockMvc.perform(put("/api/v1/pokemon/pikachu").contentType(MediaType.APPLICATION_JSON).content(PIKACHU_DATA)
				.header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.localizedNames[0].locale").value("es"))
				.andExpect(jsonPath("$.localizedNames[1].locale").value("ja-JP"))
				.andExpect(jsonPath("$.localizedNames[1].name").value("ピカチュウ"))
				.andExpect(jsonPath("$.geoMetadata[0].kind").value("region"))
				.andExpect(jsonPath("$.tags[0]").value("mascot"))
				.andExpect(jsonPath("$.modifiedAt").exists())
				.andExpect(jsonPath("$.version").value(1));

		mockMvc.perform(get("/api/v1/pokemon/PIKACHU").header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.name").value("pikachu"))
				.andExpect(jsonPath("$.totalStats").value(320))
				.andExpect(jsonPath("$.localData.localizedNames.length()").value(2))
				.andExpect(jsonPath("$.localData.geoMetadata[0].note").value("Viridian Forest"))
				.andExpect(jsonPath("$.localData.tags[1]").value("starter"))
				.andExpect(jsonPath("$.localData.version").value(1));

		// The PokeAPI part still came from the cache.
		assertThat(pokeApi.countRequests("/api/v2/pokemon/pikachu")).isEqualTo(1);
		Long id = repository.findByPokeApiId(25).orElseThrow().getId();
		assertThat(localizedNameRepository.findByPokemonId(id)).hasSize(2);
		assertThat(tagRepository.findByPokemonId(id)).hasSize(2);
	}

	@Test
	void aPartialUpdateOnlyChangesWhatItSends() throws Exception {
		lookUp("pikachu");
		mockMvc.perform(put("/api/v1/pokemon/25").contentType(MediaType.APPLICATION_JSON).content(PIKACHU_DATA)
				.header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isOk());

		mockMvc.perform(patch("/api/v1/pokemon/pikachu").contentType(MERGE_PATCH).content("{ \"tags\": [ \"electric\" ] }")
				.header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tags.length()").value(1))
				.andExpect(jsonPath("$.tags[0]").value("electric"))
				.andExpect(jsonPath("$.localizedNames.length()").value(2))
				.andExpect(jsonPath("$.geoMetadata.length()").value(1))
				.andExpect(jsonPath("$.version").value(2));

		mockMvc.perform(patch("/api/v1/pokemon/pikachu").contentType(MERGE_PATCH)
						.content("{ \"tags\": null, \"geoMetadata\": null }")
								.header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tags").isEmpty())
				.andExpect(jsonPath("$.geoMetadata").isEmpty())
				.andExpect(jsonPath("$.localizedNames.length()").value(2))
				.andExpect(jsonPath("$.version").value(3));

		// Sending the same data again changes nothing, so the version stays.
		mockMvc.perform(patch("/api/v1/pokemon/pikachu").contentType(MERGE_PATCH).content("{ \"tags\": [] }")
				.header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"3\""));
	}

	@Test
	void storesRepeatedGeoEntries() throws Exception {
		lookUp("pikachu");

		mockMvc.perform(patch("/api/v1/pokemon/pikachu").contentType(MERGE_PATCH).content("""
						{ "geoMetadata": [ { "kind": "area", "value": "route 1" }, { "kind": "area", "value": "route 1" } ] }""")
								.header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.geoMetadata.length()").value(2));

		assertThat(geoMetadataRepository.findByPokemonId(repository.findByPokeApiId(25).orElseThrow().getId()))
				.hasSize(2);
	}

	@Test
	void invalidRequestsChangeNothing() throws Exception {
		lookUp("pikachu");
		mockMvc.perform(put("/api/v1/pokemon/pikachu").contentType(MediaType.APPLICATION_JSON).content(PIKACHU_DATA)
				.header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isOk());

		mockMvc.perform(put("/api/v1/pokemon/pikachu").contentType(MediaType.APPLICATION_JSON)
						.content("{ \"name\": \"raichu\", \"localizedNames\": [], \"geoMetadata\": [], \"tags\": [] }")
								.header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("name"))
				.andExpect(jsonPath("$.errors[0].message").value("is read only"));
		mockMvc.perform(patch("/api/v1/pokemon/pikachu").contentType(MERGE_PATCH)
						.content("{ \"tags\": [ \"ok\", \"OK\" ], \"localizedNames\": [ { \"locale\": \"??\", \"name\": \"x\" } ] }")
								.header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.length()").value(2));
		mockMvc.perform(patch("/api/v1/pokemon/pikachu").contentType(MERGE_PATCH).content("{ \"tags\": [] } []")
				.header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isBadRequest());
		mockMvc.perform(patch("/api/v1/pokemon/pikachu").contentType(MediaType.TEXT_PLAIN).content("tags")
				.header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isUnsupportedMediaType());

		mockMvc.perform(get("/api/v1/pokemon/pikachu").header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(jsonPath("$.localData.tags.length()").value(2))
				.andExpect(jsonPath("$.localData.localizedNames.length()").value(2))
				.andExpect(jsonPath("$.localData.version").value(1));
	}

	@Test
	void onlyPokemonStoredLocallyCanBeUpdated() throws Exception {
		mockMvc.perform(patch("/api/v1/pokemon/pikachu").contentType(MERGE_PATCH).content("{ \"tags\": [ \"x\" ] }")
				.header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Pokemon not found"));
		assertThat(pokeApi.countRequests("/api/v2/pokemon/pikachu")).isZero();

		// Looking it up stores it, and from then on it can be updated.
		lookUp("pikachu");
		mockMvc.perform(patch("/api/v1/pokemon/pikachu").contentType(MERGE_PATCH).content("{ \"tags\": [ \"x\" ] }")
				.header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isOk());
	}

	@Test
	void syncsKeepLocalDataAndItsVersion() throws Exception {
		runFullSync();
		mockMvc.perform(put("/api/v1/pokemon/bulbasaur").contentType(MediaType.APPLICATION_JSON).content(PIKACHU_DATA)
				.header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isOk());
		LocalDataHeader before = repository.findLocalDataHeaderByPokeApiId(1).orElseThrow();

		runFullSync();
		mockMvc.perform(post("/api/v1/admin/sync/pokemon/1")
				.header(HttpHeaders.AUTHORIZATION, adminToken)).andExpect(status().isOk());

		LocalDataHeader after = repository.findLocalDataHeaderByPokeApiId(1).orElseThrow();
		assertThat(after).isEqualTo(before);
		assertThat(after.version()).isEqualTo(1);
		assertThat(tagRepository.findByPokemonId(after.pokemonId())).hasSize(2);
		assertThat(localizedNameRepository.findByPokemonId(after.pokemonId())).hasSize(2);
		assertThat(geoMetadataRepository.findByPokemonId(after.pokemonId())).hasSize(1);
	}

	@Test
	void onlyOneOfTwoUpdatesBasedOnTheSameVersionWins() throws Exception {
		lookUp("pikachu");

		List<Integer> statuses = concurrently(2, i -> put("/api/v1/pokemon/pikachu")
				.contentType(MediaType.APPLICATION_JSON)
				.header("If-Match", "\"0\"")
				.content("{ \"localizedNames\": [], \"geoMetadata\": [], \"tags\": [ \"t%d\" ] }".formatted(i)));

		assertThat(statuses).containsExactlyInAnyOrder(200, 412);
		assertThat(repository.findLocalDataHeaderByPokeApiId(25).orElseThrow().version()).isEqualTo(1);
	}

	@Test
	void concurrentUpdatesWithoutAVersionAllSucceedAlongsideASync() throws Exception {
		lookUp("pikachu");
		int updates = 8;

		List<Integer> statuses = concurrently(updates + 1, i -> i == updates
				? post("/api/v1/admin/sync/pokemon/pikachu")
				: patch("/api/v1/pokemon/pikachu").contentType(MERGE_PATCH)
						.content("{ \"tags\": [ \"t%d\" ], \"localizedNames\": [ { \"locale\": \"en\", \"name\": \"P%d\" } ] }"
								.formatted(i, i)));

		assertThat(statuses).containsOnly(200);
		LocalDataHeader header = repository.findLocalDataHeaderByPokeApiId(25).orElseThrow();
		assertThat(header.version()).isEqualTo(updates);
		assertThat(tagRepository.findByPokemonId(header.pokemonId())).hasSize(1);
		assertThat(localizedNameRepository.findByPokemonId(header.pokemonId())).hasSize(1);
	}

	private void lookUp(String nameOrId) throws Exception {
		mockMvc.perform(get("/api/v1/pokemon/{nameOrId}", nameOrId)
				.header(HttpHeaders.AUTHORIZATION, adminToken)).andExpect(status().isOk());
	}

	private void runFullSync() {
		long runId = syncService.startFullSync(SyncTrigger.API).id();
		await().atMost(Duration.ofSeconds(20))
				.until(() -> syncService.getRun(runId).status(), status -> status != SyncRunStatus.RUNNING);
	}

	/**
	 * Sends the requests at the same time and returns their status codes.
	 */
	private List<Integer> concurrently(int count, IntFunction<MockHttpServletRequestBuilder> request)
			throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(count)) {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				MockHttpServletRequestBuilder builder = request.apply(i);
				Callable<Integer> call = () -> {
					start.await();
					return mockMvc.perform(builder
							.header(HttpHeaders.AUTHORIZATION, adminToken)).andReturn().getResponse().getStatus();
				};
				results.add(executor.submit(call));
			}
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get());
			}
			return statuses;
		}
	}

}
