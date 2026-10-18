# Plan: API for User Registration, Authentication and Protected Routes

- Spec: `_specs/api-user-auth-protected-routes.md`
- Branch: `claude/feature/api-user-auth-protected-routes`

## Context

User Story 05. Every endpoint is open today. The sync endpoints (US03) and the local data updates (US04) were
built with protection as a follow-up. This story adds an auxiliary API under `/api/v1/auth` (register, login,
refresh, logout, user), and protects six routes by method and path. Everything else stays public.

Decisions from the spec. Where the spec has no answer, the suggested default is used:

| Topic | Decision |
|---|---|
| Roles | Two. `USER` is given on registration and may call `PUT`/`PATCH /api/v1/pokemon/{nameOrId}` and `GET /api/v1/auth/user`. `ADMIN` may also call the four `/api/v1/admin/sync/**` routes. |
| Registration | Open to anyone, and always gives `USER`. Admins come only from the initial administrator config. |
| Login name | Username, 3 to 50 characters of letters, digits, `.`, `_` and `-`. Compared ignoring case and stored in lowercase. |
| Password policy | 12 to 128 characters, any characters, no composition rules, not equal to the username. |
| Tokens | Access token: a signed JWT linked to its session, **15 min**. Refresh token: an opaque random value, **7 days** from issue. Both configurable. |
| Max session lifetime | 30 days after login, however often it is refreshed (suggested default; configurable). |
| Refresh token reuse | Ends the whole session and logs a WARN (suggested default). |
| Previous access token after a refresh | Stops working at once (suggested default). |
| Sign-out | Public. Takes the refresh token in the body and ends only that session. Always 204, except 400 for a malformed request. |
| Refresh | Public. The refresh token is the only credential; it is rotated. |
| Session cleanup | **None** (answered "no needed"). Ended and expired rows stay. |
| Role changes / deleted users | Checked against the database on every protected request. |
| Registration conflict | 409. Sign-in never reveals whether a username exists. |
| Failed sign-in limit | 5 failures for a username within 15 min, then 429 for 15 min. Counted per instance. |
| Initial administrator | Created at startup from `ADMIN_USERNAME`/`ADMIN_PASSWORD` when both are set and the user is missing. An existing user is never changed. |
| Path | `/api/v1/auth`: `register`, `login`, `refresh`, `logout`, `user`. |

## Design

### Dependencies (`build.gradle`)

- `spring-boot-starter-security`, plus the Boot 4 OAuth2 resource server starter
  (`spring-boot-starter-security-oauth2-resource-server`; check the exact Boot 4.1 name when adding it). This
  brings Spring Security's bearer token filter, the `WWW-Authenticate` handling and the Nimbus JWT
  encoder/decoder, so no hand-written JWT code is needed.
- Test: `spring-security-test`, through the Boot 4 security test starter if there is one, for the
  `jwt()` / `user()` request post-processors in `@WebMvcTest`.

### Data model (`V4__users_and_sessions.sql`)

```
app_user           (id BIGINT identity PK, username VARCHAR(50) NOT NULL UNIQUE CHECK (username = lower(username)),
                    password_hash VARCHAR(255) NOT NULL, created_at TIMESTAMPTZ NOT NULL)
app_user_role      (user_id FK → app_user ON DELETE CASCADE, role VARCHAR(20) CHECK IN ('USER','ADMIN'),
                    PK (user_id, role))
auth_session       (id UUID PK, user_id FK → app_user ON DELETE CASCADE, token_generation INTEGER NOT NULL,
                    created_at, expires_at (created_at + max session lifetime), ended_at NULL,
                    end_reason VARCHAR(20) NULL CHECK IN ('LOGOUT','REUSE','USER_GONE'))
auth_refresh_token (token_hash CHAR(64) PK, session_id FK → auth_session ON DELETE CASCADE,
                    generation INTEGER NOT NULL, issued_at, expires_at, used_at NULL)
```

- **Refresh tokens are kept after use** (`used_at` set). That's what makes reuse detection possible: a token
  that was already rotated is still recognised.
- Only a SHA-256 hash of the refresh token is stored (hex, 64 characters). A fast hash is fine here because the
  token is 256 random bits, unlike a password.
- `token_generation` goes up by one on every refresh. Each access token carries the generation it was issued
  for, so tokens from an older generation stop working at once.
- No cleanup job, as decided. The tables grow with sign-ins, which the README points out.

### Domain (new package `com.ballastlane.poke.auth`)

