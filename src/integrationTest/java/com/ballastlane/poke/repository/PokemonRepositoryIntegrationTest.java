package com.ballastlane.poke.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ballastlane.poke.domain.Pokemon;
import com.ballastlane.poke.support.ContainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ContainersConfiguration.class)
class PokemonRepositoryIntegrationTest {

	@Autowired
	private PokemonRepository repository;

	@Test
	void savesAndFindsByNameIgnoringCase() {
		repository.saveAndFlush(new Pokemon(4, "charmander", 6, 85, 62, "sprite.png", List.of("fire")));

		assertThat(repository.findByNameIgnoreCase("CharMander"))
				.hasValueSatisfying(pokemon -> {
					assertThat(pokemon.getId()).isNotNull();
					assertThat(pokemon.getTypes()).containsExactly("fire");
					assertThat(pokemon.getCreatedAt()).isNotNull();
				});
	}

	@Test
	void findsByPokeApiIdAndKeepsTypeOrder() {
		repository.saveAndFlush(new Pokemon(6, "charizard", 17, 905, 267, null, List.of("fire", "flying")));

		assertThat(repository.findByPokeApiId(6))
				.hasValueSatisfying(pokemon -> assertThat(pokemon.getTypes()).containsExactly("fire", "flying"));
	}

	@Test
	void enforcesUniqueName() {
		repository.saveAndFlush(new Pokemon(7, "squirtle", 5, 90, 63, null, List.of("water")));

		assertThatThrownBy(() -> repository.saveAndFlush(
				new Pokemon(9999, "squirtle", 5, 90, 63, null, List.of("water"))))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

}
