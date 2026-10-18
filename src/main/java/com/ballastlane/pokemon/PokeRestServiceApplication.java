package com.ballastlane.pokemon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PokeRestServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(PokeRestServiceApplication.class, args);
	}

}
