# Spec for API for User Registration, Authentication and Protected Routes

branch: claude/feature/api-user-auth-protected-routes

## Summary
User Story 05 – Authentication and protected routes.

As the service owner, I want callers to register and sign in, and I want the endpoints that change data or
run synchronizations to require a signed-in caller, so that only known users can modify the local store
while reading Pokemon stays open to everyone.

Today every endpoint is open. The synchronization endpoints (User Story 03) and the local data updates (User
Story 04) were built with "protection as a follow-up". This feature adds a small auxiliary API to register a
user, sign in, renew access with a refresh token and sign out, and makes the following routes **protected**:

| Method | Path                                    |
|--------|-----------------------------------------|
| GET    | `/api/v1/admin/sync/runs/{runId}`       |
| GET    | `/api/v1/admin/sync/runs/latest`        |
| POST   | `/api/v1/admin/sync/runs`               |
| POST   | `/api/v1/admin/sync/pokemon/{nameOrId}` |
| PATCH  | `/api/v1/pokemon/{nameOrId}`            |
| PUT    | `/api/v1/pokemon/{nameOrId}`            |

Every other route stays **public**, in particular `GET /api/v1/pokemon/{nameOrId}` and
`GET /api/v1/pokemon`. Protection is by method **and** path: `GET /api/v1/pokemon/{nameOrId}` is public while
`PUT` and `PATCH` on the same path are protected.

Terminology:
- **User**: an account registered through the auxiliary API, identified by a unique login name, with a
  password and one or more roles.
- **Access token**: a short-lived credential returned on sign-in and on refresh, and sent with each request to
  a protected route.
- **Refresh token**: a longer-lived credential returned on sign-in and on refresh. It can only be used to get
  a new access token (and a new refresh token), never to call a protected route.
- **Session**: what one sign-in starts. It holds the current refresh token, and it ends on sign-out, when its
  refresh token expires, or when it is revoked. Ending a session invalidates its access and refresh tokens.
- **Protected route** / **public route**: as listed above.

## Functional Requirements
- **Registration**: anyone can register a user with a login name and a password through the auxiliary API
  (for example `POST /api/v1/auth/register`).
  - The login name is unique, compared ignoring case, and stored normalized (trimmed, lowercase).
  - The password must meet the password policy (see Open Questions for the exact rules).
  - A successful registration returns 201 with the new user (id, login name, roles, creation time) and never
    returns the password in any form.
  - Registering a login name that is already taken returns 409 as Problem Details.
  - Invalid input returns 400 as Problem Details, listing every invalid field with a short reason, in the same
    style as the local data updates.
- **Sign-in**: a registered user exchanges their login name and password for an access token through the
  auxiliary API (for example `POST /api/v1/auth/login`).
  - The response contains an access token, its type and when it expires, and a refresh token and when it
    expires. Each sign-in starts a new session, so a user can be signed in on several clients at once.
  - Wrong login name or wrong password both return the same 401 Problem Details, with the same message, so the
    response never reveals whether a login name exists.
  - Malformed input (missing fields, wrong types, invalid JSON) returns 400.
- **Refresh (public)**: a client exchanges a refresh token for new tokens through the auxiliary API (for
  example `POST /api/v1/auth/refresh`), without sending the password again. The route is **public**: it needs
  no access token, because the access token may already have expired. The refresh token itself is the
  credential.
  - The refresh token is sent in the request body, not in a cookie or the `Authorization` header. Any
    `Authorization` header on this route is ignored.
  - Refreshing **ends the presented refresh token and creates new tokens**: the refresh token used is
    invalidated at once (rotation), and the response has the same shape as the sign-in response, with a new
    access token and a **new refresh token**. Each refresh token works only once.
  - The access token issued before the refresh stops working as well, so after a refresh only the newest pair
    of tokens is valid for the session.
  - An unknown, expired, revoked or already used refresh token returns 401 as Problem Details, with the same
    message in every case.
  - **Reuse detection**: presenting a refresh token that was already rotated is treated as a sign of theft. The
    whole session is ended, so both the attacker and the legitimate client must sign in again.
  - A refresh for a user that no longer exists returns 401 and ends the session.
  - **Lifetimes**: an access token expires **15 minutes** after it is issued, and a refresh token expires
    **7 days** after it is issued. Both are configurable. A client whose access token has expired can
    therefore get a new one without signing in again, for up to 7 days. Because every refresh issues a new
    refresh token with a fresh 7 days, a session stays alive while the client refreshes at least once every 7
    days, and ends after 7 days without a refresh (the user then signs in again), subject to the maximum
    session lifetime (see Open Questions).
