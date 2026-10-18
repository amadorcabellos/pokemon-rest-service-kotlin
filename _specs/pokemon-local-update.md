# Spec for Update Locally Stored Pokemon With Robust Validation

branch: claude/feature/pokemon-local-update-01

## Summary
User Story 04 – Local Data Modification.

As the service owner, I want to update any Pokemon that is stored in our local database through the API, so
that we can manage the data we own without editing the database by hand.

User Story 03 replicated the PokeAPI catalogue into PostgreSQL and added storage for proprietary data
(localized names, geographical metadata and classification tags). That storage can't be changed through the
API yet. This feature adds update operations for locally stored Pokemon, with strict validation and clear
errors: 404 when the Pokemon isn't in the local database, 400 when the request body is malformed or invalid,
and further defensive checks against conflicting or unsafe updates.

Everything stays on the existing Pokemon resource, `/api/v1/pokemon/{nameOrId}`. The existing read of that
resource (the detailed view) also shows the proprietary data, so callers can see the result of an update.
There is no separate read endpoint. The version and last modified time of the proprietary data are kept on the
existing Pokemon record in the local database, with no new table for them.

Terminology (same as User Story 03):
- **Upstream data**: fields that come from PokeAPI and are owned by it, for example name, stats, types and
  sprite. Only synchronization and the lookup write them.
- **Proprietary data**: fields owned by this service: localized names, geographical metadata and
  classification tags. Synchronization never changes them.
- **Local record**: a Pokemon row in the local database, together with its proprietary data.

## Functional Requirements
- **Reading uses the existing endpoint**: the local record is read through the existing
  `GET /api/v1/pokemon/{nameOrId}`. No new read endpoint is added (for example no `/local` sub-path).
  - The detailed view keeps all its current fields and behaviour (live PokeAPI data, cached as today).
  - It also returns the Pokemon's proprietary data (localized names, geographical metadata, tags), when that
    data was last modified, and its version (for example as an ETag).
  - The proprietary data in the response is always current: right after an update, the next read shows it,
    even while the rest of the detailed view is served from the cache.
  - For a Pokemon with no proprietary data yet, the lists are empty.
  - A first read of a Pokemon that isn't stored locally still works as today: it is fetched from PokeAPI and
    recorded, and it can be updated from then on.
- **Full update (replace)**: an operator can replace all proprietary data of a local record in one request:
  - the localized names (one per locale)
  - the geographical metadata entries (kind, value, optional note)
  - the classification tags
  Anything not in the request is removed, so sending empty lists clears that kind of data. The response is
  the updated local record: its proprietary data, last modified time and new version.
- **Partial update**: an operator can change only some kinds of proprietary data in one request (for example
  only the tags). Kinds that are not mentioned in the request stay as they are, and `null` for a kind clears
  it. The response is the same as for a full update.
- **Upstream data is read only**: the update operations never change upstream fields. A request that tries to
  set an upstream field (for example name, stats or types) is rejected with 400 and names the field.
- **Only local Pokemon can be updated**: the target must already be in the local database. If it isn't, the
  response is 404 as Problem Details, even if the Pokemon exists in PokeAPI. An update never fetches from
  PokeAPI or creates a new local record.
- **Validation (400)**: a request is rejected with 400 as Problem Details, listing every invalid field with a
  short reason, when:
  - the body is missing, empty, not valid JSON, or not a JSON object
  - a field has the wrong type (for example a number where a list is expected)
  - the body contains fields that are unknown or read only
  - the path value is not a valid Pokemon name or id (same rules as the existing lookup)
  - a localized name has a missing or invalid locale (not a well-formed language tag such as `en`, `ja-JP`),
    a blank name, or a name that is too long
  - the same locale appears more than once
  - a geographical entry has a missing or unknown kind (allowed: region, area, habitat), a blank or too long
    value, or a too long note
  - a tag is blank, too long, or uses characters outside the allowed set; tags that only differ by case or
    surrounding whitespace count as duplicates
  - a list has more entries than the allowed maximum
- **Normalisation**: text values are trimmed. Tags are stored in lowercase (as today). Locales are stored in
  their canonical form (for example `ja-jp` becomes `ja-JP`). Duplicate tags after normalisation are rejected,
  not silently merged.
- **Other request errors**:
  - a body sent with a content type other than JSON returns 415
  - a request larger than the allowed size returns 413 (or 400)
  - an unsupported method on the resource returns 405
  All as Problem Details.
- **Concurrent updates**: the read and each update response carry the version of the proprietary data (for
  example an ETag). An update may send the version it was based on. If the record has changed since, the update is rejected with
  412 and nothing is written. Updates without a version are still accepted (last write wins).
- **Atomic updates**: an update is applied fully or not at all. A failed validation or a database error leaves
  the local record unchanged.
- **Interaction with synchronization**: a sync (full or single) running at the same time as an update must not
  lose the update or fail because of it. Proprietary data written by an update survives later syncs.
