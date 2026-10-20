# Plan: Migrate the Service From Java to Idiomatic Kotlin

- Spec: `_specs/kotlin-migration.md`
- Branch: `claude/feature/kotlin-migration`

## Decisions

From the spec's answered Open Questions, plus facts checked in the codebase:

| Topic | Decision |
|---|---|
| Test libraries | **MockK** replaces Mockito (with **springmockk** `@MockkBean` replacing `@MockitoBean`). **Kotest assertions** replace AssertJ. **JUnit 5 stays** as the test engine. |
| Repository return types | **Keep `Optional`** on the Spring Data repository methods that return it today. Kotlin callers unwrap it right away (`getOrNull()`, `orElseThrow`), so `Optional` lives only in repository signatures. |
| Kotlin version | **2.3.21**, the version Spring Boot 4.1.1's dependency BOM manages (`kotlin.version`). The Gradle plugin is pinned to the same version. |
| Delivery | **One pull request**, made of one commit per step below, so each package can be reviewed on its own. |
| Lint | **ktlint/detekt are not part of this work.** They go in a follow-up story with its own spec. |
| Migration order | **Bottom-up** (leaf packages first). Converted Java records keep Java-style accessors while Java code still calls them (see "Mixed-language phase"). |
| Test method names | **Kept as they are** (camelCase, no backtick names), so the before and after test reports can be compared by name and count. |

**Library versions** (pin at implementation time, checked against Spring Boot 4.1 / Jackson 3):
- `org.jetbrains.kotlin.jvm`, `plugin.spring`, `plugin.jpa`: 2.3.21
- `io.mockk:mockk`: latest 1.14.x
- `com.ninja-squad:springmockk`: 5.0.x (the line built for Spring Framework 7 / Boot 4)
- `io.kotest:kotest-assertions-core`: latest 6.x
- `tools.jackson.module:jackson-module-kotlin` (version from the Jackson 3 BOM) and `org.jetbrains.kotlin:kotlin-reflect` (version from the Boot BOM)

**Spec follow-up (step 0):** the spec contradicts its own answers in two places:
- *Out of Scope* says replacing JUnit 5 or Mockito is out of scope. Change it to: Mockito is replaced by MockK and
  AssertJ by Kotest assertions, and JUnit 5 stays.
- *Testing Guidelines* says "Unit tests (Mockito, no Docker)". Change it to MockK.
- Write the answers below each Open Question (now they are appended to the end of the question line), and
  record the Kotlin version as 2.3.21.

## Current state (baseline)

- Spring Boot 4.1.1, Java 21, Gradle 9.8.1 wrapper, Groovy `build.gradle` / `settings.gradle`
  (`rootProject.name = 'pokemon-rest-service'`).
- 159 Java files (about 11.4k lines): 112 main, 29 unit test, 18 integration test.
- 249 `@Test` / `@ParameterizedTest` methods (parameterized ones run more than once).
- 37 records (DTOs, configuration properties, embeddables `PokemonStat` / `PokemonAbility`, projection
  `LocalDataHeader`, `TokenPair`, `SyncProgress`, `LocalDataUpdate`, `FieldProblem`).
- 9 JPA entities, none overriding `equals`/`hashCode`. `Pokemon.localDataVersion` is a read-only column
  (`insertable = false, updatable = false`) bumped by a repository query. There is no `@Version` field.
- 7 `@ConfigurationProperties` records using 23 `@DefaultValue`s, picked up by `@ConfigurationPropertiesScan`.
- Jackson 3 (`tools.jackson.*`). `LocalDataRequestParser` parses local data bodies itself into a `JsonNode`
  tree with a strict mapper. Absent and explicit-`null` fields are told apart there, not by DTO binding.
- `spring.jpa.hibernate.ddl-auto: validate`, virtual threads enabled.
- Tests: 22 `@Mock`, 5 `@InjectMocks`, 14 `@MockitoBean`, 10 `ArgumentCaptor`s, 40 `@Autowired` fields
  (all in tests), AssertJ in 31 files. `MutableClock` exists twice (unit and integration test helpers).
- `TestPokeRestServiceApplication.main` calls `PokeRestServiceApplication::main`, and `bootTestRun` points
  at it by class name.

## Design

### Build (`build.gradle.kts`)

- Same `group`, `version`, `description` and dependencies. Plugins: `kotlin("jvm")`, `kotlin("plugin.spring")`,
  `kotlin("plugin.jpa")`, Spring Boot, dependency management.
- `kotlin { jvmToolchain(21) }` replaces the `java { toolchain }` block, with these compiler options:
  - `-Xjsr305=strict`, so Spring's null-safety annotations give real Kotlin types.
  - `-Xannotation-default-target=param-property`, so annotations on constructor properties (Bean Validation,
    `@Schema`, JPA) also reach the field. Explicit `@field:` targets are still used where the target is
    unclear.
