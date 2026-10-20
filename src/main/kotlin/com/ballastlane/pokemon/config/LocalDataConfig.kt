package com.ballastlane.pokemon.config

import com.ballastlane.pokemon.filter.LocalDataRequestSizeFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class LocalDataConfig {

    @Bean
    fun localDataRequestSizeFilter(properties: LocalDataProperties): LocalDataRequestSizeFilter =
        LocalDataRequestSizeFilter(properties.maxRequestSize)
}