- **Sign-out (public)**: a client ends its session through the auxiliary API (for example
  `POST /api/v1/auth/logout`). The route is **public**: it needs no access token, so a client can sign out even
  after its access token has expired. The refresh token, sent in the request body, identifies the session to
  end.
  - It returns 204 with no body.
  - It **ends the token**: the refresh token can no longer be used, and the session's access token is rejected
    with 401 on every protected route from then on, even before it expires.
  - Other sessions of the same user (other clients) are not affected.
  - Signing out is idempotent and reveals nothing: an unknown, expired, already used or already signed-out
    refresh token also returns 204, and nothing changes. Only a malformed request (missing body, missing or
    blank refresh token, wrong type, invalid JSON) returns 400.
  - Signing out with a refresh token that was already rotated is treated like any reuse: the session it
    belonged to is ended.
- **Current user**: a signed-in caller can read their own profile (for example `GET /api/v1/auth/user`): id,
  login name and roles. This route is protected.
- **Protected routes**: the six routes listed in the Summary require a valid access token.
  - No token, a malformed token, an expired token, a token with an invalid signature, a token whose session
    has ended, or a refresh token used in place of an access token returns 401 as Problem Details, with a
    `WWW-Authenticate` header that says a bearer token is expected.
  - A valid token whose user lacks the required role returns 403 as Problem Details (see Open Questions for
    roles).
  - Authentication is checked before anything else, so an unauthenticated request with an invalid body or
    path gets 401, not 400 or 404, and never reaches the service.
  - With a valid token and the right role, the protected routes behave exactly as they do today, including
    their own 400, 404, 409, 412, 413, 415, 429, 502 and 503 responses.
- **Public routes**: every other route behaves exactly as today, with or without a token. A request to a
  public route with an invalid token is still served (the token is ignored), so a bad token never breaks
  public reads.
  - Public includes: the detailed view and catalogue list, the registration, sign-in, refresh and sign-out
    routes, Swagger UI and the OpenAPI document, and the actuator health endpoints. Only the auxiliary API's
    "current user" route is protected.
  - Methods that aren't supported on a path keep returning 405 as today.
- **Roles**: users have roles that decide which protected routes they can call (see Open Questions for the
  role model). Self-registration never grants the highest privileges by itself.
- **Initial administrator**: there is a documented way to have an administrator account available on a fresh
  installation (for example created at startup from configuration), so the synchronization routes are usable
  without editing the database by hand.
- **Error format**: all 400, 401, 403, 409 and 429 responses of this feature are Problem Details
  (`application/problem+json`), consistent with the rest of the API.
- **OpenAPI**: the auxiliary API is documented, the protected operations are marked as requiring a bearer
  token, and Swagger UI lets a user paste a token ("Authorize") to try protected routes. 401 and 403 are
  documented on every protected operation.
- **Configuration**: the access token lifetime (default 15 minutes), the refresh token lifetime (default 7
  days), the maximum session lifetime, the signing secret, and the initial administrator are configurable
  through the application configuration and environment variables, with safe defaults for local use.
- The README is updated to describe registration, sign-in, refresh, sign-out, how to call protected routes
  (with examples), the roles, the initial administrator, the new configuration, and the new error responses.

## Non-Functional Requirements
- **Password storage**: passwords are stored only as salted, slow, one-way hashes from a well-established
  algorithm. They are never logged, never returned, and never stored in plain text.
- **Token security**: access tokens are signed so they can't be forged or altered, and expire after a short,
  configurable time (see Open Questions). The signing secret comes from configuration, is never logged, and
  the application refuses to start with a missing or too-short secret outside local development.
- **Refresh token security**: refresh tokens are long, random and unguessable. Only a one-way hash of them is
  stored, so a database leak doesn't expose usable refresh tokens. They are never logged.
- **Immediate sign-out**: ending a session takes effect on the next request in every application instance,
  because sessions are kept in the shared database rather than in memory.
