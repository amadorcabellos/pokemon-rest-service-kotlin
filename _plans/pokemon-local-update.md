# Plan: Update Locally Stored Pokemon With Robust Validation

- Spec: `_specs/pokemon-local-update.md`
- Branch: `claude/feature/pokemon-local-update-01`

## Context

User Story 04. User Story 03 replicated the PokeAPI catalogue into PostgreSQL and added tables for proprietary
data: `pokemon_localized_name`, `pokemon_geo_metadata` and `pokemon_tag`. Nothing can change them through the
API yet. This story adds update operations for them, with strict validation and safe errors, all on the
existing resource `/api/v1/pokemon/{nameOrId}`. Decisions from the spec:

| Topic | Decision |
|---|---|
| What can be updated | Proprietary data only. Upstream fields are read only and rejected with 400. |
| Operations | `PUT` replaces all proprietary data. `PATCH` follows JSON Merge Patch for each kind of data. Both return the updated proprietary data and its new version. |
| `null` in a PATCH | Clears that kind of data. |
| Reading | Only the existing `GET /api/v1/pokemon/{nameOrId}`, extended with the proprietary data. No `/local` endpoint. |
| Version storage | Columns on the existing `pokemon` table. No new table. |
| Concurrency | `If-Match` is optional. A stale version gives 412, and no version means last write wins. |
| Limits | 50 localized names, 20 geo entries, 30 tags; name 100, geo value 100, note 1,000, tag 50 characters; body 64 KB. |
| Duplicate geo entries | **Allowed**: two entries with the same kind and value are stored as separate entries. |
| Locales | Any well-formed BCP 47 tag, stored in canonical form. |
| Security | No authentication; it stays a follow-up. |

## Design

### Data model (`V3__pokemon_local_data.sql`)

- On `pokemon`:
  - `local_data_version BIGINT NOT NULL DEFAULT 0`. Existing rows get 0.
  - `local_data_modified_at TIMESTAMPTZ`, null until the first update that changes something.
- Database rules as a last line of defence:
  - `CHECK (char_length(note) <= 1000)` on `pokemon_geo_metadata.note`
  - V2 already enforces unique locales, lowercase tags and allowed kinds.
  - There is no unique index on geo entries, because duplicates are allowed.

**Keeping the sync away from the version.** The sync (`PokemonSyncWriter`) loads a `Pokemon` entity, copies
the upstream fields and saves it, and Hibernate writes every updatable column. If the new columns were normal
mappings, a sync that read version 3 could save it back over a version 4 that an update had just committed. So:

- On `Pokemon`, map the two columns read only (`insertable = false, updatable = false`), with getters only.
  Inserts by the sync and the lookup get the database default of 0. Their updates never include the columns.
- They are written only by `PokemonRepository#bumpLocalDataVersion(id, now)`: a `@Modifying` native
  `UPDATE pokemon SET local_data_version = local_data_version + 1, local_data_modified_at = :now WHERE id = :id`.
  It doesn't touch `updated_at`, which keeps meaning "upstream data changed".
- No JPA `@Version`, because the sync would bump it.
- `PokemonSyncWriter`, `PokemonMapper` and the lookup's `recordIfNew` don't change.

### Locking

- `PokemonRepository#lockLocalDataVersion(id)` is a native
  `SELECT local_data_version FROM pokemon WHERE id = :id FOR NO KEY UPDATE`. It returns only the current
  version and doesn't load the entity.
- `NO KEY UPDATE` serializes concurrent updates of the same Pokemon with each other.
- A sync of the same Pokemon waits a few milliseconds for the lock, then runs. Nothing fails or is lost.
- No deadlock is possible: the update transaction locks only that `pokemon` row plus its own proprietary rows,
  while the sync's transaction touches the `pokemon` row and the upstream element collections.

### Domain and repositories

- `PokemonLocalizedName#rename(String)` allows in-place updates. Tags and geo entries stay immutable; a
  change is a delete plus an insert.
- Local lookup by path input, used by updates, sharing the lookup's rules:
  - trim and lowercase the input
  - digits only: `findByPokeApiId`, but a value that doesn't fit an `int` is a 404, never a
    `NumberFormatException`
  - anything else: `findByNameIgnoreCase`
  - never calls PokeAPI
