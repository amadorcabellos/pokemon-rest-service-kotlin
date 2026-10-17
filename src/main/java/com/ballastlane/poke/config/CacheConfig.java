package com.ballastlane.poke.config;

import com.ballastlane.poke.web.dto.PokemonResponse;
import com.ballastlane.poke.web.dto.PokemonSummary;
import com.ballastlane.poke.web.dto.PokemonSummaryPage;
import java.time.Duration;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfig {

	/** Single pokemon lookups, keyed by the normalized name or id. */
	public static final String POKEMON_CACHE = "pokemon";

	/** Catalogue pages, keyed by {@code page:size}. */
	public static final String POKEMON_PAGE_CACHE = "pokemon-page";

	/** Catalogue entries, keyed by PokeAPI id; shared across pages of any size. */
	public static final String POKEMON_SUMMARY_CACHE = "pokemon-summary";

	@Bean
	RedisCacheManagerBuilderCustomizer pokemonCacheCustomizer(CacheProperties cacheProperties) {
		Duration ttl = cacheProperties.pokemonTtl();
		return builder -> builder
				.withCacheConfiguration(POKEMON_CACHE, jsonCache(PokemonResponse.class, ttl))
				.withCacheConfiguration(POKEMON_PAGE_CACHE, jsonCache(PokemonSummaryPage.class, ttl))
				.withCacheConfiguration(POKEMON_SUMMARY_CACHE, jsonCache(PokemonSummary.class, ttl));
	}

	private static RedisCacheConfiguration jsonCache(Class<?> valueType, Duration ttl) {
		return RedisCacheConfiguration.defaultCacheConfig()
				.entryTtl(ttl)
				.disableCachingNullValues()
				.serializeValuesWith(SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(valueType)));
	}

}
