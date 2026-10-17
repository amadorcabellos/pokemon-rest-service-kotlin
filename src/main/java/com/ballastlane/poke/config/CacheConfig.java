package com.ballastlane.poke.config;

import com.ballastlane.poke.web.dto.PokemonResponse;
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

	public static final String POKEMON_CACHE = "pokemon";

	@Bean
	RedisCacheManagerBuilderCustomizer pokemonCacheCustomizer(CacheProperties cacheProperties) {
		RedisCacheConfiguration pokemonCache = RedisCacheConfiguration.defaultCacheConfig()
				.entryTtl(cacheProperties.pokemonTtl())
				.disableCachingNullValues()
				.serializeValuesWith(SerializationPair.fromSerializer(
						new JacksonJsonRedisSerializer<>(PokemonResponse.class)));

		return builder -> builder.withCacheConfiguration(POKEMON_CACHE, pokemonCache);
	}

}
