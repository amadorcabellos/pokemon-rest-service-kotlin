package com.ballastlane.pokemon.support

import com.ballastlane.pokemon.auth.AuthService
import com.ballastlane.pokemon.auth.SessionJwtAuthenticationConverter
import com.ballastlane.pokemon.client.PokeApiClient
import com.ballastlane.pokemon.domain.GeoKind
import com.ballastlane.pokemon.domain.Pokemon
import com.ballastlane.pokemon.localdata.LocalDataService
import com.ballastlane.pokemon.localdata.LocalDataUpdate
import com.ballastlane.pokemon.localdata.LocalDataUpdate.GeoEntryValue
import com.ballastlane.pokemon.localdata.LocalDataUpdate.LocalizedNameValue
import com.ballastlane.pokemon.repository.PokemonLocalizedNameRepository
import com.ballastlane.pokemon.service.EvolutionChainLoader
import com.ballastlane.pokemon.service.PokemonCatalogService
import com.ballastlane.pokemon.service.PokemonDetailsService
import com.ballastlane.pokemon.service.PokemonSummaryLoader
import com.ballastlane.pokemon.sync.SyncRunStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.dao.DataIntegrityViolationException

/**
 * Kotlin classes are final unless opened, and a final class silently loses its caching, transactions and
 * resilience. This checks the proxied beans really are proxies, and that a transaction still rolls back.
 */
class SpringProxiesIntegrationTest @Autowired constructor(
    private val context: ApplicationContext,
    private val localDataService: LocalDataService,
    private val localizedNameRepository: PokemonLocalizedNameRepository,
) : AbstractApiIntegrationTest() {

    @Test
    fun beansWithCachingTransactionsOrResilienceAreProxied() {
        listOf(
            PokeApiClient::class.java, PokemonDetailsService::class.java, EvolutionChainLoader::class.java,
            PokemonSummaryLoader::class.java, PokemonCatalogService::class.java, LocalDataService::class.java,
            AuthService::class.java, SyncRunStore::class.java, SessionJwtAuthenticationConverter::class.java,
        ).forEach { type ->
            withClue(type.simpleName) { AopUtils.isAopProxy(context.getBean(type)) shouldBe true }
        }
    }

    @Test
    fun aLocalDataUpdateThatFailsPartwayChangesNothing() {
        repository.save(Pokemon(25, "pikachu", 4, 60, 112, null, listOf("electric")))
        val tooLongNote = "x".repeat(1001)

        // The localized name is written first, then the geographical entry breaks a database rule.
        shouldThrow<DataIntegrityViolationException> {
            localDataService.update(
                "pikachu",
                LocalDataUpdate(
                    listOf(LocalizedNameValue("en", "Pikachu")),
                    listOf(GeoEntryValue(GeoKind.REGION, "kanto", tooLongNote)),
                    null,
                ),
                null,
            )
        }

        val header = repository.findLocalDataHeaderByPokeApiId(25).orElseThrow()
        header.version shouldBe 0
        localizedNameRepository.findByPokemonId(header.pokemonId).shouldBeEmpty()
    }
}