- `PokemonRepository` also gets a light query for the read side: `findIdAndLocalDataByPokeApiId`, a projection
  of id, version and modified time.

### Request model and validation (new package `com.ballastlane.pokemon.localdata`)

- Request records in `web/dto`:
  - `LocalDataReplaceRequest(localizedNames, geoMetadata, tags)` for PUT
  - entries `LocalizedNameInput(locale, name)` and `GeoMetadataInput(kind, value, note)`, and tags as plain
    strings
  - All leaf values are typed as `String`, so a bad kind or locale becomes a field error, not a Jackson error.
  - Each record has a `@JsonAnySetter` map that catches unknown properties. The global `ObjectMapper` keeps
    ignoring unknown fields, because the PokeAPI DTOs rely on that.
- **PATCH** reads the body as a JSON object node, so a missing kind and an explicit `null` can be told apart:
  - absent: keep that kind of data
  - `null`: clear it (an empty list)
  - an array: convert it with the same entry records
  - any other type: a field error
- Both PUT and PATCH become one command: `LocalDataUpdate(Optional<List<…>>` per kind, where empty means
  "keep").
- **`LocalDataValidator`** checks both operations in one place and collects every problem as a
  `FieldProblem(field, message)` with paths such as `tags[2]` or `geoMetadata[0].kind`:
  - `jakarta.validation.Validator` with `@NotNull`, `@NotBlank`, `@Size` (list maxima and string lengths)
  - unknown properties. Detailed view and upstream names (`id`, `name`, `imageUrl`, `stats`, `totalStats`,
    `description`, `evolution`, `types`, `abilities`, `height`, `weight`, `species`, `localData`, `version`,
    `modifiedAt`) get "is read only"; anything else gets "is not a known field".
  - PUT: each of the three kinds is required (`null` and absent both give "is required")
  - `null` entries inside lists
  - control characters are rejected in all text, except tab and newline in `note`
  - locales are parsed with `Locale.Builder#setLanguageTag` and stored in canonical form with
    `toLanguageTag()`
  - kinds are matched case-insensitively against `GeoKind`
  - tags must match `^[a-z0-9_-]{1,50}$` after trimming and lowercasing (ASCII on purpose)
  - duplicates after normalisation: locales and tags. Duplicate geo entries are accepted.
  - Any problem throws `InvalidLocalDataException(List<FieldProblem>)`.
- Constants for the limits live in `LocalDataLimits`, used by the annotations and the README.

### Service

**`LocalDataService`**:

- **`read(pokeApiId)`** (read-only transaction): returns a `LocalData` view.
  - It loads the proprietary rows and the version and modified time from the projection.
  - If no row exists, it returns empty lists and version 0. This only happens in a race, because the detailed
    view records the Pokemon first.
- **`update(nameOrId, LocalDataUpdate, Optional<IfMatch>)`** (`@Transactional`):
  1. find the Pokemon locally (404, with "is not stored locally"). No PokeAPI call.
  2. `lockLocalDataVersion(id)`
  3. if an `If-Match` was sent and doesn't match the current version, throw `LocalDataVersionMismatchException`
     (412) before writing anything
  4. for each kind that is present, compare the current rows with the request and write only what changed:
     - **Localized names** (keyed by locale): rename in place, delete removed locales, insert new ones.
     - **Tags** (keyed by tag): delete removed tags, insert new ones.
     - Deletes are flushed before inserts, so swapping values never hits a unique constraint. Hibernate's
       default flush order would otherwise run inserts first.
     - **Geo entries**: they have no natural key, because duplicates are allowed. Compare them as an unordered
       collection of `(kind, value, note)`. If equal, do nothing. Otherwise delete all geo rows of the Pokemon
       and insert the requested ones. There's no unique constraint, so order doesn't matter.
  5. if anything changed, call `bumpLocalDataVersion(id, now)` and return version + 1. Otherwise keep the
     version and modified time, so the update is idempotent.
  6. log the Pokemon and which kinds changed at INFO, without the payload
  - Validation runs before the transaction does any writes, and the whole update is one transaction.
- Lists in `LocalData` are sorted so responses are deterministic: names by locale, geo entries by kind then
  value, tags alphabetically.

### Detailed view: `GET /api/v1/pokemon/{nameOrId}`

