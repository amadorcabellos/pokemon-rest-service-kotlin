# Spec for Migrate the Service From Java to Idiomatic Kotlin

branch: claude/feature/kotlin-migration

## Summary
Technical Story – Kotlin Migration.

As the team maintaining this service, we want the whole codebase written in idiomatic Kotlin instead of Java,
so that the code is shorter, null-safe by design and easier to maintain, and the repository matches its name
(`pokemon-rest-service-kotlin`).

The service today is a Spring Boot 4.1 application on Java 21, built with Gradle 9 (Groovy DSL). It has about
160 Java files across production code (`src/main/java`), fast unit tests (`src/test/java`) and Testcontainers
integration tests (`src/integrationTest/java`), all under the `com.ballastlane.pokemon` package. This feature
rewrites all of it in Kotlin and moves the build script to the Gradle Kotlin DSL.

This is a pure refactor. Nothing a client can observe changes: the REST API, the database schema and
migrations, security and JWT behaviour, caching, resilience, synchronization and configuration all behave
exactly as they do today. The existing test suites, migrated to Kotlin, are the proof.

## Functional Requirements
- **Build script in Kotlin DSL**: `build.gradle` is replaced by `build.gradle.kts` (and `settings.gradle` by
  `settings.gradle.kts`), with the same group, version, description, Java 21 toolchain and dependency set.
  - The Kotlin JVM plugin is added, plus the Spring (all-open) and JPA (no-arg) compiler plugins so Spring
    proxies and JPA entities keep working.
  - The Kotlin standard library, Kotlin reflection and the Jackson Kotlin module are added where Spring needs
    them.
  - The `test` and `integrationTest` JVM test suites keep their current setup: same dependencies, the
    integration suite running after unit tests, and `check` depending on the integration suite.
  - Test logging, and the `bootTestRun` wiring to `TestPokeRestServiceApplication`, are kept.
- **Source layout**: every source set moves from `src/<set>/java` to `src/<set>/kotlin` (`main`, `test`,
  `integrationTest`), keeping the `com.ballastlane.pokemon` package structure and sub-packages (`auth`,
  `client`, `config`, `domain`, `exception`, `filter`, `handler`, `localdata`, `mapper`, `repository`,
  `service`, `sync`, `web`, `support`, and so on). Resources (`application.yml`, Flyway migrations) stay
  where they are.
- **No Java left**: when the migration is done, no `.java` files remain under `src/`.
- **Idiomatic Kotlin, not translated Java**:
  - Java records and DTOs become Kotlin data classes (or plain classes where data-class semantics don't fit,
    such as JPA entities).
  - `@ConfigurationProperties` classes use constructor binding with immutable properties and keep their
    current defaults and validation.
  - `Optional` and null checks in our own code are replaced by Kotlin nullable types and null-safe
    operators. Spring Data repository methods may return nullable types instead of `Optional` where that
    does not change behaviour.
  - Dependencies are injected through the primary constructor. No field injection.
  - Extension functions, `when` expressions, default and named arguments, and scope functions are used where
    they make the code clearer, not by reflex.
  - Static helpers and constants move to top-level declarations or `companion object`s, whichever reads
    better.
- **Behaviour preserved across all features**:
  - REST contracts: same paths, HTTP methods, request and response JSON (field names, null handling,
    ordering where it matters), status codes, headers (ETag, If-Match, CORS) and Problem Details errors.
  - The OpenAPI document describes the same API.
  - Database: Flyway migrations are not changed or renamed. The JPA mapping still validates against the
    same schema.
  - Security: same public and protected routes, registration, JWT access tokens, rotating refresh tokens and
    session handling.
  - Caching (Redis), resilience (Resilience4j retry, circuit breaker, rate limiter), PokeAPI client
    behaviour, catalogue filtering and scheduled synchronization behave as today.
  - Configuration keys in `application.yml` and environment variables keep their names and meaning.
- **Incremental migration**: the migration goes one package at a time (production code together with its
  tests), so that each step compiles and the test suites pass. Java and Kotlin can live side by side during
  the migration.
- **Docs and container**: the README (tech stack, project layout, file names) and the Dockerfile (copies the
  Kotlin DSL build files) are updated to reflect Kotlin.

## Non-Functional Requirements
- **No behaviour change**: no API, schema or configuration change of any kind. Any accidental change found
  during the migration is treated as a bug and fixed.
- **Null-safety at the boundaries**: values from Spring, JPA, Jackson and external APIs are given correct
  nullability. Non-null assertions (`!!`) are avoided and only allowed where a value is guaranteed non-null
  and a comment says why.
- **Readability**: the Kotlin code is at least as clear as the Java it replaces. Prefer `val` over `var`,
  immutable collections and expression bodies where they help.
- **Performance**: startup time, memory use and request latency stay about the same. Virtual threads stay
  enabled.
- **Build**: `./gradlew build` works from a clean checkout with the wrapper alone. Compiler warnings are kept
  to a minimum.
- **Tooling**: the Kotlin version is pinned explicitly and is compatible with Spring Boot 4.1 and Java 21.

