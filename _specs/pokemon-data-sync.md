# Spec for Synchronize Pokemon Data Into a Local Relational Store

branch: claude/feature/pokemon-data-sync

## Summary
User Story 03 – Data Synchronization.

As the service owner, I want Pokemon data from PokeAPI replicated into our own PostgreSQL database, so that we
can add proprietary fields that PokeAPI doesn't have and keep them alongside the upstream data. Example uses
are localized names, geographical metadata and internal classification tags.

Today the service only stores a basic Pokemon record (name, height, weight, base experience, sprite and types)
the first time it is looked up. Everything else is fetched live from PokeAPI and cached in Redis. This feature
adds a deliberate synchronization mechanism that copies the Pokemon catalogue into PostgreSQL, keeps it up to
date, and gives the schema a clear place for our own data that synchronization never overwrites.

Terminology used in this spec:
- **Upstream data**: fields that come from PokeAPI and are owned by it, for example name, stats, types and
  sprite. Synchronization creates and refreshes them.
- **Proprietary data**: fields owned by this service that PokeAPI doesn't provide. Synchronization never
  changes or deletes them.
- **Full sync**: synchronizing every Pokemon in the PokeAPI catalogue.
- **Single sync**: synchronizing one Pokemon, by name or id.
- **Sync run**: one execution of a full sync, with its own status and counts.

## Functional Requirements
- **Replicated data**: for each Pokemon, the local store holds the upstream data already stored today (PokeAPI
  id, name, height, weight, base experience, sprite, types) plus:
  - the six base stats
  - the abilities
  - the species it belongs to (species id and name)
  - when the record was last synchronized from PokeAPI
- **Proprietary data**: the local store has a place for each of the three example uses, linked to the
  Pokemon:
  - **Localized names**: zero or more names per Pokemon, one per language or locale.
  - **Geographical metadata**: zero or more geographical entries per Pokemon, for example a region, an area or
    a habitat, with an optional free-text note.
  - **Classification tags**: zero or more internal tags per Pokemon, unique per Pokemon.
  These start empty for newly synchronized Pokemon. Adding a new kind of proprietary field later must not
  require changing the upstream data.
- **Upstream and proprietary data are kept apart**: synchronization only writes upstream data. Proprietary
  data is never overwritten, cleared or deleted by a sync, including a re-sync of a Pokemon that already
  exists.
- **Full sync**: walks the whole PokeAPI catalogue page by page and creates or updates every Pokemon in the
  local store. Running it again is safe and doesn't create duplicates.
- **Single sync**: creates or updates one Pokemon by name or id, using the existing name and id rules.
- **Triggering**: an operator can start a full sync and a single sync through the API, under a dedicated
  admin path (for example `/api/v1/admin/sync`).
  - Starting a full sync returns straight away with an identifier for the sync run (202 Accepted). The sync
    itself runs in the background.
  - Only one full sync can run at a time. Starting another while one is running returns 409 Conflict with a
    Problem Details body that points to the running sync.
  - A single sync runs in the request and returns the synchronized Pokemon.
- **Sync status**: each full sync run is recorded with its start and end time, status (running, completed,
  completed with errors, failed), and the number of Pokemon created, updated and failed. An operator can read
  the status of a run and of the most recent run through the API.
- **Partial failures**: if some Pokemon fail during a full sync (for example a temporary PokeAPI error after
  retries), the sync carries on with the rest, records the failed names, and finishes as "completed with
  errors". Failed Pokemon keep their previous local data.
- **Existing behaviour stays the same**: the existing lookup (`GET /api/v1/pokemon/{nameOrId}`) keeps storing
  the basic Pokemon on first lookup, and must not overwrite or remove proprietary data. The catalogue list and
  detailed view responses don't change in this feature.
- **Upstream calls**: all PokeAPI calls made by synchronization go through the existing resilience layer
  (retry, circuit breaker, rate limiter). A full sync must stay within the configured rate limit and not
  starve normal API traffic.
- **Schema changes** are delivered as new Flyway migrations. Existing rows keep working after the migration,
  and their new upstream fields are filled in by the next sync.