- **Housekeeping**: ended and expired sessions are removed after a while, so the session store doesn't grow
  without limit.
- **No user enumeration**: registration and sign-in responses, messages and timing don't reveal whether a
  login name exists, except the 409 on registration (see Open Questions).
- **Brute-force protection**: repeated failed sign-ins for the same login name or from the same client are
  slowed down or temporarily rejected (429 as Problem Details), without locking out the legitimate user
  permanently.
- **No cookies**: tokens travel in headers and request bodies only, and no session cookie is set, so
  cross-site request forgery protection isn't needed. Sessions are stored in the shared database, so the API
  keeps working behind a load balancer.
- **Safe errors**: authentication errors never expose stack traces, token contents, internal identifiers, or
  which part of a credential was wrong.
- **Observability**: successful and failed sign-ins, refreshes, sign-outs, detected refresh token reuse, and
  401/403 responses are logged at an appropriate level,
  with the login name (never the password or token) and without flooding logs on repeated failures.
- **Performance**: checking a token on a protected route (including its session) adds no PokeAPI calls and
  no noticeable latency.
  Public routes are not slowed down.
- **Consistency**: follow the existing project conventions for package layout, naming, Problem Details,
  Flyway migrations, OpenAPI annotations, configuration properties, and the split between unit and
  integration tests.

## Out of Scope
- Signing out of all sessions at once, and listing a user's active sessions.
- Keeping refresh tokens in cookies (they are returned in the response body only).
- Password reset, email verification, and changing a password or login name.
- Managing users through the API (listing, deleting, disabling, changing roles), beyond the initial
  administrator.
- Third-party sign-in (OAuth providers, OpenID Connect, single sign-on) and multi-factor authentication.
- Per-user ownership of data (for example only the user who wrote local data may change it).
- Rate limiting of incoming traffic in general (only failed sign-ins are limited).
- Protecting routes other than the six listed, and frontend or UI work.

## Possible Edge Cases
- A request to a protected route with no `Authorization` header, a header without the bearer scheme, an empty
  token, a token with extra whitespace, or two `Authorization` headers.
- A token that is expired, not yet valid, signed with a different secret, altered, or issued for a user that
  no longer exists.
- A valid token on a public route, and an invalid token on a public route (still served).
- An unauthenticated request to a protected route that would otherwise fail validation (401 wins).
- `GET /api/v1/pokemon/{nameOrId}` without a token (public) next to `PATCH` on the same path without a token
  (401).
- `DELETE /api/v1/pokemon/{nameOrId}` or other unsupported methods, with and without a token (405 as today).
- Registering the same login name twice at the same time: exactly one succeeds, the other gets 409.
- Registering with a login name that only differs by case or surrounding whitespace from an existing one.
- Passwords with Unicode, spaces, or at the maximum length; a password equal to the login name.
- Sign-in with the right password but different case in the login name (succeeds), and with a wrong-case
  password (fails).
- Many failed sign-ins in a row, followed by a correct one.
- A user whose roles change between token issue and use (see Open Questions).
- The application restarts: previously issued access and refresh tokens keep working until they expire or
  their session ends, as long as the signing secret doesn't change.
- A refresh with an expired access token in the `Authorization` header (ignored; the refresh token decides).
- A refresh token used twice: the first use rotates it, the second ends the session (reuse detection), and
  the access token issued by the first use stops working too.
- Two refreshes with the same refresh token at the same time (for example a client retry): exactly one
  succeeds; the other is treated as reuse.
- A refresh token sent as a bearer token to a protected route, or an access token sent to refresh (both 401).
- A refresh just before and just after the refresh token expires (7 days by default), and at the end of the
  maximum session lifetime.
- A client whose access token has expired (after 15 minutes) but whose refresh token hasn't (before 7 days):
  protected routes return 401, and a refresh succeeds and returns a new pair.
- A client that doesn't refresh for more than 7 days: both tokens have expired, refresh returns 401, and the
  user signs in again.
- A refresh token that outlives its session's maximum lifetime: it can't, its expiry is capped at the end of
  the session.
- Lifetimes configured to other values, including a refresh token lifetime shorter than the access token
  lifetime (allowed, documented).
- Sign-out with no access token, with an expired one, or with an invalid one in the `Authorization` header
  (all ignored; the refresh token decides).
