package com.ballastlane.pokemon.web

import com.ballastlane.pokemon.auth.OpenApiSecurity
import com.ballastlane.pokemon.domain.SyncTrigger
import com.ballastlane.pokemon.sync.PokemonSyncService
import com.ballastlane.pokemon.web.dto.SyncRunReport
import com.ballastlane.pokemon.web.dto.SyncedPokemon
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Pattern
import java.net.URI
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Admin endpoints that replicate PokeAPI pokemon into the local PostgreSQL store. All of them need an access token of
 * a user with the `ADMIN` role (see `SecurityConfig`).
 */
@Tag(
    name = "Admin: synchronization",
    description = "Replicate PokeAPI pokemon into the local PostgreSQL store, keeping proprietary data intact. " +
        "Needs an ADMIN access token",
)
@RestController
@RequestMapping(SyncController.BASE_PATH)
class SyncController(private val syncService: PokemonSyncService) {

    @Operation(
        summary = "Start a full synchronization",
        description = "Starts synchronizing the whole PokeAPI catalogue into PostgreSQL in the background and " +
            "returns the new run straight away. Follow its progress through the Location header. Only one full " +
            "synchronization can run at a time.",
    )
    @ApiResponse(responseCode = "202", description = "Synchronization started")
    @ApiResponse(
        responseCode = "409", description = "Another full synchronization is still running",
        content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
    )
    @SecurityRequirement(name = OpenApiSecurity.BEARER)
    @ApiResponse(
        responseCode = "401", description = "Missing, invalid or expired access token",
        content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "403", description = "Signed in without the ADMIN role",
        content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping("/runs")
    fun startFullSync(): ResponseEntity<SyncRunReport> {
        val run = syncService.startFullSync(SyncTrigger.API)
        return ResponseEntity.accepted().location(runUri(checkNotNull(run.id))).body(run)
    }

    @Operation(
        summary = "Get the latest synchronization run",
        description = "Status, counts and failures of the most recently started full synchronization.",
    )
    @ApiResponse(responseCode = "200", description = "Latest run")
    @ApiResponse(
        responseCode = "404", description = "No full synchronization has been started yet",
        content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
    )
    @SecurityRequirement(name = OpenApiSecurity.BEARER)
    @ApiResponse(
        responseCode = "401", description = "Missing, invalid or expired access token",
        content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "403", description = "Signed in without the ADMIN role",
        content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
    )
    @GetMapping("/runs/latest")
    fun getLatestRun(): SyncRunReport = syncService.getLatestRun()

    @Operation(
        summary = "Get a synchronization run",
        description = "Status, counts and failures of one full synchronization.",
    )
    @ApiResponse(responseCode = "200", description = "Run found")
    @ApiResponse(
        responseCode = "400", description = "Invalid run id",
        content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "404", description = "Run does not exist",
        content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
    )
    @SecurityRequirement(name = OpenApiSecurity.BEARER)
    @ApiResponse(
        responseCode = "401", description = "Missing, invalid or expired access token",
        content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "403", description = "Signed in without the ADMIN role",
        content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
    )
    @GetMapping("/runs/{runId}")
    fun getRun(@Parameter(description = "Synchronization run id", example = "1") @PathVariable runId: Long): SyncRunReport =
        syncService.getRun(runId)

    @Operation(
        summary = "Synchronize one pokemon",
        description = "Fetches one pokemon from PokeAPI and creates or refreshes its local copy. Proprietary data " +
            "(localized names, geographical metadata, tags) is left unchanged.",
    )
    @ApiResponse(responseCode = "200", description = "Pokemon synchronized")
    @ApiResponse(
        responseCode = "400", description = "Invalid name or id",
        content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "404", description = "Pokemon does not exist in PokeAPI",
        content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "429", description = "Outgoing PokeAPI rate limit exhausted",
        content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "502", description = "PokeAPI failed after retries",
        content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "503", description = "Circuit breaker open; PokeAPI calls are paused",
        content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
    )
    @SecurityRequirement(name = OpenApiSecurity.BEARER)
    @ApiResponse(
        responseCode = "401", description = "Missing, invalid or expired access token",
        content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
    )
    @ApiResponse(
        responseCode = "403", description = "Signed in without the ADMIN role",
        content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
    )
    @PostMapping("/pokemon/{nameOrId}")
    fun syncPokemon(
        @Parameter(description = "Pokemon name or PokeAPI id", example = "pikachu")
        @PathVariable
        @Pattern(regexp = NAME_OR_ID_PATTERN, message = "must be a pokemon name or numeric id")
        nameOrId: String,
    ): SyncedPokemon = syncService.syncOne(nameOrId)

    companion object {

        const val BASE_PATH = "/api/v1/admin/sync"

        private const val PROBLEM_JSON = "application/problem+json"

        private const val NAME_OR_ID_PATTERN = "^[a-zA-Z0-9-]{1,50}$"

        fun runUri(runId: Long): URI = URI.create("$BASE_PATH/runs/$runId")
    }
}