- **Version and last modified time**: both are stored on the existing Pokemon record in the local database. No
  new table is added for them.
  - A successful update that changes something increases the version and records the time.
  - An update that changes nothing still succeeds and changes neither.
  - Synchronization and the lookup never change them, so a sync never makes an operator's version out of
    date.
- **Placement**: all operations are on the existing resource `/api/v1/pokemon/{nameOrId}`. The read is the
  existing GET, and the full and partial updates are added to the same path (decided in Open Questions).
- **Existing behaviour stays the same**:
  - The detailed view only gains the proprietary data, last modified time and version. Its existing fields,
    caching and error responses don't change.
  - The catalogue list doesn't change and still doesn't show proprietary data.
- The new operations, the extended detailed view response, the request and response schemas, validation rules and error codes are documented in the
  OpenAPI spec and shown in Swagger UI, with example requests.
- The README is updated to describe the update operations, the validation rules, the error responses and an
  example request.

## Non-Functional Requirements
- **Integrity**: the database keeps enforcing the existing rules (one localized name per locale, unique
  lowercase tags, allowed geographical kinds, and the length limits) as a last line of defence. A database constraint violation that
  slips past validation is returned as a 4xx Problem Details response, never as a 500 with internal details.
- **Safe errors**: error responses never expose stack traces, SQL, class names or internal identifiers.
  Invalid input values are echoed only in a shortened, safe form.
- **Idempotency**: sending the same full update twice leaves the record in the same state as sending it once.
- **Performance**: an update touches only the local database and makes no PokeAPI calls. It completes in well
  under a second for a record at the maximum list sizes. Adding proprietary data to the detailed view adds
  only a quick local database read, with no extra PokeAPI calls.
- **Observability**: successful updates are logged with the Pokemon and the kinds of data changed (not the
  full payload). Rejected updates are logged at a low level without the request body.
- **Consistency**: follow the existing project conventions for package layout, naming, Problem Details
  responses, input rules for names and ids, OpenAPI annotations, and the split between unit and integration
  tests.

## Out of Scope
- Editing upstream data, or overriding upstream values locally.
- Creating local records by hand, or deleting local records.
- Adding new kinds of proprietary data beyond localized names, geographical metadata and tags.
- A separate read endpoint for the local record (for example `/api/v1/pokemon/{nameOrId}/local`).
- A separate table for the version or last modified time.
- Showing proprietary data in the catalogue list.
- Authentication and authorization for the update operations.
- Bulk updates of several Pokemon in one request.
- A history or audit trail of past values.
- Frontend or UI work. This is an API-only feature.

## Possible Edge Cases
- The Pokemon exists in PokeAPI but has never been looked up or synchronized locally: an update gives 404,
  while a read works as today and stores it, so a later update succeeds.
- The path uses mixed case, surrounding whitespace, a PokeAPI id, or an invalid value (400), as for the
  lookup.
- The Pokemon was renamed upstream and re-synchronized: it is found by its new name and by its PokeAPI id.
- Empty lists in a full update clear that kind of data. An empty object in a partial update changes nothing.
- `null` for a whole kind of data in a partial update clears it. `null` inside a list or for a required field
  of an entry is rejected with 400.
- Two tags that only differ by case (`Mascot`, `mascot`), or two locales that only differ by case (`en-us`,
  `en-US`): rejected as duplicates.
- Several geographical entries with the same kind and value are allowed (decided in Open Questions). They are
  stored and returned as separate entries.
- Unicode in localized names (for example Japanese or accented characters) is stored and returned unchanged.
- Control characters, very long strings, or deeply nested JSON in the body.
- A JSON body with duplicate keys, trailing content, or a top-level array.
- Numbers in the path that are zero, negative or larger than an integer.
- Two updates of the same Pokemon at the same time, with and without a version: one wins cleanly, and with a
  version the other gets 412.
- A full sync updates the same Pokemon while an update is in progress. The update is kept, and the version
  doesn't change because of the sync.
- The detailed view of a Pokemon is in the cache when its proprietary data is updated. The next read shows the
  new proprietary data and version.
- An update arrives while the database is unavailable: 503 as Problem Details, nothing half written.

## Acceptance Criteria
- `GET /api/v1/pokemon/{nameOrId}` returns 200 with all the existing detailed view fields, plus the
  proprietary data, last modified time and version.
- Updating a Pokemon that is not in the local database returns 404 as Problem Details, and no PokeAPI call is
  made.
- After an update, `GET /api/v1/pokemon/{nameOrId}` shows the new proprietary data and version straight away,
  even when the detailed view comes from the cache.
- A full update of Pikachu with two localized names, one geographical entry and two tags returns 200 with
  exactly that data, and reading it again returns the same data.