## Out of Scope
- New features, new endpoints or changes to existing API responses.
- Changes to Flyway migrations, database schema or Redis key formats.
- Moving to coroutines, WebFlux, Ktor, Exposed or any other framework or library swap.
- Upgrading Spring Boot, Java, Gradle or other dependencies beyond what Kotlin support needs.
- Replacing JUnit 5 as the test engine. (Mockito is replaced by MockK and AssertJ by Kotest assertions, as
  decided below.)
- Adding linters or formatters (ktlint, detekt) to the build.
- Raising test coverage beyond the current tests, other than tests needed to prove Kotlin-specific behaviour.

## Possible Edge Cases
- **JPA entities**: entities need no-arg constructors and must not be `final`. Data classes for entities can
  break `equals`/`hashCode` and lazy loading, so entities stay regular classes with the current identity
  semantics.
- **Spring proxies**: `@Transactional`, `@Cacheable`, Resilience4j annotations and `@Configuration` classes
  rely on proxying. Classes and methods must stay open (all-open plugin), or these annotations silently stop
  working.
- **Jackson serialization**: Kotlin data classes need the Jackson Kotlin module. Default values, nullable
  fields, absent fields versus explicit `null` (important for partial updates) and unknown-field handling
  must behave exactly as today.
- **Validation annotations**: Bean Validation annotations on constructor properties must target the field or
  getter (use-site targets), or they are ignored without any error.
- **Configuration properties**: constructor binding with default values, nested properties, `Duration` and
  list types must bind the same values as today, including when a key is missing.
- **Optimistic concurrency**: `@Version` fields and ETag/If-Match handling for local data updates must keep
  their exact semantics.
- **Mockito with Kotlin**: final classes, non-null parameters and `any()` matchers behave differently in
  Kotlin. Tests may need helpers or an inline mock maker so mocks and argument matchers still work.
- **Static and package-private access**: Java package-private members and static methods used by tests need a
  Kotlin equivalent (`internal`, top-level functions, `companion object` with `@JvmStatic` where needed).
- **Clock and time**: the injectable `Clock` and the `MutableClock` test helper must keep tests deterministic.
- **Checked exceptions**: Kotlin has no checked exceptions. Error handling that relied on `throws` clauses
  must still map to the same Problem Details responses.
- **Mixed-language step**: while some packages are still Java, Java code must be able to call Kotlin code
  (and the other way round) without changing behaviour.

## Acceptance Criteria
- `build.gradle.kts` and `settings.gradle.kts` exist and the Groovy build files are gone.
- No `.java` files remain under `src/`. All code lives under `src/main/kotlin`, `src/test/kotlin` and
  `src/integrationTest/kotlin` in the same `com.ballastlane.pokemon` packages.
- `./gradlew test integrationTest` passes, with the same tests (same names and count, apart from tests added
  for Kotlin-specific behaviour) as before the migration.
- `./gradlew bootJar` builds, the Docker image builds, and the app starts with `docker compose up` and with
  `./gradlew bootTestRun`.
- The OpenAPI document generated after the migration is equivalent to the one generated before it.
- No Flyway migration file is changed, added or removed, and the app starts against an existing database
  without any migration running.
- `application.yml` keeps the same keys and defaults.
- The code is idiomatic Kotlin: data classes for DTOs, constructor-bound configuration properties, nullable
  types instead of `Optional` in our own code, constructor injection, and no unexplained `!!`.
- README and Dockerfile describe and build the Kotlin project.

## Open Questions
- Should tests stay on JUnit 5 + Mockito (smallest change), or move to MockK and/or Kotest assertions for more
  idiomatic tests?
  - **Answer:** move to MockK (with springmockk for Spring beans) and Kotest assertions. JUnit 5 stays.
- Should Spring Data repositories return nullable types (for example `findByIdOrNull`) everywhere, or keep
  `Optional` where it is used today?
  - **Answer:** keep `Optional` where it is used today.
- Which Kotlin version should be pinned (the latest stable one supported by Spring Boot 4.1)?
  - **Answer:** the latest stable one supported by Spring Boot 4.1: 2.3.21, managed by Spring Boot 4.1.1.
- Should the migration be one pull request, or one pull request per package?
  - **Answer:** one pull request.
- Should ktlint or detekt be added in a follow-up story to enforce the Kotlin style?
  - **Answer:** yes, in a follow-up story.

## Testing Guidelines
Create test file(s) for the new feature and cover the following cases with meaningful tests, without going too heavy:
- Unit tests (MockK + Kotest assertions, no Docker) in `src/test/kotlin`
- Integration tests (Testcontainers) in `src/integrationTest/kotlin`
- Migrate every existing unit and integration test to Kotlin with the same intent and assertions. These
  tests are the main safety net for the refactor.
- Add a small test that JSON for a partial update still tells an absent field apart from an explicit `null`
  after moving to Kotlin data classes.
- Add a small test that constructor-bound configuration properties bind the same defaults and overrides as
  before.
- Add a check that proxy-based behaviour still works on Kotlin classes (for example a cached call is served
  from the cache, and a transactional method rolls back on failure).
- Add a check that Bean Validation annotations on Kotlin constructor properties are enforced (an invalid
  request still returns 400 with the same field problems).