- `PokemonDetails` gains one last component: `LocalData localData`, which is
  `{localizedNames[{locale, name}], geoMetadata[{kind, value, note}], tags[], modifiedAt, version}`. The kind
  is shown in lowercase.
- `PokemonDetailsService.getPokemon` stays `@Cacheable` and still builds the value with `localData = null`, so
  **proprietary data is never cached**. Existing Redis entries without the field still deserialize: the
  record component is simply null. The cache key, TTL and serializer don't change.
- The controller calls `getPokemon` (cached), then `localDataService.read(details.id())`. That is always a fresh
  database read. It returns `details.withLocalData(localData)` and sets `ETag: "<version>"`.
  - An update therefore shows on the next GET straight away, even when the rest comes from Redis.
  - It adds one small indexed read; no extra PokeAPI calls.
- Every existing field, error response and caching behaviour stays the same. `If-None-Match` is not supported
  (there are no 304s), because the ETag only versions `localData`, not the PokeAPI part of the view. This is
  documented.

### Web layer

PUT and PATCH are added to the existing `PokemonController` (`/api/v1/pokemon`), so the resource lives in one
controller. They use the same `@Pattern` on `{nameOrId}`, and OpenAPI annotations in the existing style with
example request bodies.

| Method and path | Request | Result |
|---|---|---|
| `GET /{nameOrId}` | unchanged | 200 `PokemonDetails` with `localData`, and an `ETag`. Error codes unchanged. |
| `PUT /{nameOrId}` | `application/json`, `LocalDataReplaceRequest`, optional `If-Match` | 200 `LocalData` and the new `ETag`. 400, 404, 412, 413, 415. |
| `PATCH /{nameOrId}` | `application/merge-patch+json` or `application/json`, optional `If-Match` | Same as PUT. |

- An unsupported method on `/{nameOrId}` (for example DELETE or POST) gives 405 with `Allow: GET, PUT, PATCH`.
- **If-Match**: a list of strong entity tags, or `*`, which matches any stored Pokemon. Weak tags never match,
  as RFC 9110 requires, so they give 412. A header that can't be parsed gives 400.
- **Request size**: `LocalDataRequestSizeFilter` for PUT and PATCH on `/api/v1/pokemon/*`:
  - `Content-Length` over `app.local-data.max-request-size` (default 64KB, property `LOCAL_DATA_MAX_REQUEST_SIZE`)
    gives 413 straight away
  - chunked bodies go through a counting stream that fails once over the limit, which also gives 413
  - Configured through a small `LocalDataProperties` record.
- **Strict JSON input**: a `JsonMapperBuilderCustomizer` (Jackson 3) on the MVC mapper enables:
  - strict duplicate key detection
  - fail on trailing tokens
  - no coercion of numbers or booleans into strings
  The Redis cache uses its own `JacksonJsonRedisSerializer`, so it isn't affected. The PokeAPI `RestClient`
  messages are covered by the existing tests.
- **`GlobalExceptionHandler`**, with new handlers that all return Problem Details with no internals:

  | Exception | Response |
  |---|---|
  | `InvalidLocalDataException` | 400 "Invalid request", with an `errors` property listing `{field, message}` |
  | `HttpMessageNotReadableException` | 400 "Malformed request body". The detail is generic, or "'tags' has the wrong type" from the Jackson path. It never echoes Jackson's message. The size-limit cause maps to 413. |
  | `HttpMediaTypeNotSupportedException` | 415 |
  | `HttpRequestMethodNotSupportedException` | 405 |
  | `LocalDataVersionMismatchException` | 412, with a `currentVersion` property |
  | `InvalidIfMatchException` | 400 |
  | `DataIntegrityViolationException` | 409 "Conflicting change", generic detail, logged at WARN |
  | `CannotCreateTransactionException` / `DataAccessResourceFailureException` | 503 |

  The existing `HandlerMethodValidationException` handler also gets the `errors` list, keeping its current
  `detail` so existing tests keep passing. `PokemonNotFoundException` is reused for the local 404.

## Implementation steps

0. Save this plan as `_plans/pokemon-local-update.md`.
1. Migration `V3__pokemon_local_data.sql`.
2. Domain and repositories:
   - the read-only version columns on `Pokemon`
   - `lockLocalDataVersion`, `bumpLocalDataVersion` and the local-data projection on `PokemonRepository`
   - the in-place mutator on `PokemonLocalizedName`
   - the local lookup helper
