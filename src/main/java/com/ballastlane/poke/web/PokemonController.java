package com.ballastlane.poke.web;

import com.ballastlane.poke.service.PokemonService;
import com.ballastlane.poke.web.dto.PageResponse;
import com.ballastlane.poke.web.dto.PokemonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Pokemon", description = "Look up pokemon from PokeAPI and list the ones stored locally")
@RestController
@RequestMapping("/api/v1/pokemon")
public class PokemonController {

	private static final String PROBLEM_JSON = "application/problem+json";

	private final PokemonService pokemonService;

	public PokemonController(PokemonService pokemonService) {
		this.pokemonService = pokemonService;
	}

	/**
	 * Returns a pokemon by name or PokeAPI id, fetching it from PokeAPI on first access.
	 */
	@Operation(summary = "Get a pokemon by name or id",
			description = "Served from the Redis cache or PostgreSQL when available; otherwise fetched from "
					+ "PokeAPI, stored and cached.")
	@ApiResponse(responseCode = "200", description = "Pokemon found")
	@ApiResponse(responseCode = "400", description = "Invalid name or id",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "Pokemon does not exist in PokeAPI",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "Outgoing PokeAPI rate limit exhausted",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "502", description = "PokeAPI failed after retries",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "503", description = "Circuit breaker open; PokeAPI calls are paused",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@GetMapping("/{nameOrId}")
	public PokemonResponse getPokemon(
			@Parameter(description = "Pokemon name or PokeAPI id", example = "pikachu")
			@PathVariable @Pattern(regexp = "^[a-zA-Z0-9-]{1,50}$",
					message = "must be a pokemon name or numeric id") String nameOrId) {
		return pokemonService.getPokemon(nameOrId);
	}

	/**
	 * Lists the pokemon already stored in the local database.
	 */
	@Operation(summary = "List stored pokemon",
			description = "Pages through pokemon already stored in PostgreSQL (does not call PokeAPI).")
	@ApiResponse(responseCode = "200", description = "Page of stored pokemon")
	@GetMapping
	public PageResponse<PokemonResponse> listStored(
			@ParameterObject @PageableDefault(size = 20, sort = "pokeApiId",
					direction = Sort.Direction.ASC) Pageable pageable) {
		return PageResponse.from(pokemonService.listStored(pageable));
	}

}
