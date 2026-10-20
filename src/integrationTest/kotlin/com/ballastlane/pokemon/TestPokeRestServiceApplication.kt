package com.ballastlane.pokemon

import com.ballastlane.pokemon.support.ContainersConfiguration
import org.springframework.boot.SpringApplication
import com.ballastlane.pokemon.main as applicationMain

/**
 * Runs the application locally against throwaway PostgreSQL and Redis containers.
 * Start it with `./gradlew bootTestRun` or from your IDE.
 */
object TestPokeRestServiceApplication {

    @JvmStatic
    fun main(args: Array<String>) {
        SpringApplication.from(::applicationMain).with(ContainersConfiguration::class.java).run(*args)
    }
}
