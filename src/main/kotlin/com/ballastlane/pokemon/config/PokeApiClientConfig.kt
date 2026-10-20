package com.ballastlane.pokemon.config

import java.net.http.HttpClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient

@Configuration(proxyBeanMethods = false)
class PokeApiClientConfig {

    @Bean
    fun pokeApiRestClient(builder: RestClient.Builder, properties: PokeApiProperties): RestClient {
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.connectTimeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(properties.readTimeout) }

        // Validated as required (@NotNull), so it is always set by the time this bean is created.
        val baseUrl = checkNotNull(properties.baseUrl) { "pokeapi.base-url is required" }
        return builder
            .baseUrl(baseUrl.toString())
            .requestFactory(requestFactory)
            .build()
    }
}
