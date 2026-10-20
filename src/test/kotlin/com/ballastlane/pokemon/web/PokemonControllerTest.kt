package com.ballastlane.pokemon.web

import com.ballastlane.pokemon.config.CatalogProperties
import com.ballastlane.pokemon.exception.PokeApiException
import com.ballastlane.pokemon.exception.PokemonNotFoundException
import com.ballastlane.pokemon.localdata.LocalDataRequestParser
import com.ballastlane.pokemon.localdata.LocalDataService
import com.ballastlane.pokemon.service.PokemonCatalogService
import com.ballastlane.pokemon.service.PokemonDetailsService
import com.ballastlane.pokemon.web.dto.EvolutionStage
import com.ballastlane.pokemon.web.dto.GeoEntry
import com.ballastlane.pokemon.web.dto.LocalData
import com.ballastlane.pokemon.web.dto.LocalizedName
import com.ballastlane.pokemon.web.dto.PokemonDetails
import com.ballastlane.pokemon.web.dto.PokemonSummary
import com.ballastlane.pokemon.web.dto.PokemonSummaryPage
import com.ballastlane.pokemon.web.dto.Skill
import com.ballastlane.pokemon.web.dto.Stat
import com.ninjasquad.springmockk.MockkBean
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import io.mockk.Called
import io.mockk.every
import io.mockk.verify
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(PokemonController::class)
@Import(LocalDataRequestParser::class, WebSecurityTestConfiguration::class)
class PokemonControllerTest @Autowired constructor(private val mockMvc: MockMvc) {

    @MockkBean
    private lateinit var pokemonService: PokemonDetailsService

    @MockkBean
    private lateinit var pokemonCatalogService: PokemonCatalogService

    @MockkBean
    private lateinit var localDataService: LocalDataService

    @TestConfiguration
    @EnableConfigurationProperties(CatalogProperties::class)
    class CatalogPropertiesConfig

    @BeforeEach
    fun noLocalData() {
        every { localDataService.read(any()) } returns LocalData.EMPTY
    }

