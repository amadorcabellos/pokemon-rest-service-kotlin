package com.ballastlane.pokemon.web

import com.ballastlane.pokemon.config.CatalogProperties
import com.ballastlane.pokemon.config.LocalDataConfig
import com.ballastlane.pokemon.config.LocalDataProperties
import com.ballastlane.pokemon.exception.LocalDataVersionMismatchException
import com.ballastlane.pokemon.exception.PokemonNotStoredLocallyException
import com.ballastlane.pokemon.localdata.IfMatch
import com.ballastlane.pokemon.localdata.LocalDataRequestParser
import com.ballastlane.pokemon.localdata.LocalDataService
import com.ballastlane.pokemon.localdata.LocalDataUpdate
import com.ballastlane.pokemon.service.PokemonCatalogService
import com.ballastlane.pokemon.service.PokemonDetailsService
import com.ballastlane.pokemon.web.dto.LocalData
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.put

@WebMvcTest(PokemonController::class)
@Import(LocalDataRequestParser::class, LocalDataConfig::class, WebSecurityTestConfiguration::class)
@WithMockUser(roles = ["USER"])
class PokemonLocalDataControllerTest @Autowired constructor(private val mockMvc: MockMvc) {

    @MockkBean
    private lateinit var pokemonDetailsService: PokemonDetailsService

    @MockkBean
    private lateinit var pokemonCatalogService: PokemonCatalogService

    @MockkBean
    private lateinit var localDataService: LocalDataService

    @TestConfiguration
    @EnableConfigurationProperties(CatalogProperties::class, LocalDataProperties::class)
    class PropertiesConfig

