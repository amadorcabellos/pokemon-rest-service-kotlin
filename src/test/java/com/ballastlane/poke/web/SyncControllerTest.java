package com.ballastlane.poke.web;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ballastlane.poke.domain.SyncRunStatus;
import com.ballastlane.poke.domain.SyncTrigger;
import com.ballastlane.poke.exception.PokemonNotFoundException;
import com.ballastlane.poke.exception.SyncAlreadyRunningException;
import com.ballastlane.poke.exception.SyncRunNotFoundException;
import com.ballastlane.poke.sync.PokemonSyncService;
import com.ballastlane.poke.sync.SyncOutcome;
import com.ballastlane.poke.web.dto.SyncFailure;
import com.ballastlane.poke.web.dto.SyncRunReport;
import com.ballastlane.poke.web.dto.SyncedPokemon;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SyncController.class)
class SyncControllerTest {

	private static final Instant STARTED = Instant.parse("2026-10-17T10:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PokemonSyncService syncService;

	@Test
	void startFullSyncReturns202WithLocation() throws Exception {
		given(syncService.startFullSync(SyncTrigger.API)).willReturn(
				new SyncRunReport(7L, SyncTrigger.API, SyncRunStatus.RUNNING, STARTED, null, 0, 0, 0, 0, List.of()));

		mockMvc.perform(post("/api/v1/admin/sync/runs"))
				.andExpect(status().isAccepted())
				.andExpect(header().string("Location", "/api/v1/admin/sync/runs/7"))
				.andExpect(jsonPath("$.id").value(7))
				.andExpect(jsonPath("$.status").value("RUNNING"))
				.andExpect(jsonPath("$.trigger").value("API"))
				.andExpect(jsonPath("$.startedAt").value("2026-10-17T10:00:00Z"));
	}

	@Test
	void startFullSyncReturns409WhenAnotherRunIsRunning() throws Exception {
		given(syncService.startFullSync(SyncTrigger.API)).willThrow(new SyncAlreadyRunningException(3L));

		mockMvc.perform(post("/api/v1/admin/sync/runs"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Synchronization already running"))
				.andExpect(jsonPath("$.runId").value(3))
				.andExpect(jsonPath("$.detail").value(
						"Synchronization run 3 is still running; follow it at /api/v1/admin/sync/runs/3"));
	}

	@Test
	void getRunReturnsCountsAndFailures() throws Exception {
		given(syncService.getRun(5L)).willReturn(new SyncRunReport(5L, SyncTrigger.SCHEDULED,
				SyncRunStatus.COMPLETED_WITH_ERRORS, STARTED, STARTED.plusSeconds(60), 3, 1, 1, 1,
				List.of(new SyncFailure("missingno", "Pokemon 'missingno' was not found"))));

		mockMvc.perform(get("/api/v1/admin/sync/runs/5"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED_WITH_ERRORS"))
				.andExpect(jsonPath("$.catalogueCount").value(3))
				.andExpect(jsonPath("$.created").value(1))
				.andExpect(jsonPath("$.updated").value(1))
				.andExpect(jsonPath("$.failed").value(1))
				.andExpect(jsonPath("$.failures[0].name").value("missingno"));
	}

	@Test
	void getRunReturns404WhenMissing() throws Exception {
		given(syncService.getRun(42L)).willThrow(new SyncRunNotFoundException("Synchronization run 42 was not found"));

		mockMvc.perform(get("/api/v1/admin/sync/runs/42"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Synchronization run not found"));
	}

	@Test
	void getRunRejectsNonNumericIds() throws Exception {
		mockMvc.perform(get("/api/v1/admin/sync/runs/abc")).andExpect(status().isBadRequest());
	}

	@Test
	void getLatestRunReturnsRunOr404() throws Exception {
		given(syncService.getLatestRun()).willReturn(
				new SyncRunReport(9L, SyncTrigger.API, SyncRunStatus.COMPLETED, STARTED, STARTED, 0, 0, 0, 0, List.of()));
		mockMvc.perform(get("/api/v1/admin/sync/runs/latest"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(9));

		given(syncService.getLatestRun()).willThrow(new SyncRunNotFoundException("none"));
		mockMvc.perform(get("/api/v1/admin/sync/runs/latest")).andExpect(status().isNotFound());
	}

	@Test
	void syncPokemonReturnsResult() throws Exception {
		given(syncService.syncOne("pikachu")).willReturn(
				new SyncedPokemon(25, "pikachu", SyncOutcome.CREATED, STARTED));

		mockMvc.perform(post("/api/v1/admin/sync/pokemon/pikachu"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(25))
				.andExpect(jsonPath("$.name").value("pikachu"))
				.andExpect(jsonPath("$.outcome").value("CREATED"))
				.andExpect(jsonPath("$.lastSyncedAt").value("2026-10-17T10:00:00Z"));
	}

	@Test
	void syncPokemonReturns404ForUnknownPokemon() throws Exception {
		given(syncService.syncOne("missingno")).willThrow(new PokemonNotFoundException("missingno"));

		mockMvc.perform(post("/api/v1/admin/sync/pokemon/missingno")).andExpect(status().isNotFound());
	}

	@Test
	void syncPokemonRejectsInvalidInput() throws Exception {
		mockMvc.perform(post("/api/v1/admin/sync/pokemon/pika_chu!"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("must be a pokemon name or numeric id"));
		verifyNoInteractions(syncService);
	}

}
