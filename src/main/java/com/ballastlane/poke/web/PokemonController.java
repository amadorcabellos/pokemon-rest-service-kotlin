package com.ballastlane.poke.web;

import com.ballastlane.poke.config.CatalogProperties;
import com.ballastlane.poke.exception.InvalidPageRequestException;
import com.ballastlane.poke.service.PokemonCatalogService;
import com.ballastlane.poke.service.PokemonService;
import com.ballastlane.poke.web.dto.PokemonResponse;
import com.ballastlane.poke.web.dto.PokemonSummaryPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Pokemon", description = "Browse the PokeAPI catalogue and look up single pokemon")
@RestController
@RequestMapping("/api/v1/pokemon")
public class PokemonController {

	private static final String PROBLEM_JSON = "application/problem+json";

	private final PokemonService pokemonService;

	private final PokemonCatalogService pokemonCatalogService;

	private final CatalogProperties catalogProperties;

	public PokemonController(PokemonService pokemonService, PokemonCatalogService pokemonCatalogService,
			CatalogProperties catalogProperties) {
		this.pokemonService = pokemonService;
		this.pokemonCatalogService = pokemonCatalogService;
		this.catalogProperties = catalogProperties;
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
	 * Pages through the full PokeAPI catalogue, ordered by PokeAPI id.
	 */
	@Operation(summary = "Browse pokemon",
			description = "Pages through the full PokeAPI catalogue ordered by id. Each entry has the sprite, "
					+ "category (types), mass in kilograms and skills (abilities). Pages and entries are cached "
					+ "in Redis.")
	@ApiResponse(responseCode = "200", description = "Page of pokemon (empty past the end of the catalogue)")
	@ApiResponse(responseCode = "400", description = "Invalid page or size",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "429", description = "Outgoing PokeAPI rate limit exhausted",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "502", description = "PokeAPI failed after retries",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "503", description = "Circuit breaker open; PokeAPI calls are paused",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@GetMapping
	public PokemonSummaryPage listPokemon(
			@Parameter(description = "Zero-based page number", example = "0")
			@RequestParam(required = false) Integer page,
			@Parameter(description = "Page size (default 20, maximum 50 unless configured otherwise)", example = "20")
			@RequestParam(required = false) Integer size) {
		int pageNumber = page != null ? page : 0;
		int pageSize = size != null ? size : catalogProperties.defaultPageSize();
		if (pageNumber < 0) {
			throw new InvalidPageRequestException("'page' must be 0 or greater");
		}
		if (pageSize < 1 || pageSize > catalogProperties.maxPageSize()) {
			throw new InvalidPageRequestException(
					"'size' must be between 1 and %d".formatted(catalogProperties.maxPageSize()));
		}
		return pokemonCatalogService.getPage(pageNumber, pageSize);
	}

}