- Sign-out twice with the same refresh token (both 204), with an unknown or expired refresh token (204,
  nothing changes), and with a malformed body (400).
- Sign-out with an access token in the body instead of a refresh token (204, nothing changes).
- A refresh or sign-out with a refresh token from a session that has already ended (401 on refresh, 204 on
  sign-out).
- An access token issued before a refresh, used after it (401).
- Sign-out on one client while another client of the same user stays signed in.
- A request already in progress when its session is ended (it completes; the next one gets 401).
- Clock differences between servers when checking expiry.
- Trying to register with extra fields such as roles, to grant oneself administrator rights (ignored or
  rejected).

## Acceptance Criteria
- Registering a new login name with a valid password returns 201 with the user and no password. Registering
  it again (any case) returns 409.
- Registering with a missing, blank, too short, or too long login name or password returns 400 listing each
  invalid field.
- Signing in with correct credentials returns 200 with an access token, a refresh token and their expiry
  times. Wrong login name and wrong password return the same 401 body.
- A refresh with a valid refresh token returns 200 with a new access token and a new refresh token. Using the
  old refresh token again returns 401 and ends the session, after which the new tokens are rejected too.
- A refresh with an unknown, expired or malformed refresh token returns 401, and the route works without an
  access token.
- By default, access tokens are rejected 15 minutes after they were issued, and a refresh token is rejected on
  refresh 7 days after it was issued. Between 15 minutes and 7 days, the expired access token gets 401 while a
  refresh still succeeds. Changing the configured lifetimes changes both.
- Refresh and sign-out work without an access token. After a refresh, the previous access token and the
  previous refresh token both get 401.
- Sign-out with a refresh token returns 204, with no access token needed. Afterwards the session's access
  token gets 401 on protected routes and the refresh token gets 401 on refresh, while another session of the
  same user keeps working.
- Sign-out with an unknown, expired or already signed-out refresh token also returns 204 and changes nothing;
  a malformed sign-out request returns 400.
- Each of the six protected routes returns 401 without a token, with a malformed token, and with an expired
  token, with a `WWW-Authenticate` header, and the underlying service is never called.
- Each protected route works as before with a valid token for a user with the required role, and returns 403
  for a user without it.
- `GET /api/v1/pokemon/{nameOrId}`, `GET /api/v1/pokemon`, Swagger UI, the OpenAPI document and the health
  endpoint work without a token, and with an invalid one.
- An unauthenticated `PATCH /api/v1/pokemon/pikachu` with an invalid body returns 401, not 400.
- `GET /api/v1/auth/user` returns the signed-in user, and 401 without a token.
- After too many failed sign-ins, further attempts get 429 for a while, then succeed again with the right
  password.
- On a fresh database, the configured initial administrator can sign in and start a full synchronization.
- No password or token appears in logs or responses other than the tokens in the sign-in and refresh
  responses, and refresh tokens are not stored in a readable form.
- The OpenAPI document shows the auxiliary API and marks the protected operations as requiring a bearer
  token, with 401 and 403 documented; Swagger UI offers "Authorize".
- All existing tests still pass, updated only where they call protected routes.

## Open Questions
- **Roles**: one role for every protected route, or separate roles? Suggested default: two roles. `USER` (given
  on registration) may call `PUT` and `PATCH` on `/api/v1/pokemon/{nameOrId}` and `GET /api/v1/auth/user`.
  `ADMIN` may also call the four `/api/v1/admin/sync/**` routes. two roles.
- **Open registration**: may anyone register, or only an administrator? Suggested default: anyone may
  register, and always gets `USER`. Administrators come only from the initial administrator configuration. anyone.
- **Login name**: a username or an email address? Suggested default: a username of 3 to 50 characters made of
  letters, digits, `.`, `_` and `-`, compared ignoring case. username.
- **Password policy**: suggested default: 12 to 128 characters, any characters including spaces and Unicode,
  no composition rules, and not equal to the login name. yes.
- **Token format and lifetime**: **Decided:** the access token expires after 15 minutes and the refresh token
  expires 7 days after it was issued; both lifetimes are configurable. Suggested default for the format:
  the access token is a signed bearer token (JWT) linked to its session, and the refresh token is an opaque
  random value.