- Entities and repositories:
  - `AppUser`, with an eager `@ElementCollection Set<Role> roles`, and `Role` (`USER`, `ADMIN`).
    `AppUserRepository.findByUsername`.
  - `AuthSession`, with `@ManyToOne AppUser` and methods `end(reason, now)`, `isActive(now)` and
    `nextGeneration()`. Its repository has `findWithUserById(UUID)`, a join fetch of the user and roles.
  - `RefreshToken`. `RefreshTokenRepository.findForUpdate(hash)` uses `@Lock(PESSIMISTIC_WRITE)`, so two
    concurrent refreshes with the same token run one after the other: the first rotates it, and the second
    sees `used_at` and is treated as reuse, as the spec says.
- **`PasswordPolicy`**: checks the username and password rules, and collects every problem as a
  `FieldProblem`, the record from US04, so the 400 has the same shape. Passwords are measured in code points.
- **Password hashing**: a `DelegatingPasswordEncoder` with **PBKDF2** (`Pbkdf2PasswordEncoder` v5.8 defaults)
  as the encoding id. BCrypt was ruled out because it only uses the first 72 bytes, and a 128-character Unicode
  password can be much longer; Spring Security rejects longer inputs. Argon2 would need Bouncy Castle.
  Delegating keeps the hash prefixed with its algorithm, so it can be upgraded later.
- **`TokenService`**:
  - `issue(session, user)` returns a `TokenPair`.
  - The access token is a JWT signed with HS256 through `NimbusJwtEncoder`. Its claims are `sub` (user id),
    `sid` (session id), `gen` (generation), `roles`, `iat` and `exp`.
  - Its expiry is `now + access-token-ttl`, but never later than the session's `expires_at`.
  - The refresh token is 32 bytes from `SecureRandom`, base64url encoded. Its expiry is
    `now + refresh-token-ttl`, also capped at the session's `expires_at`. A new `RefreshToken` row is saved for
    it.
- **`AuthService`** (all `@Transactional`):
  - `register(username, password)`:
    - validates with `PasswordPolicy` (400 with every problem) and normalizes the username
    - saves the user with `USER` only; a role in the request body is never read
    - a unique violation on the username (including two concurrent registrations) becomes
      `UsernameTakenException` (409)
  - `login(username, password)`:
    - checks the `LoginAttemptLimiter` first; a locked username gets 429 with `Retry-After`
    - an unknown username still runs `matches` against a dummy hash, so the timing doesn't reveal it
    - a wrong username or password records a failure and raises `InvalidCredentialsException`: 401, same body
      either way
    - on success, resets the limiter, creates an `AuthSession` and issues the tokens
  - `refresh(refreshToken)`:
    - hashes the token and locks its row with `findForUpdate`
    - unknown or expired token, ended or expired session: `InvalidRefreshTokenException`, a 401 with one
      generic message for every case
    - `used_at` already set: **reuse**. Ends the session with `REUSE`, logs a WARN and returns 401.
    - otherwise: marks the token used, increases the session generation (which retires the old access token)
      and issues a new pair
    - the session-ending and 401 cases are committed before the exception is turned into a 401 (see Risks)
  - `logout(refreshToken)`:
    - a known token whose session is still active: ends the session with `LOGOUT`
    - a token that was already rotated: ends the session with `REUSE`
    - unknown, expired or already ended: does nothing
    - always returns normally, so the controller always answers 204
  - `currentUser(principal)` returns the user's id, username, roles and creation time.
- **`LoginAttemptLimiter`**: in memory, keyed by the normalized username, for one instance as decided.
  - Each entry holds a list of failure times inside the 15-minute window, and a lock-until time.
  - 5 failures inside the window lock the username for 15 minutes; a successful sign-in clears it.
  - Expired entries are dropped when touched, and the map is capped at 10,000 entries (oldest dropped) so
    attempts with random usernames can't fill memory. It uses a `Clock`, so tests can control time.
- **`InitialAdminCreator`**: an `ApplicationRunner`.
  - If both `app.security.initial-admin.username` and `.password` are set and the user doesn't exist, it creates
    the user with `USER` and `ADMIN`.
  - If the password breaks the policy, startup fails with a clear message.
  - It never changes an existing user. A concurrent create from another instance (unique violation) is
    ignored.

### Security configuration (`config/SecurityConfig`, `config/AuthProperties`)

`AuthProperties` (`app.security.*`, validated record):