3. Request records, `LocalData` and its entry records, `LocalDataLimits`, `LocalDataValidator`, `FieldProblem`,
   and the exceptions `InvalidLocalDataException`, `LocalDataVersionMismatchException` and
   `InvalidIfMatchException`.
4. `LocalDataService` with the diff-based update, locking and versioning.
5. `PokemonDetails.localData` and `withLocalData`, and the GET change in `PokemonController` (with the `ETag`).
6. Web:
   - PUT and PATCH in `PokemonController`
   - `LocalDataRequestSizeFilter` and `LocalDataProperties`, with `app.local-data.*` in `application.yml`
   - the Jackson strictness customizer
   - the new exception handlers
7. Unit tests (`src/test/java`, Mockito, no Docker):
   - `LocalDataValidatorTest`:
     - valid input is normalized (canonical locales, lowercase tags and kinds)
     - each rule gives the right field path: invalid locale, duplicate locale, unknown kind,
       blank and too long values, a bad tag charset, a duplicate tag after normalisation, list maxima, `null`
       entries, control characters
     - duplicate geo entries are accepted
     - unknown fields and read-only fields
   - `LocalDataServiceTest`:
     - PUT replaces, and empty lists clear
     - geo entries with the same kind and value are both stored; the same geo list in a different order is a
       no-op
     - PATCH keeps omitted kinds, and `null` clears
     - 404 without any PokeAPI interaction
     - a stale `If-Match` gives 412 with nothing written
     - a no-op update doesn't call `bumpLocalDataVersion`
     - a numeric path that is too large gives 404
     - `read` returns sorted lists, and version 0 when there's no data
   - `PokemonControllerTest`:
     - existing GET tests still pass. Additions: `localData` is in the body and the `ETag` header is set.
     - PUT and PATCH: 200 with `ETag`, 404, 412
     - 400 for malformed JSON, trailing tokens, duplicate keys, a top-level array, wrong types, an unknown and a
       read-only field, an invalid path, and an unparsable `If-Match`
     - 415, 405 with `Allow`, and 413 for an oversized body
     - the `errors` property in Problem Details
   - `PokemonDetailsServiceTest`: the cached value has `localData = null`.
   - `PokemonTest`: `copyUpstreamFrom` leaves the version fields alone.
8. Integration tests (`src/integrationTest/java`):
   - New `LocalDataIntegrationTest`:
     - PUT, then `GET /api/v1/pokemon/{nameOrId}` shows the data and the version in `localData` and in the
       `ETag`
     - a GET that fills the Redis cache, then a PUT, then a GET: the new data shows while the rest is served
       from the cache (WireMock receives no new PokeAPI calls)
     - PATCH with only tags keeps the other kinds; PATCH with `null` clears
     - a PUT with two identical geo entries stores and returns both
     - invalid requests leave rows and version unchanged
     - updating a Pokemon stubbed only in WireMock (never stored) gives 404, and WireMock receives no request
       for it; a GET of it, then the update, succeeds
     - after a single sync and a full sync, the proprietary data **and `local_data_version`** are unchanged
       (this checks the read-only mapping)
     - two concurrent PUTs with the same `If-Match`: exactly one 200 and one 412
     - many concurrent PUTs without `If-Match`, alongside a single sync of the same Pokemon: all succeed with
       no 409 or 500, and the version equals the number of updates that changed something
   - `PokemonApiIntegrationTest`: the detailed view's existing fields are unchanged, and `localData` is
     present and empty for a fresh Pokemon.
   - `PokemonRepositoryIntegrationTest`: V3 applies to existing rows (version 0), and two identical geo entries
     can be stored.
   - `OpenApiIntegrationTest`: PUT and PATCH on `/api/v1/pokemon/{nameOrId}`, the `localData` schema in the GET
     response, and the 400, 404, 412, 413 and 415 responses are documented.
   - Existing integration tests still pass with the stricter Jackson settings.
9. README:
   - the extended detailed view (`localData` and `ETag`)
   - a "Local data updates" section with PUT and PATCH examples and an `If-Match` example
   - a validation rules and limits table, and an error responses table
   - a note that the ETag only versions `localData`, and that `If-None-Match` isn't supported
   - `app.local-data.max-request-size` in the configuration table
   - V3 in "Database migrations", including why the version columns are read only in JPA
   - the testing tables and the project layout
   - an AI-assisted development entry for User Story 04
