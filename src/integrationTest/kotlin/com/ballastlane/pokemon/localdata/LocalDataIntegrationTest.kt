package com.ballastlane.pokemon.localdata

import com.ballastlane.pokemon.domain.Role
import com.ballastlane.pokemon.domain.SyncRunStatus
import com.ballastlane.pokemon.domain.SyncTrigger
import com.ballastlane.pokemon.repository.PokemonGeoMetadataRepository
import com.ballastlane.pokemon.repository.PokemonLocalizedNameRepository
import com.ballastlane.pokemon.repository.PokemonTagRepository
import com.ballastlane.pokemon.support.AbstractApiIntegrationTest
import com.ballastlane.pokemon.sync.PokemonSyncService
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders

/**
 * Local data updates against PostgreSQL, Redis and the WireMock-backed PokeAPI. Pikachu is stored locally by looking
 * it up first, as a client would.
 */
class LocalDataIntegrationTest @Autowired constructor(
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
    fun anUpdateShowsInTheDetailedViewEvenWhenItIsCached() {
        lookUp("pikachu")

        mockMvc.put("/api/v1/pokemon/pikachu") {
            contentType = MediaType.APPLICATION_JSON
            content = PIKACHU_DATA
            header(HttpHeaders.AUTHORIZATION, adminToken)
        }.andExpect {
            status { isOk() }
            header { string("ETag", "\"1\"") }
            jsonPath("$.localizedNames[0].locale") { value("es") }
            jsonPath("$.localizedNames[1].locale") { value("ja-JP") }
            jsonPath("$.localizedNames[1].name") { value("ピカチュウ") }
            jsonPath("$.geoMetadata[0].kind") { value("region") }
            jsonPath("$.tags[0]") { value("mascot") }
            jsonPath("$.modifiedAt") { exists() }
            jsonPath("$.version") { value(1) }
        }

        mockMvc.get("/api/v1/pokemon/PIKACHU") { header(HttpHeaders.AUTHORIZATION, adminToken) }.andExpect {
            status { isOk() }
            header { string("ETag", "\"1\"") }
            jsonPath("$.name") { value("pikachu") }
            jsonPath("$.totalStats") { value(320) }
            jsonPath("$.localData.localizedNames.length()") { value(2) }
            jsonPath("$.localData.geoMetadata[0].note") { value("Viridian Forest") }
            jsonPath("$.localData.tags[1]") { value("starter") }
            jsonPath("$.localData.version") { value(1) }
        }

        // The PokeAPI part still came from the cache.
        pokeApi.countRequests("/api/v2/pokemon/pikachu") shouldBe 1
        val id = checkNotNull(repository.findByPokeApiId(25).orElseThrow().id)
        localizedNameRepository.findByPokemonId(id) shouldHaveSize 2
        tagRepository.findByPokemonId(id) shouldHaveSize 2
    }

    @Test
    fun aPartialUpdateOnlyChangesWhatItSends() {
        lookUp("pikachu")
        mockMvc.put("/api/v1/pokemon/25") {
            contentType = MediaType.APPLICATION_JSON
            content = PIKACHU_DATA
            header(HttpHeaders.AUTHORIZATION, adminToken)
        }.andExpect { status { isOk() } }

        mockMvc.patch("/api/v1/pokemon/pikachu") {
            contentType = MERGE_PATCH
            content = """{ "tags": [ "electric" ] }"""
            header(HttpHeaders.AUTHORIZATION, adminToken)
        }.andExpect {
            status { isOk() }
            jsonPath("$.tags.length()") { value(1) }
            jsonPath("$.tags[0]") { value("electric") }
            jsonPath("$.localizedNames.length()") { value(2) }
            jsonPath("$.geoMetadata.length()") { value(1) }
            jsonPath("$.version") { value(2) }
        }

        mockMvc.patch("/api/v1/pokemon/pikachu") {
            contentType = MERGE_PATCH
            content = """{ "tags": null, "geoMetadata": null }"""
            header(HttpHeaders.AUTHORIZATION, adminToken)
        }.andExpect {
            status { isOk() }
            jsonPath("$.tags") { isEmpty() }
            jsonPath("$.geoMetadata") { isEmpty() }
            jsonPath("$.localizedNames.length()") { value(2) }
            jsonPath("$.version") { value(3) }
        }

        // Sending the same data again changes nothing, so the version stays.
        mockMvc.patch("/api/v1/pokemon/pikachu") {
            contentType = MERGE_PATCH
            content = """{ "tags": [] }"""
            header(HttpHeaders.AUTHORIZATION, adminToken)
        }.andExpect {
            status { isOk() }
            header { string("ETag", "\"3\"") }
        }
    }

    @Test
    fun storesRepeatedGeoEntries() {
        lookUp("pikachu")

        mockMvc.patch("/api/v1/pokemon/pikachu") {
            contentType = MERGE_PATCH
            content = """{ "geoMetadata": [ { "kind": "area", "value": "route 1" }, { "kind": "area", "value": "route 1" } ] }"""
            header(HttpHeaders.AUTHORIZATION, adminToken)
        }.andExpect {
            status { isOk() }
            jsonPath("$.geoMetadata.length()") { value(2) }
        }

        geoMetadataRepository.findByPokemonId(checkNotNull(repository.findByPokeApiId(25).orElseThrow().id)) shouldHaveSize 2
    }

    @Test
    fun invalidRequestsChangeNothing() {
        lookUp("pikachu")
        mockMvc.put("/api/v1/pokemon/pikachu") {
            contentType = MediaType.APPLICATION_JSON
            content = PIKACHU_DATA
            header(HttpHeaders.AUTHORIZATION, adminToken)
        }.andExpect { status { isOk() } }

        mockMvc.put("/api/v1/pokemon/pikachu") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "name": "raichu", "localizedNames": [], "geoMetadata": [], "tags": [] }"""
            header(HttpHeaders.AUTHORIZATION, adminToken)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("name") }
            jsonPath("$.errors[0].message") { value("is read only") }
        }
        mockMvc.patch("/api/v1/pokemon/pikachu") {
            contentType = MERGE_PATCH
            content = """{ "tags": [ "ok", "OK" ], "localizedNames": [ { "locale": "??", "name": "x" } ] }"""
            header(HttpHeaders.AUTHORIZATION, adminToken)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.errors.length()") { value(2) }
        }
        mockMvc.patch("/api/v1/pokemon/pikachu") {
            contentType = MERGE_PATCH
            content = """{ "tags": [] } []"""
            header(HttpHeaders.AUTHORIZATION, adminToken)
        }.andExpect { status { isBadRequest() } }
        mockMvc.patch("/api/v1/pokemon/pikachu") {
            contentType = MediaType.TEXT_PLAIN
            content = "tags"
            header(HttpHeaders.AUTHORIZATION, adminToken)
        }.andExpect { status { isUnsupportedMediaType() } }

        mockMvc.get("/api/v1/pokemon/pikachu") { header(HttpHeaders.AUTHORIZATION, adminToken) }.andExpect {
            jsonPath("$.localData.tags.length()") { value(2) }
            jsonPath("$.localData.localizedNames.length()") { value(2) }
            jsonPath("$.localData.version") { value(1) }
        }
    }

    @Test
    fun onlyPokemonStoredLocallyCanBeUpdated() {
        mockMvc.patch("/api/v1/pokemon/pikachu") {
            contentType = MERGE_PATCH
            content = """{ "tags": [ "x" ] }"""
            header(HttpHeaders.AUTHORIZATION, adminToken)
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.title") { value("Pokemon not found") }
        }
        pokeApi.countRequests("/api/v2/pokemon/pikachu") shouldBe 0

        // Looking it up stores it, and from then on it can be updated.
        lookUp("pikachu")
        mockMvc.patch("/api/v1/pokemon/pikachu") {
            contentType = MERGE_PATCH
            content = """{ "tags": [ "x" ] }"""
            header(HttpHeaders.AUTHORIZATION, adminToken)
        }.andExpect { status { isOk() } }
    }

    @Test
    fun syncsKeepLocalDataAndItsVersion() {
        runFullSync()
        mockMvc.put("/api/v1/pokemon/bulbasaur") {
            contentType = MediaType.APPLICATION_JSON
            content = PIKACHU_DATA
            header(HttpHeaders.AUTHORIZATION, adminToken)
        }.andExpect { status { isOk() } }
        val before = repository.findLocalDataHeaderByPokeApiId(1).orElseThrow()

        runFullSync()
        mockMvc.post("/api/v1/admin/sync/pokemon/1") { header(HttpHeaders.AUTHORIZATION, adminToken) }
            .andExpect { status { isOk() } }

        val after = repository.findLocalDataHeaderByPokeApiId(1).orElseThrow()
        after shouldBe before
        after.version shouldBe 1
        tagRepository.findByPokemonId(after.pokemonId) shouldHaveSize 2
        localizedNameRepository.findByPokemonId(after.pokemonId) shouldHaveSize 2
        geoMetadataRepository.findByPokemonId(after.pokemonId) shouldHaveSize 1
    }

    @Test
    fun onlyOneOfTwoUpdatesBasedOnTheSameVersionWins() {
        lookUp("pikachu")

        val statuses = concurrently(2) { i ->
            MockMvcRequestBuilders.put("/api/v1/pokemon/pikachu")
                .contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "\"0\"")
                .content("""{ "localizedNames": [], "geoMetadata": [], "tags": [ "t$i" ] }""")
        }

        statuses.shouldContainExactlyInAnyOrder(200, 412)
        repository.findLocalDataHeaderByPokeApiId(25).orElseThrow().version shouldBe 1
    }

    @Test
    fun concurrentUpdatesWithoutAVersionAllSucceedAlongsideASync() {
        lookUp("pikachu")
        val updates = 8

        val statuses = concurrently(updates + 1) { i ->
            if (i == updates) {
                MockMvcRequestBuilders.post("/api/v1/admin/sync/pokemon/pikachu")
            } else {
                MockMvcRequestBuilders.patch("/api/v1/pokemon/pikachu").contentType(MERGE_PATCH)
                    .content("""{ "tags": [ "t$i" ], "localizedNames": [ { "locale": "en", "name": "P$i" } ] }""")
            }
        }

        statuses.shouldContainOnly(200)
        val header = repository.findLocalDataHeaderByPokeApiId(25).orElseThrow()
        header.version shouldBe updates.toLong()
        tagRepository.findByPokemonId(header.pokemonId) shouldHaveSize 1
        localizedNameRepository.findByPokemonId(header.pokemonId) shouldHaveSize 1
    }

    private fun lookUp(nameOrId: String) {
        mockMvc.get("/api/v1/pokemon/{nameOrId}", nameOrId) { header(HttpHeaders.AUTHORIZATION, adminToken) }
            .andExpect { status { isOk() } }
    }

    private fun runFullSync() {
        val runId = checkNotNull(syncService.startFullSync(SyncTrigger.API).id)
        await().atMost(Duration.ofSeconds(20))
            .until({ syncService.getRun(runId).status }, { it != SyncRunStatus.RUNNING })
    }

    /**
     * Sends the requests at the same time and returns their status codes.
     */
    private fun concurrently(count: Int, request: (Int) -> MockHttpServletRequestBuilder): List<Int> {
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(count).use { executor ->
            val results = (0 until count).map { i ->
                val builder = request(i)
                executor.submit(
                    Callable {
                        start.await()
                        mockMvc.perform(builder.header(HttpHeaders.AUTHORIZATION, adminToken)).andReturn().response.status
                    },
                )
            }
            start.countDown()
            return results.map { it.get() }
        }
    }

    private companion object {

        val MERGE_PATCH: MediaType = MediaType.parseMediaType("application/merge-patch+json")

        const val PIKACHU_DATA = """{
  "localizedNames": [ { "locale": "ja-jp", "name": "ピカチュウ" }, { "locale": "es", "name": "Pikachu" } ],
  "geoMetadata": [ { "kind": "Region", "value": "kanto", "note": "Viridian Forest" } ],
  "tags": [ "Mascot", "starter" ]
}"""
    }
}
