# Spec for API to Browse Pokemon via Paginated Results

branch: claude/feature/api-pokemon-list

## Summary
User Story 01 – Pokemon Enumeration.

As an API consumer, I want to browse the full Pokemon catalogue page by page. Each entry should show the
Pokemon's sprite, category, mass and skills, so I can build list and overview screens without making a
separate call for every Pokemon.

The catalogue comes from PokeAPI, not just from Pokemon already stored in this service. Responses are cached
so repeat page requests are fast and reduce load on PokeAPI. The existing retry, circuit breaker and rate
limiting still apply to every PokeAPI call.

Terminology used in this spec, mapped to PokeAPI data:
- **Sprite**: the Pokemon's default front image URL.
- **Category**: the Pokemon's types, in slot order (for example `electric` for Pikachu, `grass` and
  `poison` for Bulbasaur).
- **Mass**: the Pokemon's weight, shown in kilograms. PokeAPI reports weight in hectograms.
- **Skills**: the Pokemon's abilities (for example "static" and "lightning-rod" for Pikachu), with a flag
  for hidden abilities.

## Functional Requirements
- Provide an endpoint that returns a paginated list of Pokemon from the full PokeAPI catalogue, ordered by
  PokeAPI id (ascending).
- Each entry in a page includes:
  - the PokeAPI id and name
  - the sprite URL
  - the category (the Pokemon's types, in slot order)
  - the mass in kilograms
  - the skills: a list of ability names, each marked hidden or not, in PokeAPI slot order
- Clients choose the page number (zero-based) and page size through query parameters.
- Default page size is 20 and maximum page size is 50. Requests outside the allowed range are rejected with
  a clear validation error.
- Every page response includes pagination metadata: current page, page size, total number of Pokemon in the
  catalogue, and total number of pages.
- The response shape and error format (Problem Details) match the existing API.
- The new endpoint and its parameters, response and error codes are documented in the OpenAPI spec and are
  visible in Swagger UI.
- All PokeAPI calls made for this feature go through the existing resilience layer (retry, circuit breaker,
  rate limiter).
- Service responses are cached in Redis:
  - Full page responses are cached, keyed by page number and page size.
  - Individual Pokemon list entries are cached as well, so the same Pokemon isn't fetched again when it
    appears on a page with a different size.
- The README is updated to describe the new endpoint, its caching behaviour, and any new configuration.

## Non-Functional Requirements
- **Performance**: a cached page is returned in under 100 ms. An uncached page of 20 Pokemon is returned in
  under 3 seconds under normal PokeAPI conditions.
- **Upstream load**: building one page should take as few PokeAPI requests as possible, and requests for
  different Pokemon on the same page should run in parallel within the rate limiter's limits.
- **Cache freshness**: cached pages and entries expire after a configurable time (default matches the
  existing Pokemon cache, 1 hour). The catalogue total is refreshed at least that often.
- **Resilience**: when PokeAPI is unavailable, cached pages are still served. If a page isn't cached,
  existing error behaviour applies (502, 503 or 429).
- **Configuration**: page size limits and cache expiry can be configured with environment variables, in line
  with the existing configuration style.
- **Consistency**: follow the existing project conventions for package layout, naming, error handling, and
  the split between unit and integration tests.

## Out of Scope
- Filtering, searching or sorting by anything other than PokeAPI id.
- Moves (attacks), stats, evolutions or other Pokemon details beyond the fields listed above.
- Category names or ability names in languages other than English.
- Storing list entries in PostgreSQL. This feature only caches them in Redis.
- Changing the existing single-Pokemon lookup endpoint.
- Frontend or UI work. This is an API-only feature.
- Rate limiting incoming requests to this API.

## Possible Edge Cases
- A page past the end of the catalogue returns an empty list with correct metadata, not an error.
- The last page is partially filled.
- Page size or page number is negative, zero (for size), above the maximum, or not a number.
- A Pokemon has no default sprite (some alternate forms). The sprite field is empty and the request does
  not fail.
- A Pokemon is an alternate form (for example "deoxys-attack"). Its category and skills come from the
  form's own data.
- PokeAPI fails for one Pokemon on a page while the others succeed. Expected behaviour needs to be decided;
  see Open Questions.
- The PokeAPI catalogue total changes while cached pages still exist, so metadata may be briefly stale.
- The circuit breaker opens or the rate limit runs out partway through building a page.
- Two concurrent requests for the same uncached page shouldn't each make a full set of PokeAPI calls, or the
  duplicate work should at least be harmless.

## Acceptance Criteria
- Requesting the list without parameters returns the first 20 Pokemon (ids 1–20) with correct pagination
  metadata.
- Each entry has an id, name, sprite URL, category, mass in kilograms, and a list of skills with
  hidden-ability flags. For example, Bulbasaur shows the category "grass" and "poison", 6.9 kg, and the skills "overgrow" and
  "chlorophyll" (hidden).
- Requesting a specific page and size returns the matching slice of the catalogue, and the metadata shows
  the correct totals.
- Requesting a page size above the maximum, or a negative page or size, returns 400 with a Problem Details
  body.
- Requesting a page past the end returns 200 with an empty list.
- Requesting the same page twice makes no PokeAPI calls the second time.
- Requesting a different page size that overlaps already-cached entries only fetches the Pokemon that aren't
  cached yet.
- With PokeAPI down, a previously cached page is still returned successfully.
- With PokeAPI down and the page not cached, the response follows the existing 502, 503 or 429 behaviour.
- The endpoint appears in Swagger UI with its parameters, response schema and error responses documented.
- All existing tests still pass.

## Open Questions
- **Route**: should this replace the current "list stored Pokemon" endpoint (`GET /api/v1/pokemon`), or be a
  new route? Replacing it changes the meaning of an existing endpoint. Suggested default: replace it, since
  the stored-only list was a skeleton placeholder. Replace it.
- **Category**: confirm that "category" means the species genus (for example "Mouse Pokémon") and not the
  Pokemon's types (for example "electric"). It is the pokemon's type.
- **Skills**: confirm that "skills" means abilities. The alternative is moves, which can number in the
  hundreds per Pokemon and would greatly increase response size. It means abilities.
- **Mass units**: is kilograms enough, or should the response also include the raw PokeAPI value
  (hectograms) or pounds? Kilograms is enough.
- **Partial failures**: if one Pokemon on a page can't be fetched, should the whole page fail, or should the
  page return with that entry marked unavailable? Suggested default: fail the whole page, and don't cache
  it. Fail the whole page, and don't cache it.
- **Pagination style**: zero-based page and size (matching Spring conventions and the current endpoint), or
  offset and limit (matching PokeAPI)? Zero-based page and size.
- **Cache expiry**: should list entries and pages share the existing cache expiry setting, or have their
  own? Share the existing cache expiry setting.

## Testing Guidelines
Create test file(s) for the new feature and cover the following cases with meaningful tests, without going too heavy:
- Unit tests (Mockito, no Docker) in `src/test/java`
  - Mapping from PokeAPI data to a list entry: hectogram-to-kilogram conversion, type (category) order,
    skills order and hidden flag, missing sprite or abilities.
  - Service builds a page from the catalogue listing and per-Pokemon details, and computes pagination
    metadata correctly, including the last page and past-the-end pages.
  - Service uses cached entries and only fetches missing ones.
  - Controller: default parameters, custom page and size, validation errors (400), and error mapping for
    upstream failures (502, 503, 429).
  - PokeAPI client: parsing of the catalogue listing, including server-error handling.
- Integration tests (Testcontainers) in `src/integrationTest/java`
  - With WireMock PokeAPI stubs for the catalogue listing and Pokemon details: fetching the first
    page returns the expected entries and metadata.
  - A second request for the same page is served from Redis without new PokeAPI requests (verified through
    WireMock request counts).
  - A cached page is still served when PokeAPI is failing or the circuit is open.
  - Past-the-end page and invalid parameters behave as specified.
  - The OpenAPI document includes the new endpoint with its parameters and responses.
