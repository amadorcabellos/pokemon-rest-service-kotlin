package com.ballastlane.pokemon.sync

import com.ballastlane.pokemon.domain.GeoKind
import com.ballastlane.pokemon.domain.PokemonAbility
import com.ballastlane.pokemon.domain.PokemonGeoMetadata
import com.ballastlane.pokemon.domain.PokemonLocalizedName
import com.ballastlane.pokemon.domain.PokemonStat
import com.ballastlane.pokemon.domain.PokemonTag
import com.ballastlane.pokemon.domain.Role
import com.ballastlane.pokemon.domain.SyncRunStatus
import com.ballastlane.pokemon.repository.PokemonGeoMetadataRepository
import com.ballastlane.pokemon.repository.PokemonLocalizedNameRepository
import com.ballastlane.pokemon.repository.PokemonTagRepository
import com.ballastlane.pokemon.support.AbstractApiIntegrationTest
import com.ballastlane.pokemon.web.dto.SyncRunReport
import com.jayway.jsonpath.JsonPath
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldHaveSingleElement
import io.kotest.matchers.date.shouldBeAfter
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Duration
import kotlin.jvm.optionals.getOrNull
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * Full syncs walk the stubbed catalogue (bulbasaur, ivysaur, venusaur; two per page in this profile).
 */
