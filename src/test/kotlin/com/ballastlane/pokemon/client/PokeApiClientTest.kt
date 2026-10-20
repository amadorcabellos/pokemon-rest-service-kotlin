package com.ballastlane.pokemon.client

import com.ballastlane.pokemon.exception.PokeApiException
import com.ballastlane.pokemon.exception.PokemonNotFoundException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldStartWith
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class PokeApiClientTest {

    private lateinit var server: MockRestServiceServer

    private lateinit var client: PokeApiClient

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder().baseUrl(BASE_URL)
        server = MockRestServiceServer.bindTo(builder).build()
        client = PokeApiClient(builder.build())
    }

    @Test
    fun fetchPokemonMapsResponse() {
        server.expect(requestTo("$BASE_URL/pokemon/pikachu"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(ClassPathResource("pokeapi/pikachu.json"), MediaType.APPLICATION_JSON))

        val pokemon = client.fetchPokemon("pikachu")

        pokemon.id shouldBe 25
        pokemon.name shouldBe "pikachu"
        pokemon.baseExperience shouldBe 112
        pokemon.sprites?.frontDefault shouldEndWith "/25.png"
        pokemon.types.orEmpty().map { it.type?.name }.shouldContainExactly("electric")
        pokemon.abilities.orEmpty().map { it.ability?.name to it.hidden }
            .shouldContainExactly("static" to false, "lightning-rod" to true)
        pokemon.sprites?.other?.officialArtwork?.frontDefault shouldEndWith "/official-artwork/25.png"
        pokemon.stats.orEmpty().map { it.stat?.name to it.baseStat }.shouldStartWith(listOf("hp" to 35, "attack" to 55))
        pokemon.species?.url shouldEndWith "/pokemon-species/25/"
        server.verify()
    }

    @Test
    fun fetchPokemonThrowsNotFoundOn404() {
        server.expect(requestTo("$BASE_URL/pokemon/missingno")).andRespond(withResourceNotFound())

        shouldThrow<PokemonNotFoundException> { client.fetchPokemon("missingno") }.message shouldContain "missingno"
    }

    @Test
    fun fetchSpeciesParsesFlavorTextsAndChainLink() {
        server.expect(requestTo("$BASE_URL/pokemon-species/25"))
            .andRespond(withSuccess(ClassPathResource("pokeapi/pikachu-species.json"), MediaType.APPLICATION_JSON))

        val species = client.fetchSpecies(25)

        species.id shouldBe 25
        val flavorTexts = species.flavorTextEntries.orEmpty()
        flavorTexts shouldHaveSize 3
        flavorTexts[0].language?.name shouldBe "en"
        flavorTexts[0].version?.name shouldBe "red"
        species.evolutionChain?.url shouldEndWith "/evolution-chain/10/"
        server.verify()
    }

    @Test
    fun fetchEvolutionChainParsesTree() {
        server.expect(requestTo("$BASE_URL/evolution-chain/10"))
            .andRespond(withSuccess(ClassPathResource("pokeapi/evolution-chain-10.json"), MediaType.APPLICATION_JSON))

        val chain = client.fetchEvolutionChain(10)

        chain.chain?.species?.name shouldBe "pichu"
        val pikachu = chain.chain!!.evolvesTo!![0]
        pikachu.species?.name shouldBe "pikachu"
        pikachu.evolutionDetails!![0].minHappiness shouldBe 220
        pikachu.evolvesTo!![0].evolutionDetails!![0].item?.name shouldBe "thunder-stone"
        server.verify()
    }

    @Test
    fun fetchSpeciesAndChainWrapServerErrorsAndNotFound() {
        server.expect(requestTo("$BASE_URL/pokemon-species/25")).andRespond(withServerError())
        server.expect(requestTo("$BASE_URL/evolution-chain/10")).andRespond(withResourceNotFound())

        shouldThrow<PokeApiException> { client.fetchSpecies(25) }
        shouldThrow<PokeApiException> { client.fetchEvolutionChain(10) }
    }

    @Test
    fun fetchPokemonPageParsesCatalogueListing() {
        server.expect(requestTo("$BASE_URL/pokemon?offset=0&limit=2"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(ClassPathResource("pokeapi/pokemon-list.json"), MediaType.APPLICATION_JSON))

        val listing = client.fetchPokemonPage(0, 2)

        listing.count shouldBe 1302
        listing.results.map { it.name }.shouldContainExactly("bulbasaur", "ivysaur")
        listing.results.map { it.id }.shouldContainExactly(1, 2)
        server.verify()
    }

    @Test
    fun fetchPokemonPageWrapsServerErrors() {
        server.expect(requestTo("$BASE_URL/pokemon?offset=0&limit=2")).andRespond(withServerError())

        shouldThrow<PokeApiException> { client.fetchPokemonPage(0, 2) }
    }

    @Test
    fun fetchPokemonWrapsServerErrors() {
        server.expect(requestTo("$BASE_URL/pokemon/pikachu")).andRespond(withServerError())

        shouldThrow<PokeApiException> { client.fetchPokemon("pikachu") }
    }

    private companion object {
        const val BASE_URL = "https://pokeapi.test/api/v2"
    }
}
