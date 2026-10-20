package com.ballastlane.pokemon.web

import com.ballastlane.pokemon.domain.SyncRunStatus
import com.ballastlane.pokemon.domain.SyncTrigger
import com.ballastlane.pokemon.exception.PokemonNotFoundException
import com.ballastlane.pokemon.exception.SyncAlreadyRunningException
import com.ballastlane.pokemon.exception.SyncRunNotFoundException
import com.ballastlane.pokemon.sync.PokemonSyncService
import com.ballastlane.pokemon.sync.SyncOutcome
import com.ballastlane.pokemon.web.dto.SyncFailure
import com.ballastlane.pokemon.web.dto.SyncRunReport
import com.ballastlane.pokemon.web.dto.SyncedPokemon
import com.ninjasquad.springmockk.MockkBean
import io.mockk.Called
import io.mockk.every
import io.mockk.verify
import java.time.Instant
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@WebMvcTest(SyncController::class)
@Import(WebSecurityTestConfiguration::class)
@WithMockUser(roles = ["ADMIN"])
class SyncControllerTest @Autowired constructor(private val mockMvc: MockMvc) {

    @MockkBean
    private lateinit var syncService: PokemonSyncService

    @Test
    fun startFullSyncReturns202WithLocation() {
        every { syncService.startFullSync(SyncTrigger.API) } returns
            SyncRunReport(7L, SyncTrigger.API, SyncRunStatus.RUNNING, STARTED, null, 0, 0, 0, 0, listOf())

        mockMvc.post("/api/v1/admin/sync/runs").andExpect {
            status { isAccepted() }
            header { string("Location", "/api/v1/admin/sync/runs/7") }
            jsonPath("$.id") { value(7) }
            jsonPath("$.status") { value("RUNNING") }
            jsonPath("$.trigger") { value("API") }
            jsonPath("$.startedAt") { value("2026-10-17T10:00:00Z") }
        }
    }

    @Test
    fun startFullSyncReturns409WhenAnotherRunIsRunning() {
        every { syncService.startFullSync(SyncTrigger.API) } throws SyncAlreadyRunningException(3L)

        mockMvc.post("/api/v1/admin/sync/runs").andExpect {
            status { isConflict() }
            jsonPath("$.title") { value("Synchronization already running") }
            jsonPath("$.runId") { value(3) }
            jsonPath("$.detail") {
                value("Synchronization run 3 is still running; follow it at /api/v1/admin/sync/runs/3")
            }
        }
    }

    @Test
    fun getRunReturnsCountsAndFailures() {
        every { syncService.getRun(5L) } returns SyncRunReport(
            5L, SyncTrigger.SCHEDULED, SyncRunStatus.COMPLETED_WITH_ERRORS, STARTED, STARTED.plusSeconds(60), 3, 1, 1, 1,
            listOf(SyncFailure("missingno", "Pokemon 'missingno' was not found")),
        )

        mockMvc.get("/api/v1/admin/sync/runs/5").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("COMPLETED_WITH_ERRORS") }
            jsonPath("$.catalogueCount") { value(3) }
            jsonPath("$.created") { value(1) }
            jsonPath("$.updated") { value(1) }
            jsonPath("$.failed") { value(1) }
            jsonPath("$.failures[0].name") { value("missingno") }
        }
    }

    @Test
    fun getRunReturns404WhenMissing() {
        every { syncService.getRun(42L) } throws SyncRunNotFoundException("Synchronization run 42 was not found")

        mockMvc.get("/api/v1/admin/sync/runs/42").andExpect {
            status { isNotFound() }
            jsonPath("$.title") { value("Synchronization run not found") }
        }
    }

    @Test
    fun getRunRejectsNonNumericIds() {
        mockMvc.get("/api/v1/admin/sync/runs/abc").andExpect { status { isBadRequest() } }
    }

    @Test
    fun getLatestRunReturnsRunOr404() {
        every { syncService.getLatestRun() } returns
            SyncRunReport(9L, SyncTrigger.API, SyncRunStatus.COMPLETED, STARTED, STARTED, 0, 0, 0, 0, listOf())
        mockMvc.get("/api/v1/admin/sync/runs/latest").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(9) }
        }

        every { syncService.getLatestRun() } throws SyncRunNotFoundException("none")
        mockMvc.get("/api/v1/admin/sync/runs/latest").andExpect { status { isNotFound() } }
    }

    @Test
    fun syncPokemonReturnsResult() {
        every { syncService.syncOne("pikachu") } returns SyncedPokemon(25, "pikachu", SyncOutcome.CREATED, STARTED)

        mockMvc.post("/api/v1/admin/sync/pokemon/pikachu").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(25) }
            jsonPath("$.name") { value("pikachu") }
            jsonPath("$.outcome") { value("CREATED") }
            jsonPath("$.lastSyncedAt") { value("2026-10-17T10:00:00Z") }
        }
    }

    @Test
    fun syncPokemonReturns404ForUnknownPokemon() {
        every { syncService.syncOne("missingno") } throws PokemonNotFoundException("missingno")

        mockMvc.post("/api/v1/admin/sync/pokemon/missingno").andExpect { status { isNotFound() } }
    }

    @Test
    fun syncPokemonRejectsInvalidInput() {
        mockMvc.post("/api/v1/admin/sync/pokemon/pika_chu!").andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("must be a pokemon name or numeric id") }
        }
        verify { syncService wasNot Called }
    }

    private companion object {
        val STARTED: Instant = Instant.parse("2026-10-17T10:00:00Z")
    }
}