- A full update with empty lists clears all proprietary data of that Pokemon.
- A partial update that only sends tags changes the tags and leaves localized names and geographical metadata
  unchanged. A partial update with `null` for tags clears the tags.
- A full update with two geographical entries that have the same kind and value stores both.
- Each of these returns 400 as Problem Details, lists the offending field(s), and leaves the record
  unchanged:
  - malformed JSON
  - an unknown field
  - an upstream field such as `name` or `stats`
  - an invalid locale
  - a duplicate locale
  - an unknown geographical kind
  - a blank or too long value
  - a duplicate tag after normalisation
  - a list over the maximum size
  - an invalid path value
- A non-JSON content type returns 415.
- An update sent with an out-of-date version returns 412 and changes nothing. One with the current version
  succeeds and returns a new version.
- Proprietary data set through an update is unchanged after a full sync and a single sync of that Pokemon,
  and so is its version.
- The catalogue list response is unchanged. The detailed view keeps its existing fields and only gains the
  proprietary data, last modified time and version.
- No new table is added for the version or last modified time. They are stored on the existing Pokemon record.
- The update operations and the extended detailed view appear in Swagger UI with their request and response schemas, examples and error
  responses documented.
- All existing tests still pass.

## Open Questions
- **What can be updated**: only proprietary data, or also local overrides of upstream fields (which a sync
  would then have to respect)? Suggested default: proprietary data only. Upstream fields are read only and
  are rejected with 400. only proprietary data can be updated.
- **Operations**: full replace (PUT) only, partial update (PATCH) only, or both? Suggested default: both. PUT
  replaces all proprietary data. PATCH uses JSON Merge Patch semantics per kind of data, where a list replaces
  that kind and an omitted kind is left alone. both operations return the updated local record.
- **`null` in a partial update**: does `null` for a kind of data clear it, or is it rejected? Suggested
  default: `null` clears it, following JSON Merge Patch. clear
- **Endpoint placement**: under the admin path (`/api/v1/admin/pokemon/{nameOrId}`) or on the public Pokemon
  path? Suggested default: admin path, next to the sync endpoints. on this path `/api/v1/pokemon`
  **Decided:** all operations on `/api/v1/pokemon/{nameOrId}`. The read is the existing GET, with no
  separate read endpoint.
- **Concurrency control**: should updates require a version (If-Match), accept it optionally, or ignore it?
  Suggested default: optional. A stale version returns 412, and a missing version means last write wins. optional
- **Limits**: suggested defaults:
  - at most 50 localized names, 20 geographical entries and 30 tags per Pokemon
  - localized name up to 100 characters
  - geographical value up to 100 characters, note up to 1,000
  - tags up to 50 characters, made of letters, digits, `-` and `_`
  - request body up to 64 KB

  **Decided:** suggested defaults (no other answer given).
- **Duplicate geographical entries**: should two entries with the same kind and value be allowed? Suggested
  default: rejected as duplicates (comparison ignores case). two entries with the same kind and value are allowed
- **Locale format**: any well-formed language tag, or only a fixed list of supported locales? Suggested
  default: any well-formed language tag, stored in canonical form. any well-formed language tag
- **Admin security**: still separated by path only? Suggested default: yes, protection stays a follow-up. yes

## Testing Guidelines
Create test file(s) for the new feature and cover the following cases with meaningful tests, without going too heavy:
- Unit tests (Mockito, no Docker) in `src/test/java`
  - Validation and normalisation:
    - locales, tags and geographical kinds
    - blank and too long values, and list size limits
    - duplicates after normalisation (locales and tags); duplicate geographical entries are accepted
  - Update service:
    - full replace and partial update
    - empty lists clear data, and omitted kinds are kept
    - 404 when the Pokemon is not stored locally, and no PokeAPI call is made
    - stale version gives 412
    - an update that changes nothing keeps the version and last modified time
    - the detailed view includes the current proprietary data and version
  - Controller (`@WebMvcTest`):
    - 200 for the read (with the proprietary data and version) and for updates, 404, and 415
    - 400 for malformed JSON, unknown and read-only fields, wrong types, invalid path, and field errors
      listed in Problem Details
    - 412 for a stale version, and the version returned in the response
- Integration tests (Testcontainers) in `src/integrationTest/java`
  - A full update and a partial update are stored in PostgreSQL and shown by
    `GET /api/v1/pokemon/{nameOrId}`, including when the detailed view is already in the cache.
  - The version and last modified time are stored on the Pokemon record. A sync doesn't change them.
  - Invalid requests leave the stored data unchanged.
  - Proprietary data set through an update survives a full sync and a single sync.
  - Updating a Pokemon that exists only in PokeAPI (WireMock) and not locally returns 404.
  - The detailed view's existing fields are unchanged.
  - Two concurrent updates with the same version: one succeeds, the other gets 412.
  - The OpenAPI document includes the update operations and the extended detailed view, with their request
    and response schemas and error responses.
