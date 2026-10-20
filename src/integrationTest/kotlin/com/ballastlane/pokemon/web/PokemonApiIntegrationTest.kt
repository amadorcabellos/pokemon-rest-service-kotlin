package com.ballastlane.pokemon.web

import com.ballastlane.pokemon.support.AbstractApiIntegrationTest
import com.ballastlane.pokemon.web.dto.PokemonDetails
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.jvm.optionals.getOrNull
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get

/**
 * Detailed pokemon lookup end to end: HTTP layer, PokeAPI (WireMock: pokemon → species → evolution chain), Redis
 * caching and recording in PostgreSQL.
 */
class PokemonApiIntegrationTest : AbstractApiIntegrationTest() {

    @Test
    fun returnsDetailedView() {
        mockMvc.get("/api/v1/pokemon/pikachu").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(25) }
            jsonPath("$.name") { value("pikachu") }
            jsonPath("$.imageUrl") {
                value("https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/25.png")
            }
            jsonPath("$.stats.length()") { value(6) }
            jsonPath("$.stats[0].name") { value("hp") }
            jsonPath("$.stats[0].value") { value(35) }
            jsonPath("$.stats[5].name") { value("speed") }
            jsonPath("$.stats[5].value") { value(90) }
            jsonPath("$.totalStats") { value(320) }
            jsonPath("$.description") {
                value("Pikachu that can generate powerful electricity have cheek sacs that are extra soft and super stretchy.")
            }
            jsonPath("$.evolution.name") { value("pichu") }
            jsonPath("$.evolution.current") { value(false) }
            jsonPath("$.evolution.evolvesTo[0].name") { value("pikachu") }
            jsonPath("$.evolution.evolvesTo[0].evolvesVia") { value("friendship") }
            jsonPath("$.evolution.evolvesTo[0].current") { value(true) }
            jsonPath("$.evolution.evolvesTo[0].evolvesTo[0].name") { value("raichu") }
            jsonPath("$.evolution.evolvesTo[0].evolvesTo[0].evolvesVia") { value("use thunder-stone") }
            jsonPath("$.evolution.evolvesTo[0].evolvesTo[0].evolvesTo") { isEmpty() }
            jsonPath("$.localData.localizedNames") { isEmpty() }
            jsonPath("$.localData.geoMetadata") { isEmpty() }
            jsonPath("$.localData.tags") { isEmpty() }
            jsonPath("$.localData.modifiedAt") { isEmpty() }
            jsonPath("$.localData.version") { value(0) }
            header { string("ETag", "\"0\"") }
        }
    }

    @Test
    fun servesRepeatedLookupFromCache() {
        mockMvc.get("/api/v1/pokemon/pikachu").andExpect { status { isOk() } }
        val cached = pokemonDetailsCache().get("pikachu", PokemonDetails::class.java).shouldNotBeNull()
        cached.name shouldBe "pikachu"
        // Local data is read fresh on every request, never cached.
        cached.localData.shouldBeNull()

        mockMvc.get("/api/v1/pokemon/PIKACHU").andExpect { status { isOk() } }

        pokeApi.countRequests("/api/v2/pokemon/pikachu") shouldBe 1
        pokeApi.countRequests("/api/v2/pokemon-species/25") shouldBe 1
        pokeApi.countRequests("/api/v2/evolution-chain/10") shouldBe 1
    }

    @Test
    fun familyMembersShareOneEvolutionChainRequest() {
        mockMvc.get("/api/v1/pokemon/pichu").andExpect {
            status { isOk() }
            jsonPath("$.evolution.name") { value("pichu") }
            jsonPath("$.evolution.current") { value(true) }
            jsonPath("$.evolution.evolvesTo[0].current") { value(false) }
        }
        mockMvc.get("/api/v1/pokemon/pikachu").andExpect {
            status { isOk() }
            jsonPath("$.evolution.current") { value(false) }
            jsonPath("$.evolution.evolvesTo[0].current") { value(true) }
        }

        pokeApi.countRequests("/api/v2/evolution-chain/10") shouldBe 1
    }

    @Test
    fun refetchesFromPokeApiWhenViewIsNoLongerCached() {
        mockMvc.get("/api/v1/pokemon/pikachu").andExpect { status { isOk() } }
        pokemonDetailsCache().invalidate()

        mockMvc.get("/api/v1/pokemon/pikachu").andExpect { status { isOk() } }

        pokeApi.countRequests("/api/v2/pokemon/pikachu") shouldBe 2
        pokeApi.countRequests("/api/v2/evolution-chain/10") shouldBe 1
    }

    @Test
    fun recordsLookedUpPokemonInPostgresOnce() {
        mockMvc.get("/api/v1/pokemon/pikachu").andExpect { status { isOk() } }
        mockMvc.get("/api/v1/pokemon/25").andExpect { status { isOk() } }

        repository.count() shouldBe 1
        repository.findByPokeApiId(25).getOrNull().shouldNotBeNull().types.shouldContainExactly("electric")
    }

    @Test
    fun servesCachedViewWhileCircuitIsOpen() {
        mockMvc.get("/api/v1/pokemon/pikachu").andExpect { status { isOk() } }
        pokeApiCircuitBreaker().transitionToOpenState()

        mockMvc.get("/api/v1/pokemon/pikachu").andExpect {
            status { isOk() }
            jsonPath("$.totalStats") { value(320) }
        }
    }

    @Test
    fun returns404WhenPokemonDoesNotExist() {
        mockMvc.get("/api/v1/pokemon/missingno").andExpect {
            status { isNotFound() }
            jsonPath("$.title") { value("Pokemon not found") }
        }

        repository.count() shouldBe 0
    }

    @Test
    fun returns400ForInvalidName() {
        mockMvc.get("/api/v1/pokemon/pika_chu!").andExpect { status { isBadRequest() } }
    }

    @Test
    fun failsWholeLookupWithoutCachingWhenSpeciesFails() {
        mockMvc.get("/api/v1/pokemon/broken").andExpect { status { isBadGateway() } }

        pokeApi.countRequests("/api/v2/pokemon-species/9999") shouldBe 3
        pokemonDetailsCache().get("broken").shouldBeNull()
    }

    @Test
    fun returns502WhenPokeApiFails() {
        mockMvc.get("/api/v1/pokemon/glitch").andExpect { status { isBadGateway() } }
    }

    @Test
    fun healthEndpointIsUp() {
        mockMvc.get("/actuator/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
    }
}