| Property | Env variable | Default |
|---|---|---|
| `jwt-secret` | `JWT_SECRET` | none; must be at least 32 bytes, otherwise startup fails |
| `access-token-ttl` | `ACCESS_TOKEN_TTL` | `15m` |
| `refresh-token-ttl` | `REFRESH_TOKEN_TTL` | `7d` |
| `max-session-lifetime` | `MAX_SESSION_LIFETIME` | `30d` |
| `login-attempts.max-failures` / `window` / `lockout` | `LOGIN_MAX_FAILURES`, `LOGIN_FAILURE_WINDOW`, `LOGIN_LOCKOUT` | `5` / `15m` / `15m` |
| `initial-admin.username` / `password` | `ADMIN_USERNAME` / `ADMIN_PASSWORD` | unset |

**Secret per environment.** `application.yml` has no default secret, which gives "refuses to start outside
local development". Local setups get one each:

- `compose.yaml` sets a clearly marked development `JWT_SECRET`, plus `ADMIN_USERNAME`/`ADMIN_PASSWORD` for
  local use.
- `application-integration.yml` sets its own secret.
- `TestPokeRestServiceApplication` (`bootTestRun`) gets one through a test properties file.

**`SecurityFilterChain`**, stateless:

- CSRF, form login, HTTP Basic, the request cache, Spring's logout filter and anonymous sessions are all
  disabled (`SessionCreationPolicy.STATELESS`). No cookie is ever set.
- `authorizeHttpRequests`, by method **and** path:
  - `GET /api/v1/admin/sync/runs/*`, `POST /api/v1/admin/sync/runs` and `POST /api/v1/admin/sync/pokemon/*`
    need `hasRole("ADMIN")`.
  - `PUT /api/v1/pokemon/*` and `PATCH /api/v1/pokemon/*` need `hasRole("USER")`.
  - `GET /api/v1/auth/user` needs to be authenticated.
  - `anyRequest().permitAll()`. So other methods on these paths (for example `DELETE /api/v1/pokemon/x`) are
    public and still get 405 from MVC, as today.
  - A `RoleHierarchy` makes `ADMIN` imply `USER`.
- **Bearer token only where needed.** `oauth2ResourceServer().jwt()` is used with a custom
  `BearerTokenResolver` that only reads the `Authorization` header on the protected request matchers. On public
  routes it returns `null`, so a bad token is ignored instead of giving 401. This includes refresh and logout,
  where any `Authorization` header is ignored. Two `Authorization` headers on a protected route give 401
  (the resolver rejects them).
- **Session check on every protected request.** The `JwtAuthenticationConverter` takes the decoded JWT
  (signature and `exp` already checked by Nimbus, with the default 60 s clock skew) and loads the session with
  its user and roles in one query. The session must exist and be active, its `token_generation` must equal the
  `gen` claim, and the user must still exist. Otherwise the converter throws `InvalidBearerTokenException`, a
  401. Authorities come from the **stored** roles, not the token, so role changes and deleted users take effect
  at once. A deleted user's sessions go with it through the cascade.
- **Token kinds can't be swapped.** A refresh token isn't a JWT, so the decoder rejects it as a bearer token,
  and an access token sent to refresh or logout doesn't match any stored hash.
- **Problem Details for 401/403.** `ProblemDetailsAuthenticationEntryPoint` wraps
  `BearerTokenAuthenticationEntryPoint`, which keeps its RFC 6750 `WWW-Authenticate: Bearer ...` header, then
  writes `application/problem+json` with "Authentication required" and a generic detail. No token contents
  are echoed. An `AccessDeniedHandler` does the same for 403 "Forbidden".
- **401 before anything else.** Spring Security's filter chain runs before MVC and before
  `LocalDataRequestSizeFilter` (security is at order -100, ours at the lowest precedence). An unauthenticated
  request never reaches validation, the size check or a service.
- **Login and register aren't Spring Security form login.** They are ordinary `@RestController` endpoints that
  call `AuthService`. They are public and use `PasswordEncoder` directly.

### Web layer

New `web/AuthController` under `/api/v1/auth`, with the OpenAPI tag "Auth":