- **Scheduled sync**: a full sync can also run on a configurable schedule. The schedule is disabled by
  default, and a scheduled run follows the same one-at-a-time rule as one started through the API. No sync
  runs on application startup.
- **Configuration**: sync settings (for example batch size and whether a scheduled sync is enabled and how
  often it runs) are configurable through the application configuration, with sensible defaults.
- The admin endpoints, their parameters, response schemas and error codes are documented in the OpenAPI spec
  and shown in Swagger UI.
- The README is updated to describe the local data model, the upstream/proprietary split, how to trigger and
  monitor a sync, and any new configuration.

## Non-Functional Requirements
- **Idempotency**: running a full or single sync any number of times leaves the store in the same state as
  running it once against the same upstream data.
- **Data integrity**: each Pokemon is written in its own transaction, so a failure part way through a sync
  never leaves a half-written Pokemon. Pokemon are uniquely identified by their PokeAPI id. Names are also
  unique.
- **Upstream load**: synchronizing one Pokemon takes as few PokeAPI requests as possible. A full sync of the
  current catalogue (about 1,300 Pokemon) completes in a reasonable time within the default rate limit, while
  leaving room for normal API traffic.
- **Resilience**: an open circuit breaker or exhausted rate limit pauses or slows the sync rather than marking
  the whole catalogue as failed. Restarting the application while a sync is running leaves no run stuck in
  "running" forever; it is marked as failed or interrupted on startup.
- **Observability**: sync start, progress and finish are logged with counts. Failures are logged with the
  Pokemon name and reason.
- **Security**: the admin endpoints are clearly separated from the public API so they can be protected later.
  Authentication itself is out of scope (see Open Questions).
- **Consistency**: follow the existing project conventions for package layout, naming, error handling,
  Problem Details responses, resilience and the split between unit and integration tests.

## Out of Scope
- Endpoints for creating, editing or deleting proprietary data (localized names, geographical metadata,
  tags). This feature provides the storage and guarantees it survives syncs; managing it is a follow-up
  story unless decided otherwise in Open Questions.
- Returning proprietary data in the existing public responses (lookup, catalogue list, detailed view).
- Replicating other PokeAPI resources beyond the fields listed above: moves, items, locations, evolution
  chains, species descriptions, games, etc.
- Serving the public endpoints from the local store instead of PokeAPI and Redis.
- Removing local Pokemon that disappear from PokeAPI. They are kept as they are.
- Authentication and authorization for the admin endpoints.
- Distributed locking across several application instances. A single instance is assumed.
- Frontend or UI work. This is an API-only feature.

## Possible Edge Cases
- A Pokemon already exists locally from a previous lookup, with only the basic fields. The sync fills in the
  rest without creating a duplicate.
- A Pokemon has proprietary data and is re-synchronized. The upstream fields are refreshed and the
  proprietary data is unchanged.
- A Pokemon's upstream data changed since the last sync (for example a stat or sprite URL). The local copy is
  updated to match.
- A Pokemon was renamed upstream but keeps its PokeAPI id. The local record is matched by id and its name
  updated, without breaking the name uniqueness rule.
- A Pokemon appears in the catalogue list but its own resource returns 404. It is recorded as a failure and
  the sync continues.
- PokeAPI becomes unavailable part way through a full sync, or the circuit breaker opens. The run finishes as
  "completed with errors" or "failed", and already synchronized Pokemon are kept.
- A full sync is started while another is running. The second request is rejected with 409.
- A single sync runs for a Pokemon while a full sync is updating the same Pokemon.
- The application restarts during a full sync.
- The catalogue grows between the start and the end of a full sync.
- Alternate forms (for example "deoxys-attack") are separate Pokemon linked to the same species.
- Pokemon with missing optional fields upstream (no sprite, no base experience) are stored with empty values.
- The single sync input has mixed case, surrounding whitespace, or is invalid or unknown (400 or 404, as for
  the existing lookup).

## Acceptance Criteria
- Running a full sync against a stubbed catalogue stores every Pokemon in the catalogue with its upstream
  fields, stats, abilities, species and last synchronized time.
- Running the same full sync a second time creates no new rows and reports every Pokemon as updated (or
  unchanged), with no failures.
