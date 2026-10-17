# Plan: API to View Detailed Pokemon Data

- Spec: `_specs/api-pokemon-details.md`
- Branch: `claude/feature/api-pokemon-details-01`

## Decisions

From the spec's answered Open Questions, plus one point confirmed in conversation:

| Topic | Decision |
|---|---|
| Route | **Replace** the response of the existing `GET /api/v1/pokemon/{nameOrId}` with the detailed view. This is a breaking change. |
| Image | **Official artwork, falling back to the default sprite.** It's null only if neither exists. |
| Description | English flavor text from the **most recent game version**. |
| Evolution conditions | **Summary only**, for example "level 16", "use thunder-stone", "trade holding metal-coat". |
| Lineage detail | **Name and id only** per stage, so there are no extra PokeAPI calls per stage. |
| Persistence | **Redis only for the detailed view.** Stats, description and evolution are cached, never stored. |
| PostgreSQL (confirmed in conversation) | **Keep storing** the basic Pokemon row (id, name, height, weight, base experience, sprite, types) the first time a Pokemon is looked up. No schema change. |

**Effects of these decisions:**
- **Breaking change for API clients.** The lookup response used to contain `id`, `name`, `height`, `weight`,
  `baseExperience`, `spriteUrl` and `types`. It now contains `id`, `name`, `imageUrl`, `stats`, `totalStats`,
  `description` and `evolution`. `height`, `weight`, `baseExperience`, `spriteUrl` and `types` are dropped,
  because the spec lists only image, stats, description and lineage. Adding any of them back costs nothing,
  since they come from the same PokeAPI response. If you want some kept, decide before implementation.
- **PostgreSQL is written to but not read.** The detail view needs stats, artwork and the species link, and
  none of these are in the database. So a cache miss always calls PokeAPI, and the stored row is no longer
  used as a fallback. Each looked-up Pokemon is still recorded: the row is inserted the first time and skipped
  if it already exists.

**Spec follow-up (step 0):**
- Change the "Provide an endpoint" requirement and the README requirement to say the detailed view replaces
  the existing lookup's response. Note the breaking change, and state that the basic Pokemon is still stored
  in PostgreSQL.
- Fix the Route answer: a line break split the bullet, so part of the answer shows as a separate list item.

## Response shape

`GET /api/v1/pokemon/{nameOrId}` (same path and validation as today) returns:

- `id`, `name`
- `imageUrl`: the official artwork URL, else the default sprite URL, else null
- `stats`: a list of `{name, value}` in PokeAPI order (hp, attack, defense, special-attack, special-defense,
  speed)
- `totalStats`: the sum of the six values
- `description`: cleaned English flavor text, or null
- `evolution`: the root stage of the chain. Each stage has:
  - `id` and `name` (species)
  - `evolvesVia`: the condition summary. It's null for the root.
  - `current`: true for the requested Pokemon's species
  - `evolvesTo`: a list of child stages, empty for final forms

## Design

### Request flow

On a cache miss, these steps run in order, because each needs the previous result:

1. `GET /pokemon/{nameOrId}` gets the id, name, artwork, sprite, stats, height, weight, types, and a link to
   the species. A 404 becomes `PokemonNotFoundException`, so the endpoint returns 404, as today.
2. **Record in PostgreSQL**: map the Pokemon to the existing `Pokemon` entity and save it if no row with that
   PokeAPI id exists. A unique-constraint conflict from a concurrent first lookup is ignored, because the
   other request already stored it.
3. `GET /pokemon-species/{speciesId}` gets the flavor texts and a link to the evolution chain. The species id
   is parsed from the species URL. This makes alternate forms work, because "deoxys-attack" links to the
   species "deoxys".
4. `GET /evolution-chain/{chainId}` gets the chain tree. **This call is skipped when the chain is cached.**

All three client methods have the existing `@Retry`, `@CircuitBreaker` and `@RateLimiter("pokeapi")`
annotations. A 404 on the species or chain means PokeAPI's data is inconsistent, so it's treated as a
`PokeApiException` (502). If any PokeAPI call fails, the whole request fails, and nothing is cached because
exceptions are never cached. A row saved in step 2 stays, which is harmless because it's correct basic data.

