# Spec for API to View Detailed Pokemon Data

branch: claude/feature/api-pokemon-details-01

## Summary
User Story 02 – Detailed View.

As an API consumer, I want to fetch everything about one chosen Pokemon in a single request: its image, core
statistics, narrative description and evolutionary lineage. That lets me build a detail screen without
combining several PokeAPI resources myself.

The data comes from PokeAPI and is assembled from three upstream resources: the Pokemon itself (image and
stats), its species (description), and its evolution chain (lineage). Responses are cached in Redis, and every
PokeAPI call goes through the existing retry, circuit breaker and rate limiting.

Terminology used in this spec, mapped to PokeAPI data:
- **Image**: the Pokemon's official artwork URL, with the default front sprite as a fallback.
- **Core statistics**: the six base stats: HP, Attack, Defense, Special Attack, Special Defense and Speed.
  Each has its base value, and the response also includes their total.
- **Narrative description**: the English Pokedex flavor text of the Pokemon's species, cleaned of line
  breaks and control characters.
- **Evolutionary lineage**: the full evolution chain the Pokemon belongs to, from the base form to the final
  forms, including branches (for example Eevee's eight evolutions). Each stage shows the species name, its id,
  and how it evolves from the previous stage (for example "level 16" or "use water-stone").

## Functional Requirements
- The detailed view replaces the response of the existing lookup, `GET /api/v1/pokemon/{nameOrId}`, keeping
  its path and its name and id rules. This is a breaking change for clients of the current lookup response.
- The lookup keeps storing the basic Pokemon (name, height, weight, base experience, sprite, types) in
  PostgreSQL the first time it is looked up. The detailed view itself is only cached in Redis.
- The detailed view includes:
  - the PokeAPI id and name
  - the image URL (official artwork, with the default sprite as a fallback)
  - the six base stats, each with its name and value, in PokeAPI order, plus the total of all six
  - the narrative description in English
  - the evolution chain as a tree: each stage has its species name, id and evolution conditions
    (trigger, plus minimum level, item or other condition when present), and lists the stages it evolves
    into
- The response marks where the requested Pokemon sits in its evolution chain, so clients can highlight the
  current stage.
- Unknown Pokemon return 404. Invalid names or ids return 400. Both use the existing Problem Details format.
- All PokeAPI calls for this feature go through the existing resilience layer (retry, circuit breaker, rate
  limiter). Upstream failures follow the existing error behaviour (502, 503 or 429).
- Service responses are cached in Redis:
  - The full detailed view is cached per Pokemon. A request by name and a request by id for the same Pokemon
    should share the cached data where practical.
  - Evolution chains are cached separately, keyed by chain, so every member of a family reuses the same
    cached chain.
- The endpoint, its parameters, response schema and error codes are documented in the OpenAPI spec and shown
  in Swagger UI.
- The README is updated to describe the new lookup response (including the breaking change), its fields, caching behaviour and any new
  configuration.

## Non-Functional Requirements
- **Performance**: a cached detailed view is returned in under 100 ms. An uncached one is returned in under
  2 seconds under normal PokeAPI conditions.
- **Upstream load**: building one detailed view should take as few PokeAPI requests as possible (at most one
  each for the Pokemon, its species and its evolution chain). A cached evolution chain saves one call.
- **Cache freshness**: cached views and chains expire after the existing configurable cache expiry (default
  1 hour).
- **Resilience**: a cached detailed view is still served when PokeAPI is unavailable, the circuit breaker is
  open, or the rate limit is used up.
- **Consistency**: follow the existing project conventions for package layout, naming, error handling,
  caching and the split between unit and integration tests.

## Out of Scope
- Moves, abilities, held items, encounter locations and other details beyond the fields listed above.
  Abilities are already in the catalogue list.
- Descriptions in languages other than English, and description history across game versions.
- Storing the detailed view in PostgreSQL. This feature only caches it in Redis.
- Changing the catalogue list endpoint or its cache.
- Images other than official artwork and the default sprite (shiny, back or animated sprites).
- Frontend or UI work. This is an API-only feature.

## Possible Edge Cases
- A Pokemon has no evolutions (for example Tauros). The chain has a single stage.
- A chain branches (for example Eevee, or Wurmple's split). All branches are returned.
- A Pokemon evolves through a non-level condition: an item, trade, friendship, time of day, or a
  combination. The conditions are described without failing on unfamiliar ones.
- The requested Pokemon is an alternate form (for example "deoxys-attack" or "charizard-mega-x"). Its species
  differs from its name, so the description and chain come from the linked species.
- A Pokemon has no official artwork. The image falls back to the default sprite, or is empty if neither
  exists.
- A species has no English flavor text. The description is empty and the request doesn't fail.
- The flavor text contains form feeds, soft hyphens or line breaks that need cleaning into readable text.
- The Pokemon request succeeds but the species or evolution chain call fails. The whole request fails with
  the usual error and nothing is cached.
- Name and id requests for the same Pokemon, for example "pikachu" and "25", happen concurrently or one after
  the other.
- The input has mixed case or surrounding whitespace, as already handled by the existing lookup.

## Acceptance Criteria
- Requesting the detailed view of "pikachu" or 25 returns:
  - id 25, name "pikachu" and an official artwork image URL
  - six base stats with HP 35, Attack 55, Defense 40, Special Attack 50, Special Defense 50, Speed 90, and a
    total of 320
  - a non-empty English description without line breaks or control characters
  - the chain Pichu → Pikachu → Raichu, with Pikachu marked as the current stage and the evolution conditions
    of each step
- Requesting the detailed view of Eevee returns a chain with all of its branches.
- Requesting a Pokemon with no evolutions returns a single-stage chain.
- Requesting an unknown Pokemon returns 404 with a Problem Details body. Requesting an invalid name returns
  400.
- Requesting the same Pokemon twice makes no PokeAPI calls the second time.
- Requesting two members of the same family only fetches the evolution chain once.
- With PokeAPI down, a previously cached detailed view is still returned successfully. An uncached one follows
  the existing 502, 503 or 429 behaviour.
- The endpoint appears in Swagger UI with its parameters, response schema and error responses documented.
- All existing tests still pass.

## Open Questions
- **Route**: should the detailed view be a new route (for example `/api/v1/pokemon/{nameOrId}/details`), or
  should it replace the response of the existing `GET /api/v1/pokemon/{nameOrId}`? The existing lookup also
  stores Pokemon in PostgreSQL. Suggested default: a new route, leaving the existing lookup unchanged. Replace
  the response of the existing `GET /api/v1/pokemon/{nameOrId}`. The lookup keeps storing the basic Pokemon
  in PostgreSQL (confirmed in conversation).
- **Image**: official artwork with the sprite as a fallback, or only the default sprite (matching the
  catalogue list)? Suggested default: official artwork with a sprite fallback. official artwork with a sprite
  fallback
- **Description choice**: PokeAPI has many English flavor texts, one per game version. Which one should be
  used? Suggested default: the most recent game version. the most recent game version
- **Evolution conditions**: are a short summary (trigger plus the main condition, such as level or item) and
  the raw condition fields both needed, or is the summary enough? Suggested default: the summary only. the summary only
- **Lineage detail**: should each chain stage also include its image, or only its name and id? Including
  images needs one extra PokeAPI call per stage. Suggested default: name and id only. name and id only
- **Persistence**: should the detailed view also be stored in PostgreSQL, like the existing lookup, or only
  cached in Redis? Suggested default: Redis only. Redis only

## Testing Guidelines
Create test file(s) for the new feature and cover the following cases with meaningful tests, without going too heavy:
- Unit tests (Mockito, no Docker) in `src/test/java`
  - Mapping: stats order and total, artwork fallback to sprite, English description selection and
    cleaning, missing description.
  - Evolution chain mapping: linear chain, branching chain, single-stage chain, evolution condition
    summaries (level, item, trade or friendship), and marking the current stage.
  - Service: assembles the view from the Pokemon, species and chain calls; uses the linked species for
    alternate forms; reuses a cached chain; fails the whole request if any upstream call fails.
  - Controller: successful response shape, 400 for invalid names, 404, and mapping of 502, 503 and 429.
  - PokeAPI client: parsing of species and evolution chain responses, including not-found and server-error
    handling.
- Integration tests (Testcontainers) in `src/integrationTest/java`
  - With WireMock stubs for a Pokemon, its species and its evolution chain: the detailed view has the
    expected image, stats, description and lineage.
  - A second request for the same Pokemon is served from Redis without new PokeAPI requests (verified through
    WireMock request counts).
  - Two members of the same family share one evolution chain request.
  - A cached detailed view is still served while the circuit is open.
  - Unknown and invalid Pokemon behave as specified.
  - The OpenAPI document includes the new endpoint with its parameters and responses.