10. Verify (below).

## Files

| Action | File (under `src/main/java/com/ballastlane/pokemon/` unless noted) |
|---|---|
| Add | `src/main/resources/db/migration/V3__pokemon_local_data.sql` |
| Add | `localdata/LocalDataService.java`, `localdata/LocalDataValidator.java`, `localdata/LocalDataUpdate.java`, `localdata/LocalDataLimits.java`, `localdata/FieldProblem.java`, `localdata/IfMatch.java` |
| Add | `exception/InvalidLocalDataException.java`, `exception/LocalDataVersionMismatchException.java`, `exception/InvalidIfMatchException.java` |
| Add | `web/LocalDataRequestSizeFilter.java`, `web/dto/LocalData.java` (plus its entry records), `web/dto/LocalDataReplaceRequest.java`, `web/dto/LocalizedNameInput.java`, `web/dto/GeoMetadataInput.java` |
| Add | `config/LocalDataProperties.java`, `config/JacksonConfig.java` |
| Modify | `domain/Pokemon.java` (read-only version columns), `domain/PokemonLocalizedName.java`, `repository/PokemonRepository.java`, `web/PokemonController.java`, `web/dto/PokemonDetails.java`, `web/GlobalExceptionHandler.java`, `src/main/resources/application.yml`, `README.md` |
| Unchanged | sync package, `PokemonDetailsService` (apart from passing `null` for `localData`), `PokemonMapper`, caches, V1 and V2 migrations; no new table |
| Tests | New: `LocalDataValidatorTest`, `LocalDataServiceTest`, `LocalDataIntegrationTest`. Updated: `PokemonControllerTest`, `PokemonDetailsServiceTest`, `PokemonTest`, `PokemonApiIntegrationTest`, `PokemonRepositoryIntegrationTest`, `OpenApiIntegrationTest`. |

## Verification

- `./gradlew build` runs unit and integration tests, and all of them pass, including existing ones.
- Smoke test with `docker compose up` (the local DB already holds the full catalogue from US03):
  - `GET /api/v1/pokemon/pikachu` gives 200 with the existing fields, plus `localData` and an `ETag`
  - `PUT /api/v1/pokemon/pikachu` with names, geo and tags gives 200 and a new ETag
  - a GET shows the data straight away (the detailed view is still cached)
  - a PUT with the old ETag gives 412
  - PATCH `{"tags": null}` clears the tags
  - malformed JSON, an unknown field, `stats`, a bad locale and a duplicate tag each give 400 with `errors`
  - `text/plain` gives 415, a 100 KB body gives 413, and DELETE gives 405
  - updating a Pokemon not stored locally gives 404 (for example after deleting a row in psql)
  - `POST /api/v1/admin/sync/pokemon/pikachu`: `local_data_version` in psql and the GET are unchanged
  - Swagger UI shows PUT and PATCH, and the `localData` schema on GET

## Risks

- **Sync overwriting the version**: the read-only JPA mapping is what stops it, so a future change that makes
  those columns updatable would quietly bring the bug back. A comment on the mapping and the integration test
  that syncs after an update cover this.
- **Write operations on the public path are unauthenticated**: anyone who can reach the API can change
  proprietary data. This is accepted for now, and the README says so. Protecting it later needs a
  method-based rule (PUT and PATCH on `/api/v1/pokemon/**`).
- **Detailed view response changes**: it gains `localData` and an `ETag`. The change only adds fields, but
  strict clients that reject unknown fields would notice.
- **Cached type gains a field**: older Redis entries deserialize with `localData = null`, and the controller
  always fills it. Covered by the cache test.
- **Lock waits**: a sync of a Pokemon waits for an in-flight update of the same Pokemon (milliseconds). Only
  one Pokemon at a time is affected.
- **Stricter Jackson settings** on the MVC mapper could reject an unusual PokeAPI response, if the
  `RestClient` shares that mapper. Mitigation: run the full test suite; if anything breaks, apply them only to
  local-data request parsing.
- **Hibernate flush order** (inserts before deletes) can break unique constraints during a replace. This is
  handled by diffing and flushing deletes first, and covered by a test that swaps values.