Like today, the lookup isn't `@Transactional`, so HTTP calls never hold a database connection. Only the save
runs in its own short transaction.

An uncached lookup now costs **up to 3 PokeAPI calls instead of 1**. Before, a stored Pokemon cost none, even
when its cache entry had expired. That's still well within the 20 calls per second rate limit.

### Caching

| Cache | Key | Value | Why |
|---|---|---|---|
| `pokemon-details` | normalized input (trimmed, lowercase), the same key rule as today's lookup | The full detail view | A repeat request costs no PokeAPI calls. |
| `evolution-chain` | chain id | The chain tree, **without** `current` flags | Every member of a family reuses one cached chain. |

- **The old `pokemon` cache is retired, not reused.** Reusing the name with a new value type would make old
  Redis entries fail to deserialize after deployment, until their TTL runs out. A new cache name avoids that.
  Old `pokemon::*` keys simply expire.
- The `current` flag depends on the requested Pokemon, so it's applied when the view is assembled. It's never
  stored in the shared chain cache.
- "pikachu" and "25" are cached as separate entries, as today. Sharing them fully would need a name-to-id
  lookup before every cache check, which costs a call on each miss. The chain cache still saves the most
  expensive part. Note this trade-off in the README.
- `sync = true` on both caches. Both use the shared `app.cache.pokemon-ttl`.
- The chain cache lives in a separate `EvolutionChainLoader` bean, so `@Cacheable` goes through the proxy. A
  self-call would bypass it (the same reason as `PokemonSummaryLoader`).

### Mapping rules

- **Image**: official artwork if present, otherwise the default front sprite, otherwise null.
- **Stats**: keep PokeAPI's order and sum them for `totalStats`.
- **Description**: from English entries only, pick the one whose game version has the highest id, parsed from
  the version URL. Then clean it:
  - replace form feeds, carriage returns and newlines with spaces
  - drop soft hyphens, joining the split word
  - collapse repeated whitespace and trim
  - if there's no English entry, return null
- **Evolution summary**: built from each `evolution_details` entry:
  - `level-up` with a minimum level gives "level N"
  - `level-up` with happiness gives "friendship", plus " (day)" or " (night)" when a time of day is set
  - `use-item` gives "use {item}"
  - `trade` gives "trade", plus " holding {item}" when a held item is set
  - any other trigger gives its name, so unfamiliar triggers never fail
  - distinct summaries are joined with " or ", because some species evolve differently across games
- **Chain tree**: walk `chain.evolves_to` recursively, so branches are kept in PokeAPI order. Species ids come
  from the species URLs.

### Shared helpers

- Extract the "id from PokeAPI URL" logic, now in `PokeApiPokemonList.Entry#id`, into a small shared helper,
  and reuse it for species, chain and version ids.
- Extract the client's repeated GET-and-error-mapping code into one private helper, so the new methods don't
  repeat it.

## Implementation steps

0. **Spec alignment**: apply the spec fixes listed above.
1. **PokeAPI DTOs and client**
   - Extend `PokeApiPokemon` with `stats`, `species`, and `sprites.other.official-artwork.front_default`.
   - Add a `PokeApiSpecies` DTO (flavor text entries with language and version, and the evolution chain link).
   - Add a `PokeApiEvolutionChain` DTO (a recursive chain link with species, evolution details and
     `evolves_to`).
   - Add the URL id helper.
   - Add `PokeApiClient` methods `fetchSpecies(id)` and `fetchEvolutionChain(id)` with the resilience
     annotations, plus the shared GET helper.
2. **Response records** (`web/dto`): `PokemonDetails`, `Stat`, and a recursive `EvolutionStage`.
3. **Mapping**: a new `PokemonDetailsMapper`. It handles the image fallback, stats and total, description
   selection and cleaning, the evolution summary, the chain tree, and marking the current stage.
4. **Caching config**: in `CacheConfig`, replace the `pokemon` cache with `pokemon-details`
   (`PokemonDetails`) and add `evolution-chain` (`EvolutionStage`), both with typed JSON serializers.
