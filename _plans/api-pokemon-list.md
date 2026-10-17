# Plan: API to Browse Pokemon via Paginated Results

- Spec: `_specs/api-pokemon-list.md`
- Branch: `claude/feature/api-pokemon-list`

## Decisions (from the spec's answered Open Questions)

| Topic | Decision |
|---|---|
| Route | **Replace** `GET /api/v1/pokemon`. The stored-only list is removed. |
| Category | The Pokemon's **types**, as a list in slot order (e.g. `["grass", "poison"]`). |
| Skills | **Abilities**: name and hidden flag, in slot order. |
| Mass | Kilograms only, with one decimal place (PokeAPI hectograms ÷ 10). |
| Partial failure | If any entry fails, the whole page fails and the page is not cached. |
| Pagination | Zero-based `page` and `size` query parameters. |
| Cache expiry | Shares the existing `app.cache.pokemon-ttl` (default 1h). |

**Effect of the category decision:** types come from the same PokeAPI `/pokemon/{id}` response as the sprite,
weight and abilities. So **no species call is needed**, and one page costs 1 catalogue call plus 1 detail call
per uncached Pokemon.

**Spec follow-up:** some spec sections still describe category as the species genus: Summary terminology, the
"no English genus" and "alternate form species" edge cases, the Bulbasaur acceptance example ("Seed Pokémon"),
and the testing guidelines (genus selection, species stubs). Step 0 below updates them so the spec and plan
agree.

## Response shape

The `GET /api/v1/pokemon?page=0&size=20` response keeps the same pagination fields as today's page response:

- `content`: a list of entries. Each entry has `id`, `name`, `spriteUrl`, `category` (list of type names),
  `massKg` (decimal), and `skills` (a list of `{name, hidden}`).
- `page`, `size`, `totalElements` (PokeAPI catalogue `count`), `totalPages`.

## Design

### Request flow

1. The controller validates `page` and `size`, then calls the catalogue service.
2. The catalogue service builds the page. Its result is cached in a new Redis cache, `pokemon-page`, keyed
   `page:size`.
   - It calls PokeAPI's list endpoint (`/pokemon?offset=page*size&limit=size`) to get the total `count` and
     the names and URLs on this page. Each Pokemon's id is parsed from its URL.
   - It loads each entry through a separate bean, `PokemonSummaryLoader`, whose result is cached in a new
     Redis cache, `pokemon-summary`, keyed by id. On a miss, the loader calls the existing
     `PokeApiClient#fetchPokemon(id)` and maps the result.
   - Entries are loaded in parallel on virtual threads. The service waits for all of them. If one fails, it
     unwraps the async wrapper and rethrows the original exception (`PokeApiException`,
     `CallNotPermittedException`, `RequestNotPermitted`), so the existing exception handler maps it to 502,
     503 or 429.
3. If any entry fails, no page is cached, because exceptions are never cached. Entries that loaded
   successfully stay in the entry cache, which is fine and saves work on retry.

### Why a separate loader bean

Spring's `@Cacheable` only works through the proxy. If the page service called its own cached method, the
entry cache would be silently skipped.

### Resilience

- Add a client method that fetches the catalogue page, with the same `@Retry`, `@CircuitBreaker` and
  `@RateLimiter("pokeapi")` annotations as `fetchPokemon`. The detail calls already go through
  `fetchPokemon`.
- **Rate limiter conflict (must fix):** the current limiter allows 20 calls per second and waits at most
  500 ms. An uncached default page needs 21 calls, and a page of 50 needs 51. Those requests would return
  **429** partway through. Fix: raise `timeout-duration` to about 3s, so a full page of 50 can wait through
  up to 2 extra refresh periods while staying within the spec's 3-second target. Keep the 20/s limit to
  respect PokeAPI fair use. Document this trade-off in the README.
- If the circuit breaker opens partway through a page, remaining calls fail fast and the page returns 503,
  as the spec requires.

### Caching

- Register `pokemon-page` and `pokemon-summary` in `CacheConfig`, each with a JSON serializer for a
  **concrete** type. The page cache stores a dedicated page record, not the generic `PageResponse<T>`, so
  Redis serialization doesn't need generic type information. Both caches use `app.cache.pokemon-ttl`.
- Use `sync = true` on both caches to stop duplicate loads when concurrent requests hit the same uncached
  page or entry. This works within one app instance, which is enough for the spec ("harmless").
- The existing `pokemon` cache for single lookups is unchanged.

### Validation and configuration

- New `app.catalog.*` properties: `default-page-size` (20) and `max-page-size` (50). Environment variables
  follow the existing pattern (`CATALOG_DEFAULT_PAGE_SIZE`, `CATALOG_MAX_PAGE_SIZE`).
- The limits are configurable, so validate in code (`page >= 0`, `1 <= size <= max`) rather than with fixed
  `@Max` annotations. Invalid values raise a new exception that is mapped to **400** with a Problem Details
  body.
- Add a handler for non-numeric `page` or `size` (type mismatch), so it also returns a 400 Problem Details
  body.
- Compute the offset as a long. An offset beyond the integer range is treated as past the end: empty content
  with correct metadata. To get the metadata, call the list endpoint with a minimal limit for `count`.

### Edge cases mapped

| Edge case | Handling |
|---|---|
| Past the end | PokeAPI returns empty `results` with `count`, so the response has empty content and correct totals. |
| Partial last page | The size comes from PokeAPI's results, and `totalPages` is computed from `count`. |
| No sprite | `spriteUrl` is null and the request doesn't fail. The mapper is already null-safe. |
| Alternate forms (id ≥ 10001) | No special handling. Types and abilities come from the form's own `/pokemon` data. |
| Catalogue count changes | Can be stale for up to one cache TTL, as the spec allows. |