- A Pokemon with localized names, geographical metadata and tags keeps all of them unchanged after a full
  sync and after a single sync.
- A single sync of "pikachu" or 25 stores or refreshes Pikachu and returns it. An unknown Pokemon returns 404,
  and an invalid name returns 400, both as Problem Details.
- Starting a full sync returns 202 with a run identifier. Its status can then be read and moves from running
  to completed with the right counts.
- Starting a full sync while one is running returns 409.
- When some Pokemon fail upstream during a full sync, the run finishes as "completed with errors", lists the
  failed Pokemon, and all other Pokemon are synchronized.
- A Pokemon stored by a previous lookup is completed by the sync, not duplicated.
- Existing data survives the new database migrations, and the application starts with schema validation
  enabled.
- The existing lookup does not remove or overwrite proprietary data.
- The admin endpoints appear in Swagger UI with their parameters, response schemas and error responses
  documented.
- All existing tests still pass.

## Open Questions
- **Triggering**: should the full sync be started only on demand through the admin API, also on a schedule,
  or also once on application startup? Suggested default: on demand through the admin API, plus an optional
  scheduled sync that is disabled by default. **Decided:** suggested default.
- **Proprietary data management**: should this feature also include endpoints to add, edit and remove
  localized names, geographical metadata and tags, or only the storage for them? Suggested default: storage
  only. Management endpoints are a follow-up story. **Decided:** suggested default.
- **Exposing proprietary data**: should proprietary data be returned in the existing detailed view or lookup
  responses? Suggested default: not in this feature. **Decided:** suggested default.
- **Seeding localized names**: PokeAPI species include names in several languages. Should the sync use them
  to pre-fill localized names, or should localized names be purely proprietary? Suggested default: purely
  proprietary, left empty by the sync. **Decided:** suggested default.
- **Geographical metadata shape**: is a region, area or habitat with an optional note enough, or are
  coordinates or links to a separate locations table needed? Suggested default: region, area or habitat with
  an optional note. **Decided:** suggested default.
- **Tags**: free-text tags per Pokemon, or tags picked from a managed list of allowed tags? Suggested default:
  free-text tags, normalised to lowercase. **Decided:** suggested default.
- **Scope of replication**: should the sync also store species-level data (description, evolution chain) so
  the detailed view could later be served locally? Suggested default: no, only the fields listed above.
  **Decided:** suggested default.
- **Deleted upstream Pokemon**: should Pokemon no longer in PokeAPI be kept, flagged, or removed? Suggested
  default: kept as they are. **Decided:** suggested default.
- **Admin security**: should the admin endpoints be protected now (for example by an API key), or left open
  and only separated by path? Suggested default: separated by path only, with protection as a follow-up.
  **Decided:** suggested default.

## Testing Guidelines
Create test file(s) for the new feature and cover the following cases with meaningful tests, without going too heavy:
- Unit tests (Mockito, no Docker) in `src/test/java`
  - Mapping from PokeAPI responses to the local upstream data, including stats, abilities, species and
    missing optional fields.
  - Single sync: creates a new Pokemon, updates an existing one matched by PokeAPI id, keeps proprietary data
    untouched, and handles renamed Pokemon.
  - Full sync: walks every catalogue page, counts created, updated and failed Pokemon, carries on after a
    single failure, and records the final status.
  - Concurrency guard: a second full sync is rejected while one is running.
  - Scheduled sync: disabled by default, and skipped when a full sync is already running.
  - Controller: 202 with run id, 409 on a running sync, status responses, single sync success, 400 and 404.
- Integration tests (Testcontainers) in `src/integrationTest/java`
  - With WireMock stubs for a small catalogue: a full sync stores every Pokemon with all replicated fields in
    PostgreSQL.
  - A second full sync creates no duplicates.
  - Proprietary data (localized names, geographical metadata, tags) inserted before a sync is unchanged after
    a full sync and a single sync.
  - A Pokemon that fails upstream is recorded as failed while the rest are synchronized.
  - A Pokemon stored by a previous lookup is completed, not duplicated, by the sync.
  - The Flyway migrations apply on top of existing data and pass schema validation.
  - The OpenAPI document includes the admin sync endpoints with their parameters and responses.
