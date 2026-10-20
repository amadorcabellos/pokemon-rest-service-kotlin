package com.ballastlane.pokemon.config

import com.ballastlane.pokemon.web.dto.EvolutionStage
import com.ballastlane.pokemon.web.dto.PokemonDetails
import com.ballastlane.pokemon.web.dto.PokemonSummary
import com.ballastlane.pokemon.web.dto.PokemonSummaryPage
import java.time.Duration
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.cache.RedisCacheConfiguration
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

@Configuration(proxyBeanMethods = false)
@EnableCaching
class CacheConfig {

    @Bean
    fun pokemonCacheCustomizer(cacheProperties: CacheProperties): RedisCacheManagerBuilderCustomizer {
        val ttl = cacheProperties.pokemonTtl
        return RedisCacheManagerBuilderCustomizer { builder ->
            builder
                .withCacheConfiguration(POKEMON_DETAILS_CACHE, jsonCache(PokemonDetails::class.java, ttl))
                .withCacheConfiguration(EVOLUTION_CHAIN_CACHE, jsonCache(EvolutionStage::class.java, ttl))
                .withCacheConfiguration(POKEMON_PAGE_CACHE, jsonCache(PokemonSummaryPage::class.java, ttl))
                .withCacheConfiguration(POKEMON_SUMMARY_CACHE, jsonCache(PokemonSummary::class.java, ttl))
        }
    }

    companion object {

        /** Detailed pokemon views, keyed by the normalized name or id. */
        const val POKEMON_DETAILS_CACHE = "pokemon-details"

        /** Evolution chain trees (without the "current" marker), keyed by chain id; shared by a whole family. */
        const val EVOLUTION_CHAIN_CACHE = "evolution-chain"

        /** Catalogue pages, keyed by `page:size`. */
        const val POKEMON_PAGE_CACHE = "pokemon-page"

        /** Catalogue entries, keyed by PokeAPI id; shared across pages of any size. */
        const val POKEMON_SUMMARY_CACHE = "pokemon-summary"

        /**
         * The serializer's default mapper (`JsonMapper.shared()`) plus the Kotlin module, so cached values can be read
         * back into Kotlin data classes.
         */
        private val CACHE_MAPPER: JsonMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

        private fun jsonCache(valueType: Class<*>, ttl: Duration): RedisCacheConfiguration =
            RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(JacksonJsonRedisSerializer(CACHE_MAPPER, valueType)))
    }
}
