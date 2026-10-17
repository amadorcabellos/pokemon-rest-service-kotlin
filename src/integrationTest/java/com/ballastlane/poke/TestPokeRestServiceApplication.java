package com.ballastlane.poke;

import com.ballastlane.poke.support.ContainersConfiguration;
import org.springframework.boot.SpringApplication;

/**
 * Runs the application locally against throwaway PostgreSQL and Redis containers.
 * Start it with {@code ./gradlew bootTestRun} or from your IDE.
 */
public class TestPokeRestServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(PokeRestServiceApplication::main).with(ContainersConfiguration.class).run(args);
	}

}