- **Maximum session lifetime**: should rotation be able to keep a session alive forever? Suggested default:
  no. A session ends 30 days after sign-in, however often it is refreshed, and the user signs in again.
- **Refresh token reuse**: when an already rotated refresh token is presented, end the whole session or only
  reject the request? Suggested default: end the whole session (reuse detection), and log it as a warning.
  Concurrent client retries then need a new sign-in, which is accepted.
- **Sign-out scope**: only the current session, or all sessions of the user? Suggested default: only the
  current session. "Sign out everywhere" is a follow-up.
- **Sign-out credential**: **Decided:** sign-out is a public route that takes the refresh token in the request
  body; no access token is needed. The session's access token is invalidated with it.
- **Refresh access**: **Decided:** refresh is a public route; the refresh token is the only credential, and
  the presented refresh token is ended and replaced by a new pair.
- **Previous access token after a refresh**: should the access token issued before a refresh keep working
  until it expires? Suggested default: no, it stops working at once, so only the newest pair is valid.
- **Session cleanup**: suggested default: ended and expired sessions are deleted after 30 days by a scheduled
  job. no needed.
- **Role changes and deleted users**: should a token stop working as soon as its user is deleted or loses a
  role? Suggested default: the token is checked against the stored user on each protected request, so a
  deleted user gets 401 and a removed role takes effect at once. default.
- **Registration conflict and enumeration**: returning 409 for a taken login name reveals that it exists.
  Accept that for registration? Suggested default: yes, 409 on registration; sign-in never reveals it. default.
- **Failed sign-in limit**: suggested default: after 5 failed attempts for a login name within 15 minutes,
  further attempts for it get 429 for 15 minutes, counted per application instance. default.
- **Initial administrator**: suggested default: created at startup from `ADMIN_USERNAME` and `ADMIN_PASSWORD`
  when both are set and the user doesn't exist yet. Not created otherwise, and an existing user is never
  changed. default.
- **Auxiliary API path**: suggested default: `/api/v1/auth` (`register`, `login`, `user`). default. With this
  change, also `refresh` and `logout` under the same path.

## Testing Guidelines
Create test file(s) for the new feature and cover the following cases with meaningful tests, without going too heavy:
- Unit tests (Mockito, no Docker) in `src/test/java`
  - Registration: normalizes the login name, hashes the password, rejects duplicates and invalid input, and
    never grants `ADMIN`.
  - Sign-in: correct credentials give a token; wrong login name and wrong password give the same error;
    the failed sign-in limit kicks in and resets.
  - Tokens: the default lifetimes are 15 minutes for the access token and 7 days for the refresh token,
    and follow the configuration; issued tokens
    are accepted until they expire; altered, expired and differently signed tokens are
    rejected; a refresh token is never accepted as an access token, and the other way round.
  - Refresh: rotation issues a new pair and invalidates the old refresh token; reuse ends the session; expired,
    revoked and unknown refresh tokens are rejected the same way; the maximum session lifetime is enforced.
  - Sign-out: needs only the refresh token; ends only that session, and its access and refresh tokens stop
    working; unknown, expired and already ended refresh tokens give the same 204 and change nothing.
  - Initial administrator: created when configured and missing, left alone otherwise.
  - Controller and security slices: 201, 409, 400 with field errors, 200 with tokens on sign-in and refresh,
    204 on sign-out, 401 with `WWW-Authenticate`, 403, and public routes served with no token or an invalid
    one.
- Integration tests (Testcontainers) in `src/integrationTest/java`
  - Register, sign in and call a protected route end to end against PostgreSQL.
  - Refresh end to end: a new pair works, the old refresh token is rejected, and reusing it ends the session.
  - Refresh and sign-out end to end without an access token.
  - Sign-out end to end: the access token and refresh token stop working at once, while a second session of
    the same user keeps working.
  - Concurrent refreshes with the same refresh token: exactly one succeeds.
  - Each of the six protected routes: 401 without a token, 403 with a `USER` token where `ADMIN` is needed,
    and the existing behavior with the right token.
  - Public routes work without a token.
  - An unauthenticated request with an invalid body gets 401 and changes nothing.
  - Concurrent registrations of the same login name: one 201, one 409.
  - The OpenAPI document includes the auxiliary API and the bearer security on the protected operations.
  - Existing integration tests for sync and local data updates still pass with an authenticated caller.
