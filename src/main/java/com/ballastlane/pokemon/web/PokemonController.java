package com.ballastlane.pokemon.web;

import com.ballastlane.pokemon.auth.OpenApiSecurity;
import com.ballastlane.pokemon.config.CatalogProperties;
import com.ballastlane.pokemon.exception.InvalidPageRequestException;
import com.ballastlane.pokemon.localdata.IfMatch;
import com.ballastlane.pokemon.localdata.LocalDataRequestParser;
import com.ballastlane.pokemon.localdata.LocalDataService;
import com.ballastlane.pokemon.localdata.LocalDataUpdate;
import com.ballastlane.pokemon.service.PokemonCatalogService;
import com.ballastlane.pokemon.service.PokemonDetailsService;
import com.ballastlane.pokemon.web.dto.LocalData;
import com.ballastlane.pokemon.web.dto.LocalDataRequest;
import com.ballastlane.pokemon.web.dto.PokemonDetails;
import com.ballastlane.pokemon.web.dto.PokemonSummaryPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Pokemon", description = "Browse the PokeAPI catalogue, look up single pokemon and update their local "
		+ "(proprietary) data")
@RestController
@RequestMapping("/api/v1/pokemon")
public class PokemonController {

	private static final String PROBLEM_JSON = "application/problem+json";

	private static final String MERGE_PATCH_JSON = "application/merge-patch+json";

	private static final String LOCAL_DATA_EXAMPLE = """
			{
			  "localizedNames": [ { "locale": "ja-JP", "name": "ピカチュウ" }, { "locale": "es", "name": "Pikachu" } ],
			  "geoMetadata": [ { "kind": "region", "value": "kanto", "note": "Common in Viridian Forest" } ],
			  "tags": [ "mascot", "starter" ]
			}""";

	private final PokemonDetailsService pokemonDetailsService;

	private final PokemonCatalogService pokemonCatalogService;

	private final CatalogProperties catalogProperties;

	private final LocalDataService localDataService;

	private final LocalDataRequestParser localDataRequestParser;

	public PokemonController(PokemonDetailsService pokemonDetailsService,
			PokemonCatalogService pokemonCatalogService, CatalogProperties catalogProperties,
			LocalDataService localDataService, LocalDataRequestParser localDataRequestParser) {
		this.pokemonDetailsService = pokemonDetailsService;
		this.pokemonCatalogService = pokemonCatalogService;
		this.catalogProperties = catalogProperties;
		this.localDataService = localDataService;
		this.localDataRequestParser = localDataRequestParser;
	}

	/**
	 * Returns the detailed view of a pokemon by name or PokeAPI id, with its current local data.
	 */
	@Operation(summary = "Get pokemon details by name or id",
			description = "Image (official artwork, or the default sprite), base stats, English Pokedex description "
					+ "and evolution chain. Built from PokeAPI and cached in Redis; the basic pokemon is also "
					+ "recorded in PostgreSQL on first lookup. 'localData' (localized names, geographical metadata, "
					+ "tags) is read from PostgreSQL on every request, so it always shows the latest update. The "
					+ "ETag versions 'localData' only; send it as If-Match on PUT or PATCH.")
	@ApiResponse(responseCode = "200", description = "Pokemon found",
			headers = @Header(name = HttpHeaders.ETAG, description = "Version of 'localData', e.g. \"3\""))
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
	public ResponseEntity<PokemonDetails> getPokemon(
			@Parameter(description = "Pokemon name or PokeAPI id", example = "pikachu")
			@PathVariable @Pattern(regexp = "^[a-zA-Z0-9-]{1,50}$",
					message = "must be a pokemon name or numeric id") String nameOrId) {
		PokemonDetails details = pokemonDetailsService.getPokemon(nameOrId);
		LocalData localData = localDataService.read(details.id());
		return withEntityTag(localData.version()).body(details.withLocalData(localData));
	}