| Method and path | Request | Response |
|---|---|---|
| `POST /register` | `RegisterRequest(username, password)` | 201 `UserProfile(id, username, roles, createdAt)`, `Location: /api/v1/auth/user`; 400 with `errors`; 409 |
| `POST /login` | `LoginRequest(username, password)` | 200 `TokenResponse(accessToken, tokenType: "Bearer", expiresAt, refreshToken, refreshExpiresAt)`; 400; 401; 429 + `Retry-After` |
| `POST /refresh` | `RefreshRequest(refreshToken)` | 200 `TokenResponse`; 400; 401 |
| `POST /logout` | `RefreshRequest(refreshToken)` | 204; 400 |
| `GET /user` | bearer token | 200 `UserProfile`; 401 |

- The request records use Bean Validation (`@NotBlank`, `@Size`), and `@Valid @RequestBody` gives
  `MethodArgumentNotValidException`. A new handler maps it to 400 with the same `errors` list (`FieldProblem`).
  Policy checks that Bean Validation can't express, such as "not equal to the username", come from
  `PasswordPolicy` as `InvalidRegistrationException`, also listing fields.
- Malformed JSON or wrong types fall into the existing `HttpMessageNotReadableException` handler: 400
  "Malformed request body", with nothing echoed.
- Unknown fields such as `roles` are ignored by the shared mapper. The service never reads roles from a
  request, and a test proves it.
- New `GlobalExceptionHandler` entries:

  | Exception | Response |
  |---|---|
  | `UsernameTakenException` | 409 "Username already taken" |
  | `InvalidCredentialsException` | 401 "Invalid username or password" (no `WWW-Authenticate`) |
  | `InvalidRefreshTokenException` | 401 "Invalid refresh token" |
  | `TooManyLoginAttemptsException` | 429 "Too many sign-in attempts", with a `Retry-After` header |
  | `MethodArgumentNotValidException` | 400 with `errors` |

**OpenAPI.** `OpenApiConfig` adds a `bearerAuth` security scheme (HTTP bearer, JWT). The six protected
operations and `GET /user` get `@SecurityRequirement(name = "bearerAuth")` and documented `401`/`403` responses,
which makes Swagger UI show "Authorize" and lock icons. Swagger UI, `/v3/api-docs` and all of `/actuator/**`
stay public, as today.

**Logging.**