5. **Services**
   - `EvolutionChainLoader`: cached by chain id. It fetches the chain and maps it to an `EvolutionStage` tree
     without `current` flags.
   - `PokemonDetailsService`: cached by normalized input. It fetches the Pokemon, records it in PostgreSQL
     (insert if missing), fetches the species, gets the chain from the loader, marks the current species, and
     builds the view. It replaces `PokemonService`.
6. **Web layer**: `GET /{nameOrId}` in `PokemonController` now returns `PokemonDetails` from
   `PokemonDetailsService`. Keep the same `@Pattern` validation. Update the OpenAPI summary, description and
   responses (200, 400, 404, 429, 502, 503). No new exception handlers are needed.
7. **Retire the old lookup code**
   - Delete `PokemonService`, which moves into `PokemonDetailsService`, and `PokemonResponse`, which is
     replaced by `PokemonDetails`.
   - Delete `PokemonMapper#toResponse`. Keep `toEntity` for recording in PostgreSQL, and `toSummary` for the
     catalogue.
   - Keep the `Pokemon` entity, `PokemonRepository`, the `V1` migration and all database setup unchanged.
     `findByPokeApiId` is used for the insert-if-missing check.
8. **Unit tests** (`src/test/java`)
   - `PokemonDetailsMapperTest`:
     - image from artwork, then sprite fallback, then null
     - stats order and total
     - most recent English description chosen and cleaned
     - no English description
     - evolution summaries for level, item, trade with item, friendship with time of day, an unknown
       trigger, and several summaries joined
     - linear, branching (Eevee-shaped) and single-stage chains
     - current stage marked
   - `PokemonDetailsServiceTest`:
     - builds the view from the three sources
     - uses the species from the Pokemon response, for alternate forms
     - normalizes input
     - saves a new Pokemon, skips saving an existing one, and ignores a concurrent duplicate-key conflict
     - propagates 404 from the Pokemon call
     - fails the whole request when the species or chain call fails
   - `EvolutionChainLoaderTest`: maps the client result and propagates errors.
   - `PokeApiClientTest`: parses fixtures for species and chain, and wraps server errors.
   - `PokemonControllerTest`: rewrite the lookup tests for the new response shape. Keep 400 for an invalid
     name, and mapping for 404, 429, 502 and 503.
   - `PokemonMapperTest`: remove the `toResponse` test. Keep the `toEntity` and `toSummary` tests.
   - Delete `PokemonServiceTest`. Its storage cases move into `PokemonDetailsServiceTest`.
   - Update tests that build `PokeApiPokemon` directly, because it has a new constructor.
9. **Integration tests** (`src/integrationTest/java`)
   - WireMock stubs:
     - extend `pikachu.json` with stats, species and artwork
     - add `pichu` (172) Pokemon and species
     - add `pikachu` species (25)
     - add evolution chain 10 (pichu → pikachu → raichu, with a `use-item` thunder-stone step)
     - add bulbasaur species and chain, because existing tests look up `/1`
     - add a species stub that returns 503 for a failure case
   - Rewrite `PokemonApiIntegrationTest` as the details test:
     - the Pikachu view has the artwork, stats (total 320), cleaned description, and the chain with Pikachu
       as current
     - a repeat request makes no new WireMock requests
     - Pichu then Pikachu makes only one evolution-chain request
     - a cached view is served while the circuit is open
     - unknown gives 404, invalid gives 400
     - a failing species call gives 502 and nothing is cached
     - actuator health is up
     - the looked-up Pokemon is stored in PostgreSQL, and looking up "pikachu" then "25" leaves one row
     - Replace the old database-fallback test: an expired cache entry now refetches from PokeAPI.
   - `PokemonRepositoryIntegrationTest` is unchanged.
   - `AbstractApiIntegrationTest` is unchanged and still clears the database between tests.
   - **`PokeApiRateLimiterIntegrationTest`**: a lookup now costs 3 calls, so the 2-call quota fails
     immediately. Raise the quota, for example to 6, so the first two lookups (Pikachu with 3 calls,
     Bulbasaur with 3 calls) use it up exactly, and the next upstream call still gets 429.
   - `PokeApiResilienceIntegrationTest`: check that the retry and circuit counts still hold. The first call
     (`/pokemon/glitch`) is the one that fails, so they should. `openCircuitDoesNotAffectCachedPokemon` needs
     the Pikachu species and chain stubs.
   - `OpenApiIntegrationTest`: check the updated lookup operation and its responses.