- `allOpen` adds `jakarta.persistence.Entity`, `MappedSuperclass` and `Embeddable`, so Hibernate can make lazy
  proxies. `plugin.spring` already opens `@Component`, `@Configuration`, `@Transactional`, `@Cacheable` and
  related annotations.
- Test suites in Kotlin DSL: `testing.suites` with `test` (`getting`) and `integrationTest`
  (`register<JvmTestSuite>`), the same `shouldRunAfter(test)`, the same `configurations` extension, `check`
  depending on `integrationTest`, and the same test logging. The Kotlin plugin adds `src/<suite>/kotlin` to
  each suite's source set on its own.
- `bootTestRun` keeps `mainClass = "com.ballastlane.pokemon.TestPokeRestServiceApplication"` (see entry
  points below).
- When the migration is done, Mockito is excluded from the test configurations, so nobody can import it by
  accident. If Spring's test support turns out to need it at runtime, the exclusion is dropped and only the
  ban on imports is checked.

### Entry points

- `PokeRestServiceApplication.kt`: the annotated class plus a top-level `fun main` using
  `runApplication<PokeRestServiceApplication>(*args)`. The Boot plugin finds `PokeRestServiceApplicationKt`
  on its own, and the Dockerfile's `JarLauncher` entry point doesn't change.
- `TestPokeRestServiceApplication.kt`: an `object` with a `@JvmStatic fun main`. That keeps the class name
  `bootTestRun` uses, and it avoids two top-level `main` functions in the same package across source sets.
  It delegates with `SpringApplication.from { runApplication<PokeRestServiceApplication>(*it) }`.

### How Java maps to Kotlin