- Successful sign-ins, refreshes and sign-outs are logged at INFO with the username.
- Failed sign-ins are logged at INFO until the lockout, then once at WARN when it starts (so repeated failures
  don't flood the log).
- Detected refresh token reuse is logged at WARN with the session id.
- 401 and 403 are logged at DEBUG.
- Passwords and tokens are never logged.

## Implementation steps

0. Save this plan as `_plans/api-user-auth-protected-routes.md`.
1. Dependencies in `build.gradle`; `app.security.*` in `application.yml`; development secrets in
   `compose.yaml`, `application-integration.yml` and the `bootTestRun` properties.
2. Migration `V4__users_and_sessions.sql`.
3. `auth` package: entities, repositories, `Role`, `PasswordPolicy`, the password encoder bean,
   `TokenService`, `LoginAttemptLimiter`, `AuthService`, `InitialAdminCreator`, and the exceptions.
4. `SecurityConfig`: the filter chain, the restricted bearer token resolver, the session-checking JWT
   converter, the JWT encoder and decoder from the secret, the role hierarchy, and the Problem Details entry
   point and access denied handler.
5. `AuthController`, its DTOs, the `GlobalExceptionHandler` additions, and the OpenAPI security scheme and
   annotations on `SyncController` and `PokemonController`.
6. Update the existing tests that call protected routes:
   - `@WebMvcTest` classes (`SyncControllerTest`, `PokemonLocalDataControllerTest`): `@Import(SecurityConfig)`
     and send `.with(jwt().authorities(...))`.
   - `PokemonControllerTest`: public GETs keep working with no token.
   - Integration tests: `AbstractApiIntegrationTest` gets `adminToken()` / `userToken()` helpers, which create
     a user through `AuthService` and sign in, and `resetState` also clears users, sessions and the login
     limiter. `PokemonSyncIntegrationTest` and `LocalDataIntegrationTest` send `Authorization` headers.
7. New unit tests (`src/test/java`):
   - `PasswordPolicyTest`: username charset and length, normalization, password length in code points, Unicode
     and spaces, password equal to the username, every problem listed.
   - `AuthServiceTest`:
     - register: normalizes, hashes, `USER` only, duplicate → 409
     - login: success, and the same error for unknown and wrong; the limiter is consulted
     - refresh: rotation, generation bump, reuse ends the session, expired/unknown/ended rejected,
       session max lifetime caps the token expiry
     - logout: ends the session; unknown, ended and rotated tokens are handled as decided
   - `TokenServiceTest`: 15-minute defaults and configured TTLs, claims, capping at the session expiry,
     unique random refresh tokens, only the hash stored.
   - `LoginAttemptLimiterTest`: 5 failures lock, 429 until the lockout ends, a success resets, the window
     slides, the map is capped. Uses a fixed `Clock`.
   - `InitialAdminCreatorTest`: created when configured and missing; skipped when not configured or already
     there; a bad password fails startup.
   - `AuthControllerTest` (`@WebMvcTest` + `SecurityConfig`):
     - 201 + `Location`, 400 with `errors`, 409
     - login 200/401/429 with `Retry-After`
     - refresh 200/401, and works without `Authorization`
     - logout 204 with any refresh token, 400 when malformed
     - `/user` 200 with `jwt()`, 401 without
   - `SecurityConfigTest` (`@WebMvcTest`, mocked services):
     - every protected route: 401 with `WWW-Authenticate` and a Problem Details body with no token, a malformed
       token, an expired token, a wrongly signed token and two headers
     - 403 for `USER` on the admin routes
     - public routes served with no token and with garbage
     - unauthenticated `PATCH` with an invalid body gives 401, and the service is never called
     - `DELETE /api/v1/pokemon/x` gives 405 with and without a token
8. New integration test `AuthIntegrationTest` (`src/integrationTest/java/.../auth`):
   - **Flow:** register → login → `PATCH /api/v1/pokemon/pikachu` (after a lookup) → `/user`, against PostgreSQL.
   - **Protected routes:** each one gives 401 without a token, 403 with a `USER` token on admin routes, and
     its existing behaviour with the right token.
   - **Refresh:**
     - the new pair works; the old access token and the old refresh token get 401
     - reusing the old refresh token ends the session, so the new pair gets 401 too
     - works with no access token
   - **Logout:**
     - with no access token: 204; the access token gets 401 at once and the refresh token gets 401
     - a second session of the same user still works
     - unknown, expired and repeated logouts give 204
   - **Expiry:** a short `refresh-token-ttl` / `access-token-ttl` in a dedicated context, or a mutable `Clock`
     bean in the test configuration, rejects both after expiry.
   - **Max session lifetime:** enforced.
   - **Revocation:** deleting a user makes their token 401. Removing `ADMIN` from a user makes their token 403
     on admin routes at once.
   - **Concurrency:** concurrent registrations of one username give one 201 and one 409; concurrent refreshes
     with one token give one 200 and one 401.
   - **Brute force:** 5 wrong logins, then 429 even with the right password; after the lockout (fixed clock),
     login succeeds.
   - **Initial administrator:** a context with `ADMIN_USERNAME`/`ADMIN_PASSWORD` can sign in and start a full
     sync.
   - **Leaks:** no password or token appears in the captured log output (`OutputCaptureExtension`), and
     `auth_refresh_token` holds hashes only.
   - **Other tests:** `OpenApiIntegrationTest` checks the `bearerAuth` scheme, the security requirement and
     401/403 on the protected operations, and that the auth endpoints are documented. The
     `PokemonRepositoryIntegrationTest` style also covers V4 constraints (lowercase username check, role check,
     cascades).
9. README:
   - an Authentication section with a diagram of login → access/refresh → refresh rotation → logout
   - the roles and the protected/public route table
   - curl examples for register, login, calling a protected route, refresh and logout
   - Swagger "Authorize"
   - the initial administrator, and the new configuration rows (with "set `JWT_SECRET` in production")
   - the new error responses, V4 in migrations, and the test tables
   - a note that sessions are never cleaned up
   - an AI-assisted development entry 7
10. Verify (below).

## Files

| Action | File (under `src/main/java/com/ballastlane/poke/` unless noted) |
|---|---|
| Add | `src/main/resources/db/migration/V4__users_and_sessions.sql` |
| Add | `auth/AppUser.java`, `auth/Role.java`, `auth/AuthSession.java`, `auth/RefreshToken.java`, `auth/AppUserRepository.java`, `auth/AuthSessionRepository.java`, `auth/RefreshTokenRepository.java` |
| Add | `auth/AuthService.java`, `auth/TokenService.java`, `auth/TokenPair.java`, `auth/PasswordPolicy.java`, `auth/LoginAttemptLimiter.java`, `auth/InitialAdminCreator.java`, `auth/SessionJwtAuthenticationConverter.java` |
| Add | `exception/UsernameTakenException.java`, `exception/InvalidCredentialsException.java`, `exception/InvalidRefreshTokenException.java`, `exception/TooManyLoginAttemptsException.java`, `exception/InvalidRegistrationException.java` |
| Add | `config/SecurityConfig.java`, `config/AuthProperties.java`, `web/ProblemDetailsAuthenticationEntryPoint.java`, `web/ProblemDetailsAccessDeniedHandler.java` |
| Add | `web/AuthController.java`, `web/dto/RegisterRequest.java`, `web/dto/LoginRequest.java`, `web/dto/RefreshRequest.java`, `web/dto/TokenResponse.java`, `web/dto/UserProfile.java` |
| Modify | `build.gradle`, `application.yml`, `compose.yaml`, `config/OpenApiConfig.java`, `web/SyncController.java`, `web/PokemonController.java` (security annotations only), `web/GlobalExceptionHandler.java`, `README.md` |
| Test (modify) | `SyncControllerTest`, `PokemonLocalDataControllerTest`, `PokemonControllerTest`, `AbstractApiIntegrationTest`, `PokemonSyncIntegrationTest`, `LocalDataIntegrationTest`, `OpenApiIntegrationTest`, `application-integration.yml`, `TestPokeRestServiceApplication` properties |
| Test (add) | `PasswordPolicyTest`, `AuthServiceTest`, `TokenServiceTest`, `LoginAttemptLimiterTest`, `InitialAdminCreatorTest`, `AuthControllerTest`, `SecurityConfigTest`, `AuthIntegrationTest` |

## Verification

- `./gradlew build` runs unit and integration tests, and all of them pass, including the existing ones updated
  for tokens.
- Smoke test with `docker compose up --build` (with `JWT_SECRET` and the admin set in `compose.yaml`):
  - `curl -X POST /api/v1/admin/sync/runs` gives 401 with `WWW-Authenticate: Bearer`
  - `GET /api/v1/pokemon/pikachu` with no token gives 200, and with `Authorization: Bearer garbage` also 200
  - register `ash` → 201; again → 409
  - login → access and refresh tokens; `PATCH /api/v1/pokemon/pikachu` with the token → 200
  - the same token on `POST /api/v1/admin/sync/runs` → 403
  - admin login → `POST /api/v1/admin/sync/runs` → 202
  - refresh → new pair; the old access token and the old refresh token → 401; reusing the old refresh token
    ends the session
  - logout with the refresh token and no access token → 204; that access token → 401
  - 6 wrong logins → 429 with `Retry-After`
  - wait 15 minutes, or run with `ACCESS_TOKEN_TTL=1m`: the expired access token → 401
  - Swagger UI: "Authorize" with a token, then try `PATCH`
  - `docker compose logs app` shows no passwords or tokens

## Risks

- **Every existing test that calls a protected route changes.** Without `@Import(SecurityConfig)`, Boot's
  default security would lock down all `@WebMvcTest`s, including public ones. Mitigation: import the config
  everywhere it's needed and use `jwt()` post-processors; step 6 is done before the new tests.
- **A database read on every protected request** (session, user and roles in one indexed query). It's needed
  for immediate logout, refresh retirement and role changes. Only protected routes pay it; public reads don't.
- **A 7-day refresh token** means a stolen refresh token stays usable for up to 7 days unless it is used
  twice (reuse ends the session) or the user signs out. A client idle for over 7 days must sign in again.
  This is intentional and documented. The 30-day maximum only applies to clients that keep refreshing.
- **No session cleanup**, as decided: `auth_session` and `auth_refresh_token` grow by about one row per login
  and per refresh. With refreshes every 15 minutes that's about 96 rows a day per active client. The README
  points this out as a follow-up.
- **The failed sign-in limit is per instance** and lost on restart, as decided. Behind several instances, an
  attacker gets 5 tries per instance per window.
- **Committing work before throwing.** Ending a session on refresh token reuse must be committed even though a
  401 is returned. The service writes the session end in its own transaction, or returns a result the
  controller turns into 401, instead of throwing out of the transaction that ended it, which would roll it
  back.
- **The JWT secret** is missing in any environment that doesn't set it, so the app won't start. That's
  intended. `compose.yaml` and the test profiles set development values, clearly labelled as not for
  production.
- **Concurrent client refreshes**: two refreshes with one token at the same time end the session, by the reuse
  rule. Accepted in the spec. Clients must serialize refreshes.