    @Test
    fun getPokemonReturnsPokemon() {
        every { pokemonService.getPokemon("pikachu") } returns PIKACHU

        mockMvc.get("/api/v1/pokemon/pikachu").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(25) }
            jsonPath("$.name") { value("pikachu") }
            jsonPath("$.imageUrl") { value("artwork.png") }
            jsonPath("$.stats[0].name") { value("hp") }
            jsonPath("$.stats[0].value") { value(35) }
            jsonPath("$.totalStats") { value(125) }
            jsonPath("$.description") { value("It stores electricity.") }
            jsonPath("$.evolution.name") { value("pichu") }
            jsonPath("$.evolution.evolvesVia") { doesNotExist() }
            jsonPath("$.evolution.evolvesTo[0].name") { value("pikachu") }
            jsonPath("$.evolution.evolvesTo[0].current") { value(true) }
            jsonPath("$.evolution.evolvesTo[0].evolvesTo[0].evolvesVia") { value("use thunder-stone") }
            jsonPath("$.localData.tags") { isEmpty() }
            jsonPath("$.localData.version") { value(0) }
            header { string("ETag", "\"0\"") }
        }
    }

    @Test
    fun getPokemonAddsTheCurrentLocalData() {
        every { pokemonService.getPokemon("pikachu") } returns PIKACHU
        every { localDataService.read(25) } returns LocalData(
            listOf(LocalizedName("ja-JP", "ピカチュウ")), listOf(GeoEntry("region", "kanto", null)), listOf("mascot"),
            Instant.parse("2026-10-18T10:00:00Z"), 3,
        )

        mockMvc.get("/api/v1/pokemon/pikachu").andExpect {
            status { isOk() }
            header { string("ETag", "\"3\"") }
            jsonPath("$.name") { value("pikachu") }
            jsonPath("$.localData.localizedNames[0].locale") { value("ja-JP") }
            jsonPath("$.localData.localizedNames[0].name") { value("ピカチュウ") }
            jsonPath("$.localData.geoMetadata[0].kind") { value("region") }
            jsonPath("$.localData.tags[0]") { value("mascot") }
            jsonPath("$.localData.modifiedAt") { value("2026-10-18T10:00:00Z") }
            jsonPath("$.localData.version") { value(3) }
        }
    }

    @Test
    fun getPokemonReturns404WhenNotFound() {
        every { pokemonService.getPokemon("missingno") } throws PokemonNotFoundException("missingno")

        mockMvc.get("/api/v1/pokemon/missingno").andExpect {
            status { isNotFound() }
            jsonPath("$.title") { value("Pokemon not found") }
            jsonPath("$.status") { value(404) }
        }
    }

    @Test
    fun getPokemonReturns502WhenPokeApiFails() {
        every { pokemonService.getPokemon("pikachu") } throws PokeApiException("boom", RuntimeException())

        mockMvc.get("/api/v1/pokemon/pikachu").andExpect {
            status { isBadGateway() }
            jsonPath("$.title") { value("Upstream service error") }
        }
    }

    @Test
    fun getPokemonReturns503WhenCircuitIsOpen() {
        every { pokemonService.getPokemon("pikachu") } throws
            CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("pokeapi"))

        mockMvc.get("/api/v1/pokemon/pikachu").andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.title") { value("Service temporarily unavailable") }
        }
    }

    @Test
    fun getPokemonReturns429WhenRateLimited() {
        every { pokemonService.getPokemon("pikachu") } throws
            RequestNotPermitted.createRequestNotPermitted(RateLimiter.ofDefaults("pokeapi"))

        mockMvc.get("/api/v1/pokemon/pikachu").andExpect {
            status { isTooManyRequests() }
            jsonPath("$.title") { value("Too many requests") }
        }
    }

    @Test
    fun getPokemonRejectsInvalidInput() {
        mockMvc.get("/api/v1/pokemon/pika_chu!").andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("must be a pokemon name or numeric id") }
        }

        verify { pokemonService wasNot Called }
    }

    @Test
    fun listPokemonUsesDefaultPaging() {
        every { pokemonCatalogService.getPage(0, 20) } returns PokemonSummaryPage(listOf(BULBASAUR), 0, 20, 1302, 66)

        mockMvc.get("/api/v1/pokemon").andExpect {
            status { isOk() }
            jsonPath("$.content[0].id") { value(1) }
            jsonPath("$.content[0].spriteUrl") { value("sprite.png") }
            jsonPath("$.content[0].category[0]") { value("grass") }
            jsonPath("$.content[0].category[1]") { value("poison") }
            jsonPath("$.content[0].massKg") { value(6.9) }
            jsonPath("$.content[0].skills[1].name") { value("chlorophyll") }
            jsonPath("$.content[0].skills[1].hidden") { value(true) }
            jsonPath("$.page") { value(0) }
            jsonPath("$.size") { value(20) }
            jsonPath("$.totalElements") { value(1302) }
            jsonPath("$.totalPages") { value(66) }
        }
    }

    @Test
    fun listPokemonPassesCustomPaging() {
        every { pokemonCatalogService.getPage(3, 50) } returns PokemonSummaryPage(listOf(), 3, 50, 100, 2)

        mockMvc.get("/api/v1/pokemon") {
            param("page", "3")
            param("size", "50")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content") { isEmpty() }
            jsonPath("$.page") { value(3) }
        }
    }

    @Test
    fun listPokemonFiltersByTrimmedName() {
        every { pokemonCatalogService.searchByName("Saur", 1, 10) } returns
            PokemonSummaryPage(listOf(BULBASAUR), 1, 10, 11, 2)

        mockMvc.get("/api/v1/pokemon") {
            param("page", "1")
            param("size", "10")
            param("name", "  Saur ")
        }.andExpect {
            status { isOk() }
            jsonPath("$.content[0].name") { value("bulbasaur") }
            jsonPath("$.totalElements") { value(11) }
        }
    }

    @Test
    fun listPokemonIgnoresBlankName() {
        every { pokemonCatalogService.getPage(0, 20) } returns PokemonSummaryPage(listOf(), 0, 20, 1302, 66)

        mockMvc.get("/api/v1/pokemon") { param("name", "  ") }.andExpect { status { isOk() } }
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '"',
        value = [
            "page=-1        | 'page' must be 0 or greater",
            "size=0         | 'size' must be between 1 and 50",
            "size=51        | 'size' must be between 1 and 50",
            "size=abc       | 'size' must be a number",
            "page=1.5       | 'page' must be a number",
            "name=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa | 'name' must be at most 100 characters",
        ],
    )
    fun listPokemonRejectsInvalidPaging(query: String, detail: String) {
        mockMvc.get("/api/v1/pokemon?$query").andExpect {
            status { isBadRequest() }
            jsonPath("$.title") { value("Invalid request") }
            jsonPath("$.detail") { value(detail) }
        }

        verify { pokemonCatalogService wasNot Called }
    }

    @Test
    fun listPokemonMapsUpstreamFailures() {
        every { pokemonCatalogService.getPage(0, 20) } throws PokeApiException("boom", RuntimeException())
        mockMvc.get("/api/v1/pokemon").andExpect { status { isBadGateway() } }

        every { pokemonCatalogService.getPage(1, 20) } throws
            CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("pokeapi"))
        mockMvc.get("/api/v1/pokemon") { param("page", "1") }.andExpect { status { isServiceUnavailable() } }

        every { pokemonCatalogService.getPage(2, 20) } throws
            RequestNotPermitted.createRequestNotPermitted(RateLimiter.ofDefaults("pokeapi"))
        mockMvc.get("/api/v1/pokemon") { param("page", "2") }.andExpect { status { isTooManyRequests() } }
    }

    private companion object {

        val PIKACHU = PokemonDetails(
            25, "pikachu", "artwork.png", listOf(Stat("hp", 35), Stat("speed", 90)), 125, "It stores electricity.",
            EvolutionStage(
                172, "pichu", null, false,
                listOf(
                    EvolutionStage(
                        25, "pikachu", "friendship", true,
                        listOf(EvolutionStage(26, "raichu", "use thunder-stone", false, listOf())),
                    ),
                ),
            ),
        )

        val BULBASAUR = PokemonSummary(
            1, "bulbasaur", "sprite.png", listOf("grass", "poison"), BigDecimal("6.9"),
            listOf(Skill("overgrow", false), Skill("chlorophyll", true)),
        )
    }
}
