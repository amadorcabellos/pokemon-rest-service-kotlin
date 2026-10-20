package com.ballastlane.pokemon.catalog

import com.ballastlane.pokemon.support.AbstractApiIntegrationTest
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get

/**
 * Browsing the catalogue end to end. The WireMock PokeAPI stub exposes a 3-pokemon catalogue (bulbasaur, ivysaur,
 * venusaur); see `wiremock/mappings/catalogue-*.json`.
 */
class PokemonCatalogIntegrationTest : AbstractApiIntegrationTest() {

    @Test
    fun returnsFirstPageWithEntriesAndMetadata() {
        mockMvc.get("/api/v1/pokemon") { param("size", "2") }.andExpect {
            status { isOk() }
            jsonPath("$.page") { value(0) }
            jsonPath("$.size") { value(2) }
            jsonPath("$.totalElements") { value(3) }
            jsonPath("$.totalPages") { value(2) }
            jsonPath("$.content.length()") { value(2) }
            jsonPath("$.content[0].id") { value(1) }
            jsonPath("$.content[0].name") { value("bulbasaur") }
            jsonPath("$.content[0].spriteUrl") {
                value("https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/1.png")
            }
            jsonPath("$.content[0].category[0]") { value("grass") }
            jsonPath("$.content[0].category[1]") { value("poison") }
            jsonPath("$.content[0].massKg") { value(6.9) }
            jsonPath("$.content[0].skills[0].name") { value("overgrow") }
            jsonPath("$.content[0].skills[0].hidden") { value(false) }
            jsonPath("$.content[0].skills[1].name") { value("chlorophyll") }
            jsonPath("$.content[0].skills[1].hidden") { value(true) }
            jsonPath("$.content[1].name") { value("ivysaur") }
        }
    }

    @Test
    fun returnsPartialLastPage() {
        mockMvc.get("/api/v1/pokemon") {
            param("page", "1")
            param("size", "2")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content.length()") { value(1) }
            jsonPath("$.content[0].name") { value("venusaur") }
            jsonPath("$.content[0].massKg") { value(100.0) }
        }
    }

    @Test
    fun servesRepeatedPageFromCacheWithoutCallingPokeApi() {
        mockMvc.get("/api/v1/pokemon") { param("size", "2") }.andExpect { status { isOk() } }
        mockMvc.get("/api/v1/pokemon") { param("size", "2") }.andExpect {
            status { isOk() }
            jsonPath("$.content[1].name") { value("ivysaur") }
        }

        pokeApi.countRequests(CATALOGUE) shouldBe 1
        pokeApi.countRequests("/api/v2/pokemon/1") shouldBe 1
        pokeApi.countRequests("/api/v2/pokemon/2") shouldBe 1
    }

    @Test
    fun reusesCachedEntriesAcrossPageSizes() {
        mockMvc.get("/api/v1/pokemon") { param("size", "2") }.andExpect { status { isOk() } }
        mockMvc.get("/api/v1/pokemon") { param("size", "3") }.andExpect {
            status { isOk() }
            jsonPath("$.content.length()") { value(3) }
        }

        pokeApi.countRequests("/api/v2/pokemon/1") shouldBe 1
        pokeApi.countRequests("/api/v2/pokemon/2") shouldBe 1
        pokeApi.countRequests("/api/v2/pokemon/3") shouldBe 1
    }

    @Test
    fun servesCachedPageWhileCircuitIsOpen() {
        mockMvc.get("/api/v1/pokemon") { param("size", "2") }.andExpect { status { isOk() } }
        pokeApiCircuitBreaker().transitionToOpenState()

        mockMvc.get("/api/v1/pokemon") { param("size", "2") }.andExpect {
            status { isOk() }
            jsonPath("$.content[0].name") { value("bulbasaur") }
        }
    }

    @Test
    fun returns503ForUncachedPageWhileCircuitIsOpen() {
        pokeApiCircuitBreaker().transitionToOpenState()

        mockMvc.get("/api/v1/pokemon") { param("size", "2") }.andExpect { status { isServiceUnavailable() } }
        pokeApi.countRequests(CATALOGUE) shouldBe 0
    }

    @Test
    fun returnsEmptyPagePastTheEnd() {
        mockMvc.get("/api/v1/pokemon") {
            param("page", "5")
            param("size", "2")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content") { isEmpty() }
            jsonPath("$.totalElements") { value(3) }
            jsonPath("$.totalPages") { value(2) }
        }
    }

    @Test
    fun rejectsInvalidPaging() {
        mockMvc.get("/api/v1/pokemon") { param("size", "51") }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("'size' must be between 1 and 50") }
        }
        mockMvc.get("/api/v1/pokemon") { param("page", "-1") }.andExpect { status { isBadRequest() } }

        pokeApi.countRequests(CATALOGUE) shouldBe 0
    }

    private companion object {
        const val CATALOGUE = "/api/v2/pokemon"
    }
}
