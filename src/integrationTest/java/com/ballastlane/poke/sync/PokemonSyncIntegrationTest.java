package com.ballastlane.poke.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ballastlane.poke.domain.GeoKind;
import com.ballastlane.poke.domain.Pokemon;
import com.ballastlane.poke.domain.PokemonAbility;
import com.ballastlane.poke.domain.PokemonGeoMetadata;
import com.ballastlane.poke.domain.PokemonLocalizedName;
import com.ballastlane.poke.domain.PokemonStat;
import com.ballastlane.poke.domain.PokemonTag;
import com.ballastlane.poke.domain.SyncRunStatus;
import com.ballastlane.poke.repository.PokemonGeoMetadataRepository;
import com.ballastlane.poke.repository.PokemonLocalizedNameRepository;
import com.ballastlane.poke.repository.PokemonTagRepository;
import com.ballastlane.poke.support.AbstractApiIntegrationTest;
import com.ballastlane.poke.web.dto.SyncRunReport;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Full syncs walk the stubbed catalogue (bulbasaur, ivysaur, venusaur; two per page in this profile).
 */
class PokemonSyncIntegrationTest extends AbstractApiIntegrationTest {

	@Autowired
	private PokemonSyncService syncService;

	@Autowired
	private PokemonLocalizedNameRepository localizedNameRepository;

	@Autowired
	private PokemonGeoMetadataRepository geoMetadataRepository;

	@Autowired
	private PokemonTagRepository tagRepository;

	@Test
	void fullSyncStoresEveryCataloguePokemon() throws Exception {
		SyncRunReport run = runFullSync();

		assertThat(run.status()).isEqualTo(SyncRunStatus.COMPLETED);
		assertThat(run.catalogueCount()).isEqualTo(3);
		assertThat(run.created()).isEqualTo(3);
		assertThat(run.updated()).isZero();
		assertThat(run.failed()).isZero();
		assertThat(run.finishedAt()).isNotNull();
		assertThat(repository.count()).isEqualTo(3);

		Pokemon bulbasaur = repository.findByPokeApiId(1).orElseThrow();
		assertThat(bulbasaur.getName()).isEqualTo("bulbasaur");
		assertThat(bulbasaur.getTypes()).containsExactly("grass", "poison");
		assertThat(bulbasaur.getStats()).hasSize(6).first().isEqualTo(new PokemonStat("hp", 45));
		assertThat(bulbasaur.getAbilities()).contains(new PokemonAbility("overgrow", false));
		assertThat(bulbasaur.getSpeciesId()).isEqualTo(1);
		assertThat(bulbasaur.getSpeciesName()).isEqualTo("bulbasaur");
		assertThat(bulbasaur.getLastSyncedAt()).isNotNull();
	}

	@Test
	void runningAFullSyncAgainCreatesNoDuplicates() throws Exception {
		runFullSync();

		SyncRunReport second = runFullSync();

		assertThat(second.status()).isEqualTo(SyncRunStatus.COMPLETED);
		assertThat(second.created()).isZero();
		assertThat(second.updated()).isEqualTo(3);
		assertThat(repository.count()).isEqualTo(3);
	}

	@Test
	void syncsNeverChangeProprietaryData() throws Exception {
		runFullSync();
		Pokemon bulbasaur = repository.findByPokeApiId(1).orElseThrow();
		Instant firstSync = bulbasaur.getLastSyncedAt();
		localizedNameRepository.save(new PokemonLocalizedName(bulbasaur, "es-ES", "Bulbasaur"));
		geoMetadataRepository.save(new PokemonGeoMetadata(bulbasaur, GeoKind.REGION, "kanto", "Starter"));
		tagRepository.save(new PokemonTag(bulbasaur, "Starter"));

		runFullSync();
		mockMvc.perform(post("/api/v1/admin/sync/pokemon/1")).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/pokemon/1")).andExpect(status().isOk());

		Pokemon refreshed = repository.findByPokeApiId(1).orElseThrow();
		assertThat(refreshed.getId()).isEqualTo(bulbasaur.getId());
		assertThat(refreshed.getLastSyncedAt()).isAfter(firstSync);
		assertThat(localizedNameRepository.findByPokemonId(bulbasaur.getId()))
				.singleElement()
				.satisfies(name -> {
					assertThat(name.getLocale()).isEqualTo("es-ES");
					assertThat(name.getName()).isEqualTo("Bulbasaur");
				});
		assertThat(geoMetadataRepository.findByPokemonId(bulbasaur.getId()))
				.singleElement()
				.satisfies(geo -> {
					assertThat(geo.getKind()).isEqualTo(GeoKind.REGION);
					assertThat(geo.getValue()).isEqualTo("kanto");
					assertThat(geo.getNote()).isEqualTo("Starter");
				});
		assertThat(tagRepository.findByPokemonId(bulbasaur.getId()))
				.singleElement()
				.satisfies(tag -> assertThat(tag.getTag()).isEqualTo("starter"));
	}