10. **README**
    - Rewrite the "Single Pokémon lookup" architecture and API section for the detailed view: flow, example
      response, field table, evolution summary examples.
    - Add a breaking-change note listing the dropped fields.
    - Replace the `pokemon` cache with the two new caches, and note the name/id trade-off.
    - Database section: the lookup still records basic Pokemon data, but the detail view is always built from
      PokeAPI and Redis. The database is no longer a read fallback.
    - Update the testing tables.
    - Add an AI-assisted development entry for User Story 02 (spec, plan, implementation).
11. **Verify**
    - `./gradlew build` passes all tests.
    - Smoke test with Docker Compose against the real PokeAPI:
      - pikachu (linear chain)
      - eevee (branching)
      - tauros (single stage)
      - deoxys-attack (alternate form, species "deoxys")
      - an unknown name (404)
      - a repeat request (served from cache)
      - a row in PostgreSQL for each Pokemon looked up

## Files

| Action | File |
|---|---|
| Modify | `client/PokeApiClient.java`, `client/dto/PokeApiPokemon.java`, `client/dto/PokeApiPokemonList.java` (use the shared id helper), `config/CacheConfig.java`, `mapper/PokemonMapper.java`, `web/PokemonController.java`, `README.md`, `_specs/api-pokemon-details.md` |
| Add | `client/dto/PokeApiSpecies.java`, `client/dto/PokeApiEvolutionChain.java`, `client/PokeApiUrls.java` (id helper), `mapper/PokemonDetailsMapper.java`, `service/PokemonDetailsService.java`, `service/EvolutionChainLoader.java`, `web/dto/PokemonDetails.java`, `web/dto/Stat.java`, `web/dto/EvolutionStage.java` |
| Delete | `service/PokemonService.java`, `web/dto/PokemonResponse.java` |
| Unchanged | `domain/Pokemon.java`, `repository/PokemonRepository.java`, `db/migration/V1__create_pokemon_tables.sql` |
| Tests | New: `PokemonDetailsMapperTest`, `PokemonDetailsServiceTest`, `EvolutionChainLoaderTest`, species and chain fixtures and WireMock stubs. Rewritten: `PokemonApiIntegrationTest`, `PokemonControllerTest` (lookup tests). Updated: `PokeApiClientTest`, `PokemonMapperTest`, `PokemonSummaryLoaderTest`, `PokemonCatalogServiceTest` (if affected), `PokeApiRateLimiterIntegrationTest`, `OpenApiIntegrationTest`. Deleted: `PokemonServiceTest` |

## Risks

- **Breaking API change**: existing clients of `GET /api/v1/pokemon/{nameOrId}` lose `height`, `weight`,
  `baseExperience`, `spriteUrl` and `types`. Accepted per the Route decision. Call it out in the README and
  the commit.
- **The database no longer serves reads**: the lookup writes basic Pokemon data but never reads it back for
  the response. The stored rows remain a record of what users looked up. To make the database serve reads
  again, a later `V2` migration could add stats, artwork and species id columns. That moves toward storing
  the detailed view, which the Persistence answer rules out, so it isn't planned.
- **More upstream calls per uncached lookup** (3 instead of 1, and an expired cache entry is no longer answered
  from the database): the evolution chain cache and the view cache reduce this in practice. Tests that depend
  on rate-limiter quotas need adjusting (step 9).
- **`PokeApiPokemon` constructor change**: several tests that build it directly need mechanical updates.
- **Evolution condition variety**: rare conditions fall back to the trigger name, so nothing fails, but some
  summaries will be vague.
- **"Most recent version" relies on version ids increasing over time**. This holds for PokeAPI today.