class PokemonSyncIntegrationTest @Autowired constructor(
    private val syncService: PokemonSyncService,
    private val localizedNameRepository: PokemonLocalizedNameRepository,
    private val geoMetadataRepository: PokemonGeoMetadataRepository,
    private val tagRepository: PokemonTagRepository,
) : AbstractApiIntegrationTest() {

    /** Every request in this class is made by an administrator (ADMIN implies USER). */
    private lateinit var adminToken: String

    @BeforeEach
    fun signIn() {
        adminToken = bearerTokenFor("test-admin", Role.USER, Role.ADMIN)
    }

    @Test
    fun fullSyncStoresEveryCataloguePokemon() {
        val run = runFullSync()

        run.status shouldBe SyncRunStatus.COMPLETED
        run.catalogueCount shouldBe 3
        run.created shouldBe 3
        run.updated shouldBe 0
        run.failed shouldBe 0
        run.finishedAt.shouldNotBeNull()
        repository.count() shouldBe 3

        val bulbasaur = repository.findByPokeApiId(1).orElseThrow()
        bulbasaur.name shouldBe "bulbasaur"
        bulbasaur.types.shouldContainExactly("grass", "poison")
        bulbasaur.stats shouldHaveSize 6
        bulbasaur.stats.first() shouldBe PokemonStat("hp", 45)
        bulbasaur.abilities shouldContain PokemonAbility("overgrow", false)
        bulbasaur.speciesId shouldBe 1
        bulbasaur.speciesName shouldBe "bulbasaur"
        bulbasaur.lastSyncedAt.shouldNotBeNull()
    }

    @Test
    fun runningAFullSyncAgainCreatesNoDuplicates() {
        runFullSync()

        val second = runFullSync()

        second.status shouldBe SyncRunStatus.COMPLETED
        second.created shouldBe 0
        second.updated shouldBe 3
        repository.count() shouldBe 3
    }

    @Test
    fun syncsNeverChangeProprietaryData() {
        runFullSync()
        val bulbasaur = repository.findByPokeApiId(1).orElseThrow()
        val bulbasaurId = checkNotNull(bulbasaur.id)
        val firstSync = bulbasaur.lastSyncedAt.shouldNotBeNull()
        localizedNameRepository.save(PokemonLocalizedName(bulbasaur, "es-ES", "Bulbasaur"))
        geoMetadataRepository.save(PokemonGeoMetadata(bulbasaur, GeoKind.REGION, "kanto", "Starter"))
        tagRepository.save(PokemonTag(bulbasaur, "Starter"))

        runFullSync()
        mockMvc.post("/api/v1/admin/sync/pokemon/1") { header(HttpHeaders.AUTHORIZATION, adminToken) }
            .andExpect { status { isOk() } }
        mockMvc.get("/api/v1/pokemon/1") { header(HttpHeaders.AUTHORIZATION, adminToken) }
            .andExpect { status { isOk() } }

        val refreshed = repository.findByPokeApiId(1).orElseThrow()
        refreshed.id shouldBe bulbasaurId
        refreshed.lastSyncedAt.shouldNotBeNull() shouldBeAfter firstSync
        val name = localizedNameRepository.findByPokemonId(bulbasaurId).shouldHaveSize(1).single()
        name.locale shouldBe "es-ES"
        name.name shouldBe "Bulbasaur"
        val geo = geoMetadataRepository.findByPokemonId(bulbasaurId).shouldHaveSize(1).single()
        geo.kind shouldBe GeoKind.REGION
        geo.value shouldBe "kanto"
        geo.note shouldBe "Starter"
        tagRepository.findByPokemonId(bulbasaurId).map { it.tag }.shouldHaveSingleElement("starter")
    }

    @Test
    fun fullSyncCompletesPokemonRecordedByALookup() {
        mockMvc.get("/api/v1/pokemon/1") { header(HttpHeaders.AUTHORIZATION, adminToken) }
            .andExpect { status { isOk() } }
        val recorded = repository.findByPokeApiId(1).orElseThrow()
        recorded.lastSyncedAt.shouldBeNull()

        val run = runFullSync()

        run.created shouldBe 2
        run.updated shouldBe 1
        repository.count() shouldBe 3
        val synced = repository.findByPokeApiId(1).orElseThrow()
        synced.id shouldBe recorded.id
        synced.lastSyncedAt.shouldNotBeNull()
    }

    @Test
    fun recordsPokemonThatFailUpstreamAndSyncsTheRest() {
        val stubId = pokeApi.addStub(
            """
            {
              "priority": 1,
              "request": {
                "method": "GET",
                "urlPath": "/api/v2/pokemon",
                "queryParameters": { "offset": { "equalTo": "0" }, "limit": { "equalTo": "2" } }
              },
              "response": {
                "status": 200,
                "headers": { "Content-Type": "application/json" },
                "jsonBody": {
                  "count": 2,
                  "results": [
                    { "name": "bulbasaur", "url": "https://pokeapi.co/api/v2/pokemon/1/" },
                    { "name": "missingno", "url": "https://pokeapi.co/api/v2/pokemon/9998/" }
                  ]
                }
              }
            }
            """.trimIndent(),
        )
        try {
            val run = runFullSync()

            run.status shouldBe SyncRunStatus.COMPLETED_WITH_ERRORS
            run.created shouldBe 1
            run.failed shouldBe 1
            val failure = run.failures.shouldHaveSize(1).single()
            failure.name shouldBe "missingno"
            failure.reason shouldContain "was not found"
            repository.findByPokeApiId(1).getOrNull().shouldNotBeNull()
        } finally {
            pokeApi.removeStub(stubId)
        }
    }

    @Test
    fun rejectsASecondFullSyncWhileOneIsRunning() {
        // Slow down bulbasaur so the first run is still running when the second one is requested.
        val stubId = pokeApi.addStub(
            """
            {
              "priority": 1,
              "request": { "method": "GET", "urlPath": "/api/v2/pokemon/1" },
              "response": {
                "status": 200,
                "headers": { "Content-Type": "application/json" },
                "bodyFileName": "bulbasaur.json",
                "fixedDelayMilliseconds": 1500
              }
            }
            """.trimIndent(),
        )
        try {
            val runId = startFullSync()

            mockMvc.post("/api/v1/admin/sync/runs") { header(HttpHeaders.AUTHORIZATION, adminToken) }.andExpect {
                status { isConflict() }
                jsonPath("$.title") { value("Synchronization already running") }
                jsonPath("$.runId") { value(runId) }
            }
            mockMvc.get("/api/v1/admin/sync/runs/latest") { header(HttpHeaders.AUTHORIZATION, adminToken) }.andExpect {
                status { isOk() }
                jsonPath("$.id") { value(runId) }
                jsonPath("$.status") { value("RUNNING") }
            }

            awaitFinished(runId).status shouldBe SyncRunStatus.COMPLETED
        } finally {
            pokeApi.removeStub(stubId)
        }
    }

    @Test
    fun runStatusIsServedThroughTheApi() {
        mockMvc.get("/api/v1/admin/sync/runs/latest") { header(HttpHeaders.AUTHORIZATION, adminToken) }
            .andExpect { status { isNotFound() } }

        val runId = checkNotNull(runFullSync().id)

        mockMvc.get("/api/v1/admin/sync/runs/{id}", runId) { header(HttpHeaders.AUTHORIZATION, adminToken) }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("COMPLETED") }
            jsonPath("$.trigger") { value("API") }
            jsonPath("$.created") { value(3) }
        }
        mockMvc.get("/api/v1/admin/sync/runs/{id}", runId + 1000) { header(HttpHeaders.AUTHORIZATION, adminToken) }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun syncsOnePokemon() {
        mockMvc.post("/api/v1/admin/sync/pokemon/PIKACHU") { header(HttpHeaders.AUTHORIZATION, adminToken) }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(25) }
            jsonPath("$.name") { value("pikachu") }
            jsonPath("$.outcome") { value("CREATED") }
            jsonPath("$.lastSyncedAt") { exists() }
        }
        mockMvc.post("/api/v1/admin/sync/pokemon/25") { header(HttpHeaders.AUTHORIZATION, adminToken) }.andExpect {
            status { isOk() }
            jsonPath("$.outcome") { value("UPDATED") }
        }

        val pikachu = repository.findByPokeApiId(25).orElseThrow()
        pikachu.stats shouldHaveSize 6
        pikachu.speciesId shouldBe 25
        repository.count() shouldBe 1
    }

    @Test
    fun singleSyncRejectsUnknownAndInvalidPokemon() {
        mockMvc.post("/api/v1/admin/sync/pokemon/missingno") { header(HttpHeaders.AUTHORIZATION, adminToken) }.andExpect {
            status { isNotFound() }
            jsonPath("$.title") { value("Pokemon not found") }
        }
        mockMvc.post("/api/v1/admin/sync/pokemon/pika_chu!") { header(HttpHeaders.AUTHORIZATION, adminToken) }
            .andExpect { status { isBadRequest() } }
        repository.count() shouldBe 0
    }

    private fun runFullSync(): SyncRunReport = awaitFinished(startFullSync())

    private fun startFullSync(): Long {
        val body = mockMvc.post("/api/v1/admin/sync/runs") { header(HttpHeaders.AUTHORIZATION, adminToken) }
            .andExpect {
                status { isAccepted() }
                header { exists("Location") }
                jsonPath("$.status") { value("RUNNING") }
            }
            .andReturn().response.contentAsString
        return JsonPath.read<Number>(body, "$.id").toLong()
    }

    private fun awaitFinished(runId: Long): SyncRunReport = await().atMost(Duration.ofSeconds(20))
        .until({ syncService.getRun(runId) }, { it.status != SyncRunStatus.RUNNING })
}