    @Test
    fun replacesLocalData() {
        every { localDataService.update("pikachu", any(), null) } returns UPDATED

        mockMvc.put("/api/v1/pokemon/pikachu") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "localizedNames": [], "geoMetadata": [], "tags": [ "Mascot" ] }"""
        }.andExpect {
            status { isOk() }
            header { string("ETag", "\"4\"") }
            jsonPath("$.tags[0]") { value("mascot") }
            jsonPath("$.version") { value(4) }
        }

        val update = slot<LocalDataUpdate>()
        verify(exactly = 1) { localDataService.update("pikachu", capture(update), null) }
        update.captured shouldBe LocalDataUpdate(listOf(), listOf(), listOf("mascot"))
    }

    @Test
    fun patchesLocalDataWithMergePatchOrPlainJson() {
        every { localDataService.update("25", any(), any()) } returns UPDATED

        mockMvc.patch("/api/v1/pokemon/25") {
            contentType = MERGE_PATCH
            header("If-Match", "\"3\"")
            content = """{ "tags": [ "mascot" ] }"""
        }.andExpect {
            status { isOk() }
            header { string("ETag", "\"4\"") }
        }
        mockMvc.patch("/api/v1/pokemon/25") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "tags": null }"""
        }.andExpect { status { isOk() } }

        val ifMatch = slot<IfMatch>()
        verify(exactly = 1) {
            localDataService.update("25", LocalDataUpdate(null, null, listOf("mascot")), capture(ifMatch))
        }
        ifMatch.captured.matches(3) shouldBe true
        verify(exactly = 1) { localDataService.update("25", LocalDataUpdate(null, null, listOf()), null) }
    }

    @Test
    fun listsEveryInvalidField() {
        mockMvc.put("/api/v1/pokemon/pikachu") {
            contentType = MediaType.APPLICATION_JSON
            content = """{ "name": "raichu", "geoMetadata": [ { "kind": "planet", "value": "kanto" } ], "tags": [] }"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.title") { value("Invalid request") }
            jsonPath("$.errors.length()") { value(3) }
            jsonPath("$.errors[0].field") { value("name") }
            jsonPath("$.errors[0].message") { value("is read only") }
            jsonPath("$.errors[1].field") { value("localizedNames") }
            jsonPath("$.errors[2].field") { value("geoMetadata[0].kind") }
        }

        verify { localDataService wasNot Called }
    }

    @Test
    fun rejectsMalformedAndMissingBodies() {
        mockMvc.patch("/api/v1/pokemon/pikachu") {
            contentType = MERGE_PATCH
            content = """{ "tags": [ """
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].field") { value("body") }
            jsonPath("$.errors[0].message") { value("is not valid JSON") }
        }
        mockMvc.patch("/api/v1/pokemon/pikachu") {
            contentType = MERGE_PATCH
            content = "[]"
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].message") { value("must be a JSON object") }
        }
        mockMvc.put("/api/v1/pokemon/pikachu") { contentType = MediaType.APPLICATION_JSON }.andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].message") { value("is required") }
        }

        verify { localDataService wasNot Called }
    }

    @Test
    fun rejectsInvalidPathAndIfMatch() {
        mockMvc.patch("/api/v1/pokemon/pika_chu!") {
            contentType = MERGE_PATCH
            content = "{}"
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("must be a pokemon name or numeric id") }
            jsonPath("$.errors[0].field") { value("nameOrId") }
        }
        mockMvc.patch("/api/v1/pokemon/pikachu") {
            contentType = MERGE_PATCH
            header("If-Match", "3")
            content = "{}"
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("'If-Match' must be '*' or a list of entity tags such as \"3\"") }
        }

        verify { localDataService wasNot Called }
    }

    @Test
    fun returns404WhenThePokemonIsNotStoredLocally() {
        every { localDataService.update("raichu", any(), any()) } throws PokemonNotStoredLocallyException("raichu")

        mockMvc.patch("/api/v1/pokemon/raichu") {
            contentType = MERGE_PATCH
            content = "{}"
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.title") { value("Pokemon not found") }
            jsonPath("$.detail") { value("Pokemon 'raichu' is not stored locally; look it up or synchronize it first") }
        }
    }

    @Test
    fun returns412ForAStaleVersion() {
        every { localDataService.update("pikachu", any(), any()) } throws LocalDataVersionMismatchException(5)

        mockMvc.patch("/api/v1/pokemon/pikachu") {
            contentType = MERGE_PATCH
            header("If-Match", "\"3\"")
            content = "{}"
        }.andExpect {
            status { isPreconditionFailed() }
            jsonPath("$.title") { value("Local data has changed") }
            jsonPath("$.currentVersion") { value(5) }
        }
    }

    @Test
    fun rejectsOtherContentTypesMethodsAndLargeBodies() {
        mockMvc.put("/api/v1/pokemon/pikachu") {
            contentType = MediaType.TEXT_PLAIN
            content = "tags"
        }.andExpect {
            status { isUnsupportedMediaType() }
            jsonPath("$.title") { value("Unsupported media type") }
        }
        mockMvc.put("/api/v1/pokemon/pikachu") {
            contentType = MERGE_PATCH
            content = "{}"
        }.andExpect { status { isUnsupportedMediaType() } }
        mockMvc.delete("/api/v1/pokemon/pikachu").andExpect {
            status { isMethodNotAllowed() }
            header { string("Allow", containsString("PATCH")) }
            jsonPath("$.title") { value("Method not allowed") }
        }
        val tooLarge = """{ "tags": [ "${"a".repeat(64 * 1024)}" ] }"""
        mockMvc.patch("/api/v1/pokemon/pikachu") {
            contentType = MERGE_PATCH
            content = tooLarge
        }.andExpect {
            status { isContentTooLarge() }
            jsonPath("$.title") { value("Request body too large") }
        }

        verify { localDataService wasNot Called }
    }

    private companion object {

        val MERGE_PATCH: MediaType = MediaType.parseMediaType("application/merge-patch+json")

        val UPDATED = LocalData(listOf(), listOf(), listOf("mascot"), Instant.parse("2026-10-18T10:00:00Z"), 4)
    }
}