	@Operation(summary = "Replace the local data of a pokemon",
			description = "Replaces all proprietary data (localized names, geographical metadata, tags) of a pokemon "
					+ "stored in the local database. All three fields are required; an empty list clears that kind "
					+ "of data. Upstream (PokeAPI) fields are read only. PokeAPI is never called.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
			mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = LocalDataRequest.class),
			examples = @ExampleObject(name = "pikachu", value = LOCAL_DATA_EXAMPLE)))
	@ApiResponse(responseCode = "200", description = "Local data updated (or already as requested)",
			headers = @Header(name = HttpHeaders.ETAG, description = "New version of the local data"))
	@ApiResponse(responseCode = "400", description = "Malformed body, invalid fields (listed in 'errors'), invalid "
			+ "name or id, or invalid If-Match",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "Pokemon is not stored locally",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "412", description = "If-Match doesn't match the current version",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "413", description = "Request body too large",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "415", description = "Body is not JSON",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@SecurityRequirement(name = OpenApiSecurity.BEARER)
	@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "Signed in without the USER role",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@PutMapping(path = "/{nameOrId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<LocalData> replaceLocalData(
			@Parameter(description = "Pokemon name or PokeAPI id", example = "pikachu")
			@PathVariable @Pattern(regexp = "^[a-zA-Z0-9-]{1,50}$",
					message = "must be a pokemon name or numeric id") String nameOrId,
			@Parameter(description = "Apply only if the local data is still at this version (the ETag), e.g. \"3\"")
			@RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@RequestBody(required = false) byte[] body) {
		return updateLocalData(nameOrId, ifMatch, localDataRequestParser.parseReplacement(body));
	}

	@Operation(summary = "Partially update the local data of a pokemon",
			description = "JSON Merge Patch on the proprietary data of a pokemon stored in the local database: a "
					+ "missing field is kept, null clears it, and a list replaces it. Upstream (PokeAPI) fields are "
					+ "read only. PokeAPI is never called.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
			mediaType = MERGE_PATCH_JSON, schema = @Schema(implementation = LocalDataRequest.class),
			examples = @ExampleObject(name = "tags only", value = "{ \"tags\": [ \"mascot\" ] }")))
	@ApiResponse(responseCode = "200", description = "Local data updated (or already as requested)",
			headers = @Header(name = HttpHeaders.ETAG, description = "New version of the local data"))
	@ApiResponse(responseCode = "400", description = "Malformed body, invalid fields (listed in 'errors'), invalid "
			+ "name or id, or invalid If-Match",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "Pokemon is not stored locally",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "412", description = "If-Match doesn't match the current version",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "413", description = "Request body too large",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "415", description = "Body is not JSON",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@SecurityRequirement(name = OpenApiSecurity.BEARER)
	@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "403", description = "Signed in without the USER role",
			content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
	@PatchMapping(path = "/{nameOrId}", consumes = { MERGE_PATCH_JSON, MediaType.APPLICATION_JSON_VALUE })
	public ResponseEntity<LocalData> patchLocalData(
			@Parameter(description = "Pokemon name or PokeAPI id", example = "pikachu")
			@PathVariable @Pattern(regexp = "^[a-zA-Z0-9-]{1,50}$",
					message = "must be a pokemon name or numeric id") String nameOrId,
			@Parameter(description = "Apply only if the local data is still at this version (the ETag), e.g. \"3\"")
			@RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@RequestBody(required = false) byte[] body) {
		return updateLocalData(nameOrId, ifMatch, localDataRequestParser.parsePatch(body));
	}

	private ResponseEntity<LocalData> updateLocalData(String nameOrId, String ifMatch, LocalDataUpdate update) {
		IfMatch precondition = ifMatch != null ? IfMatch.parse(ifMatch) : null;
		LocalData localData = localDataService.update(nameOrId, update, precondition);
		return withEntityTag(localData.version()).body(localData);
	}

	private static ResponseEntity.BodyBuilder withEntityTag(long version) {
		return ResponseEntity.ok().eTag(IfMatch.entityTag(version));
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