| Java today | Kotlin | Watch out for |
|---|---|---|
| Response DTO record | `data class` with `val`s | Same property names (don't rename `current` to `isCurrent`), same nullability as the values really have, `@Schema` kept. |
| Request DTO record (`LoginRequest`, `RegisterRequest`, `RefreshTokenRequest`, `LocalDataRequest`) | `data class` with **nullable** properties | A non-null property makes Jackson fail on a missing field *before* our validation runs, which would change the 400 response. Nullable keeps today's field-level problems. |
| PokeAPI client DTOs (`client/dto`) | `data class`es with nullable properties defaulting to `null` where PokeAPI can leave a field out | The Java records accept missing fields, and the Kotlin module would otherwise reject them. `@JsonIgnoreProperties` is kept. |
| `@ConfigurationProperties` record | Constructor-bound `data class` | Scalar defaults become Kotlin default values. `@DefaultValue` stays where it creates an empty nested object or a list default, so binding stays the same. Validation annotations get `@field:` targets. |
| JPA entity | Regular `class` (never `data class`), `var` properties, no-arg constructor from `plugin.jpa`, open through `allOpen` | Keep field access, column names and the `insertable/updatable = false` mapping of `localDataVersion` (`private set`). No new `equals`/`hashCode`. `ddl-auto: validate` catches mapping drift. |
| Embeddable record (`PokemonStat`, `PokemonAbility`) | `data class`, `@Embeddable` | Needs the no-arg constructor (`plugin.jpa`) and to be open (`allOpen`). |
| Projection record (`LocalDataHeader`) | `data class` | The constructor parameter order must match the JPQL constructor expression. |
| Repository interface | Kotlin interface, `Optional` kept where it is today, `@Query` text unchanged | Query method names don't change, because they define the queries. |
| Service / component / controller | Class with primary-constructor injection, `private val` dependencies | Logger in a `companion object` (or a private top-level `val`). `@Transactional` self-calls behave the same as in Java. |
| Utility class (private constructor + statics: `LocalDataLimits`, `PokeApiUrls`, `PasswordPolicy`, `OpenApiSecurity`, `IfMatch` factory methods, and so on) | `object` with `const val`s, top-level functions, or a `companion object` | Use whatever reads best at each call site. |
| Exceptions | `class X(message: String) : RuntimeException(message)`, one per file | No checked exceptions: make sure every place that caught a checked exception still catches it. |
| `Optional` in our own code (mappers, services, sync) | Nullable types, `?.`, `?:`, `let` | `Optional` stays only at repository boundaries. |
| `SecurityConfig` lambda DSL | Spring Security's Kotlin DSL (`http { authorizeHttpRequests { … } }`) | `SecurityRulesTest`, `AuthIntegrationTest` and `CorsIntegrationTest` must pass with no change to what they assert. |
| `RestClient` calls | Kotlin extensions (`body<T>()`) | Same error handling for 404 and 5xx. Resilience4j annotations stay on open methods. |

### Mixed-language phase

While Java and Kotlin live side by side, the Kotlin plugin compiles Kotlin first (it can see the Java sources),
then `javac` compiles the Java against the Kotlin classes. To avoid editing Java code that is about to be
deleted:
- Data classes that replace a record are annotated **`@JvmRecord`** during the transition, so remaining Java
  callers keep calling `name()` instead of `getName()`.
- Members of `object`s and `companion object`s that Java calls get **`@JvmStatic`** (and constants are
  `const val`).
- Both annotations are removed in the cleanup step, once no Java callers are left.
- Kotlin code calling Java code that hasn't been migrated yet gets platform types. Write the expected
  nullability explicitly at those call sites rather than relying on inference.

### Tests

- Mockito → MockK: `@Mock`/`@InjectMocks` + `MockitoExtension` → `mockk()` in the test class's properties,
  with the subject built in the constructor or `@BeforeEach` (or `@MockK` + `MockKExtension`).
  `given(...).willReturn` → `every { } returns`, `verify(...)` → `verify { }`,
  `verifyNoInteractions` → `verify { mock wasNot Called }`, `ArgumentCaptor` → `slot()` / `mutableListOf()`
  + `capture`.
- MockK mocks are **strict by default**: a call that wasn't stubbed throws, where Mockito returns a default
  value. Stub those calls explicitly, or use `relaxUnitFun = true` for `Unit` methods only. Don't use fully
  relaxed mocks, because they hide missing expectations.
- `@MockitoBean` → `@MockkBean` (springmockk) in the `@WebMvcTest`-style tests.
- AssertJ → Kotest matchers (`shouldBe`, `shouldContainExactly`, `shouldThrow<T> { }`,
  `shouldBeNull`). MockMvc assertions use Spring's Kotlin MockMvc DSL (`mockMvc.get(...) { }.andExpect { }`).
- `@Autowired` fields → constructor injection (`class XTest @Autowired constructor(...)`). The abstract
  `AbstractApiIntegrationTest` may keep `@Autowired lateinit var` for what its subclasses share.
- `OutputCaptureExtension`, `@ParameterizedTest`/`@CsvSource`, Testcontainers and WireMock setup carry over
  unchanged.
- Both `MutableClock` copies are migrated where they are. Merging them is out of scope.

## Steps

Every step ends with `./gradlew test integrationTest` passing (Docker is needed) and its own commit, using the
repo's gitmoji style (`🔨 refactor: …`). In each package step, the package's main code and its unit tests
move together.

0. **Spec follow-up**: apply the edits listed under Decisions.
1. **Baseline**: on the unmigrated code, run `./gradlew test integrationTest` and save the list of test
   classes and methods with counts (from `build/test-results/**/*.xml`). Start the app (`./gradlew bootTestRun`)
   and save `/v3/api-docs` as sorted JSON. Both go in the scratchpad, not the repo. Write down the SHA-256 of
   each file under `src/main/resources/db/migration` and of `application.yml`.
2. **Gradle Kotlin DSL, still all Java**: replace `build.gradle`/`settings.gradle` with `.kts` equivalents, with
   no change in behaviour. Update the Dockerfile `COPY` line (`settings.gradle.kts build.gradle.kts`). Check
   that `./gradlew dependencies` resolves the same set and the Docker image builds.
3. **Add the Kotlin toolchain**: Kotlin plugins, compiler options, `allOpen` setup, `kotlin-reflect`,
   `jackson-module-kotlin`, MockK, springmockk, Kotest assertions. Run a throwaway `@MockkBean` test to
   confirm springmockk works with Boot 4.1 before relying on it, then delete it. No source changes yet.
4. **`exception`** (14 classes). There are no unit tests for this package.
5. **`domain`** (9 entities, 2 embeddables, enums) + `PokemonTest`. The integration suite here proves the
   mapping against `ddl-auto: validate` and the real schema.
6. **`repository`** (9 interfaces + `LocalDataHeader`). `Optional` is kept. `@Query` strings are copied
   exactly.
7. **`client`** (`PokeApiClient`, `PokeApiUrls`, `client/dto`) + `PokeApiClientTest`.
8. **Configuration properties** (the 7 `*Properties` classes in `config`). Add
   `ConfigurationPropertiesBindingTest` (see Tests to add). The `@Configuration` classes stay Java until
   step 15.
9. **`mapper`** + `PokemonMapperTest`, `PokemonDetailsMapperTest`.
10. **`localdata`** + `IfMatchTest`, `LocalDataRequestParserTest`, `LocalDataServiceTest`.
11. **`auth`** + `AuthServiceTest`, `InitialAdminCreatorTest`, `LoginAttemptLimiterTest`, `PasswordPolicyTest`,
    `TokenServiceTest`, the unit-test `MutableClock`.
12. **`service`** + its four tests.
13. **`sync`** + its five tests (executors and virtual threads stay as they are, no coroutines).
14. **`filter`, `handler`** + `LocalDataRequestSizeFilterTest`.
15. **`config` `@Configuration` classes** (`SecurityConfig`, `CacheConfig`, `PokeApiClientConfig`,
    `OpenApiConfig`, `SyncConfig`, `LocalDataConfig`).
16. **`web`** (controllers + `web/dto`) + controller tests, `SecurityRulesTest`,
    `WebSecurityTestConfiguration`, and `PokeRestServiceApplication`. After this step no main code is in Java.
17. **Integration tests**: `support` first, then `TestPokeRestServiceApplication`, then each feature package
    (`auth`, `catalog`, `localdata`, `repository`, `resilience`, `sync`, `web`). Add the proxy check (see Tests
    to add).
18. **Cleanup**: remove the transitional `@JvmRecord`/`@JvmStatic`, delete the empty `src/*/java` folders,
    exclude Mockito, and look for leftovers (see Verification). Every `!!` that remains must have a comment
    saying why.
19. **Docs**: README (tech stack row, "Built with" line, project layout tree, file names, unit test
    description, plus a short note on the Kotlin compiler plugins). The Dockerfile already changed in step 2.

## Tests to add

Kept small, as the spec asks. Before adding each one, check whether an existing test already covers the case,
and extend that test instead if it does.

- **Absent field vs explicit `null`** (`LocalDataRequestParserTest`, unit): a partial update without a field
  leaves that field alone, and an explicit `null` is handled the same as today (cleared or rejected, as
  current behaviour dictates).
- **Configuration binding** (`config/ConfigurationPropertiesBindingTest`, unit, `ApplicationContextRunner`):
  for each properties class, an empty environment gives today's defaults (durations, lists, nested objects),
  and overrides bind as before.
- **Proxies still apply** (integration): the main proxied beans (`PokemonDetailsService` caching,
  `PokeApiClient` resilience, `LocalDataService` transactions) are AOP proxies. A transactional local data
  update that fails partway leaves the database unchanged. If `PokemonApiIntegrationTest` already checks
  that a repeat call is served from cache (WireMock call count), reuse it.
- **Bean Validation on Kotlin properties** (controller unit test): an invalid request, including one with a
  required field **missing** rather than blank, still returns 400 with the same Problem Details field
  problems.

## Verification

Before opening the PR:
- `./gradlew clean build` passes (runs `check`, so both suites).
- The test report lists the same test classes and methods as the baseline, plus the added tests. Nothing has
  gone missing and nothing is skipped.
- `/v3/api-docs` from the migrated app, sorted, matches the baseline. Any difference has to be explained and
  fixed (see Risks: `required` fields).
- The migration files and `application.yml` hash the same as the baseline.
- `docker compose up --build` starts the app against an existing database volume, and the Flyway log shows no
  migration applied. `./gradlew bootTestRun` starts too.
- Searches come back empty: `find src -name '*.java'`, `org.mockito`, `org.assertj`, `@JvmRecord`,
  `Optional` outside `repository/` (and outside call sites that unwrap it right away), `@Autowired` in
  `src/main`.
- Manual smoke test of register → login → refresh → update local data with If-Match → read details, which
  covers the security, ETag and Problem Details paths.

## Risks

| Risk | Mitigation |
|---|---|
| springdoc marks non-null Kotlin properties as `required`, so the OpenAPI document changes | Diff against the baseline (step 1). Where the Java version left a property optional, make it nullable if it really can be null, or set `@Schema(requiredMode = …)` to keep the document the same. |
| Jackson Kotlin module rejects JSON that Java records accepted (missing fields, `null` into non-null) | Nullable request and client DTOs (see How Java maps to Kotlin). Integration tests use real PokeAPI-shaped WireMock bodies. |
| A Spring proxy stops applying because a class or method is final | `plugin.spring` + `allOpen`. The added proxy check. The resilience and cache integration tests. |
| Validation annotations land on the constructor parameter only and are ignored without error | `-Xannotation-default-target=param-property`, `@field:` where unclear, and the added validation test. |
| springmockk doesn't support Boot 4.1 | Checked in step 3. Fallback: keep `@MockitoBean` for the Spring slice tests only, and report it as a departure from the spec. |
| Strict MockK mocks make migrated tests fail on calls that weren't stubbed | Stub them explicitly. Use `relaxUnitFun` only for `Unit` methods. |
| Hibernate mapping drift (field vs property access, read-only `localDataVersion`) | `ddl-auto: validate` at startup, `PokemonRepositoryIntegrationTest`, local data integration tests. |
| A big single PR is hard to review | One commit per step. Main code and its tests move together. Behaviour is pinned by the unchanged test assertions. |