## Implementation steps

0. **Spec alignment:** update the genus wording in `_specs/api-pokemon-list.md` (listed above) to "types".
1. **PokeAPI client and DTOs**
   - Add `abilities` (name, `is_hidden`, slot) to `PokeApiPokemon`.
   - Add a catalogue list DTO (`count`, `results[name, url]`).
   - Add `PokeApiClient#fetchPokemonPage(offset, limit)` with the resilience annotations.
2. **Domain records and mapper**
   - New records in `web/dto`: `PokemonSummary`, `Skill`, and a concrete page record.
   - Mapper method that builds `PokemonSummary` from `PokeApiPokemon`: types and abilities in slot order,
     kilograms with one decimal place.
3. **Configuration**
   - `CatalogProperties` record (`app.catalog.*`).
   - Two new caches in `CacheConfig`.
   - Raise the rate limiter `timeout-duration` in `application.yml`.
4. **Service layer**
   - `PokemonSummaryLoader` (cached per entry).
   - `PokemonCatalogService` (cached per page): parallel loading on virtual threads, exception unwrapping,
     metadata calculation.
5. **Web layer**
   - Replace `listStored` in `PokemonController` with the catalogue endpoint. Add `page` and `size` request
     parameters with defaults from properties.
   - Add OpenAPI annotations (summary, parameters, 200/400/429/502/503 responses).
   - Add exception handlers for invalid page requests and type mismatches.
6. **Remove the stored-only list**
   - Delete `PokemonService#listStored` and its tests.
   - Delete `PageResponse` if it's no longer used.
   - Keep `PokemonRepository` for single lookups.
7. **Unit tests** (`src/test/java`)
   - Mapper: kilogram conversion (69 → 6.9), type and ability ordering, hidden flag, missing sprite or
     abilities.
   - `PokemonCatalogService` (Mockito):
     - builds content and metadata
     - handles partial last page and past-the-end
     - delegates to the loader for each id
     - one failing entry makes the page fail with the original exception
   - `PokemonSummaryLoader`: maps the client result and propagates errors.
   - `PokeApiClientTest`: parses the catalogue listing, and wraps a 5xx in `PokeApiException`.
   - `PokemonControllerTest`:
     - default and custom page/size
     - 400 for negative page, size 0, size above max, non-numeric values
     - 429/502/503 mapping
   - Remove the `listStored` tests.
8. **Integration tests** (`src/integrationTest/java`)
   - WireMock stubs:
     - the catalogue list, matched on `offset` and `limit` query parameters, with a small `count` so totals
       are easy to check
     - detail stubs by id, with abilities added to the fixture JSON
     - a past-the-end listing
   - `PokemonCatalogIntegrationTest`:
     - first page has the expected entries and metadata
     - repeating the page makes no new WireMock requests
     - an overlapping page size only fetches uncached ids
     - a cached page is served while the circuit is open
     - past-the-end and invalid parameters behave as specified
   - Update `PokemonApiIntegrationTest` (remove the stored-list test) and `OpenApiIntegrationTest` (check
     `page` and `size` parameters and the new response codes).
   - Check that the existing rate limiter test still holds with the new timeout. It overrides
     `timeout-duration=0s`, so it should.
9. **README**
   - Replace the "List stored Pokémon" section with the catalogue endpoint: parameters, entry fields,
     example.
   - Caching: describe the two new caches and their keys.
   - Configuration table: `CATALOG_*` variables.
   - Rate limiter: explain the new timeout and why.
   - Testing tables: new and changed tests.
10. **Verify**
    - `./gradlew build` passes all tests.
    - Smoke test with Docker Compose against the real PokeAPI: first page, page 1 with size 50, past the end,
      and a repeat request served from cache.

## Files

| Action | File |
|---|---|
| Modify | `client/PokeApiClient.java`, `client/dto/PokeApiPokemon.java`, `config/CacheConfig.java`, `mapper/PokemonMapper.java`, `service/PokemonService.java`, `web/PokemonController.java`, `web/GlobalExceptionHandler.java`, `application.yml`, `README.md`, `_specs/api-pokemon-list.md` |
| Add | `client/dto/PokeApiPokemonList.java`, `config/CatalogProperties.java`, `service/PokemonCatalogService.java`, `service/PokemonSummaryLoader.java`, `web/dto/PokemonSummary.java`, `web/dto/Skill.java`, `web/dto/PokemonSummaryPage.java`, `exception/InvalidPageRequestException.java` |
| Delete | `web/dto/PageResponse.java` (if unused) |
| Tests | New: `PokemonCatalogServiceTest`, `PokemonSummaryLoaderTest`, `PokemonCatalogIntegrationTest`, WireMock list/detail stubs. Updated: `PokemonControllerTest`, `PokemonServiceTest`, `PokemonMapperTest`, `PokeApiClientTest`, `PokemonApiIntegrationTest`, `OpenApiIntegrationTest` |

## Risks

- **Rate limiter versus page size** (handled in step 3). If PokeAPI fair use requires a lower limit, the
  maximum page size or the 3-second target will need to be revisited.
- **Breaking change:** clients of the old `GET /api/v1/pokemon` (stored-only list) get a different payload.
  This is acceptable per the spec. Call it out in the README and the commit.
- **Cache keys depend on the size limits:** changing `max-page-size` later doesn't invalidate cached pages,
  but cached keys stay valid. That's harmless.