	@Test
	void fullSyncCompletesPokemonRecordedByALookup() throws Exception {
		mockMvc.perform(get("/api/v1/pokemon/1")).andExpect(status().isOk());
		Pokemon recorded = repository.findByPokeApiId(1).orElseThrow();
		assertThat(recorded.getLastSyncedAt()).isNull();

		SyncRunReport run = runFullSync();

		assertThat(run.created()).isEqualTo(2);
		assertThat(run.updated()).isEqualTo(1);
		assertThat(repository.count()).isEqualTo(3);
		Pokemon synced = repository.findByPokeApiId(1).orElseThrow();
		assertThat(synced.getId()).isEqualTo(recorded.getId());
		assertThat(synced.getLastSyncedAt()).isNotNull();
	}

	@Test
	void recordsPokemonThatFailUpstreamAndSyncsTheRest() throws Exception {
		String stubId = pokeApi.addStub("""
				{
				  "priority": 1,
				  "request": {
				    "method": "GET",
				    "urlPath": "/api/v2/pokemon",
				    "queryParameters": { "offset": { "equalTo": "0" }, "limit": { "equalTo": "2" } }
				  },
				  "response": {
				    "status": 200,
				    "headers": { "Content-Type": "application/json" },
				    "jsonBody": {
				      "count": 2,
				      "results": [
				        { "name": "bulbasaur", "url": "https://pokeapi.co/api/v2/pokemon/1/" },
				        { "name": "missingno", "url": "https://pokeapi.co/api/v2/pokemon/9998/" }
				      ]
				    }
				  }
				}""");
		try {
			SyncRunReport run = runFullSync();

			assertThat(run.status()).isEqualTo(SyncRunStatus.COMPLETED_WITH_ERRORS);
			assertThat(run.created()).isEqualTo(1);
			assertThat(run.failed()).isEqualTo(1);
			assertThat(run.failures()).singleElement().satisfies(failure -> {
				assertThat(failure.name()).isEqualTo("missingno");
				assertThat(failure.reason()).contains("was not found");
			});
			assertThat(repository.findByPokeApiId(1)).isPresent();
		}
		finally {
			pokeApi.removeStub(stubId);
		}
	}

	@Test
	void rejectsASecondFullSyncWhileOneIsRunning() throws Exception {
		// Slow down bulbasaur so the first run is still running when the second one is requested.
		String stubId = pokeApi.addStub("""
				{
				  "priority": 1,
				  "request": { "method": "GET", "urlPath": "/api/v2/pokemon/1" },
				  "response": {
				    "status": 200,
				    "headers": { "Content-Type": "application/json" },
				    "bodyFileName": "bulbasaur.json",
				    "fixedDelayMilliseconds": 1500
				  }
				}""");
		try {
			long runId = startFullSync();

			mockMvc.perform(post("/api/v1/admin/sync/runs"))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.title").value("Synchronization already running"))
					.andExpect(jsonPath("$.runId").value(runId));
			mockMvc.perform(get("/api/v1/admin/sync/runs/latest"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.id").value(runId))
					.andExpect(jsonPath("$.status").value("RUNNING"));

			assertThat(awaitFinished(runId).status()).isEqualTo(SyncRunStatus.COMPLETED);
		}
		finally {
			pokeApi.removeStub(stubId);
		}
	}

	@Test
	void runStatusIsServedThroughTheApi() throws Exception {
		mockMvc.perform(get("/api/v1/admin/sync/runs/latest")).andExpect(status().isNotFound());

		long runId = runFullSync().id();

		mockMvc.perform(get("/api/v1/admin/sync/runs/{id}", runId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.trigger").value("API"))
				.andExpect(jsonPath("$.created").value(3));
		mockMvc.perform(get("/api/v1/admin/sync/runs/{id}", runId + 1000)).andExpect(status().isNotFound());
	}

	@Test
	void syncsOnePokemon() throws Exception {
		mockMvc.perform(post("/api/v1/admin/sync/pokemon/PIKACHU"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(25))
				.andExpect(jsonPath("$.name").value("pikachu"))
				.andExpect(jsonPath("$.outcome").value("CREATED"))
				.andExpect(jsonPath("$.lastSyncedAt").exists());
		mockMvc.perform(post("/api/v1/admin/sync/pokemon/25"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.outcome").value("UPDATED"));

		Pokemon pikachu = repository.findByPokeApiId(25).orElseThrow();
		assertThat(pikachu.getStats()).hasSize(6);
		assertThat(pikachu.getSpeciesId()).isEqualTo(25);
		assertThat(repository.count()).isEqualTo(1);
	}

	@Test
	void singleSyncRejectsUnknownAndInvalidPokemon() throws Exception {
		mockMvc.perform(post("/api/v1/admin/sync/pokemon/missingno"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Pokemon not found"));
		mockMvc.perform(post("/api/v1/admin/sync/pokemon/pika_chu!")).andExpect(status().isBadRequest());
		assertThat(repository.count()).isZero();
	}

	private SyncRunReport runFullSync() throws Exception {
		return awaitFinished(startFullSync());
	}

	private long startFullSync() throws Exception {
		String body = mockMvc.perform(post("/api/v1/admin/sync/runs"))
				.andExpect(status().isAccepted())
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.status").value("RUNNING"))
				.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	private SyncRunReport awaitFinished(long runId) {
		return await().atMost(Duration.ofSeconds(20))
				.until(() -> syncService.getRun(runId), run -> run.status() != SyncRunStatus.RUNNING);
	}

}
