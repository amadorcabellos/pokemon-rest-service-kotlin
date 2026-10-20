package com.ballastlane.pokemon

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class PokeRestServiceApplication

fun main(args: Array<String>) {
    runApplication<PokeRestServiceApplication>(*args)
}
