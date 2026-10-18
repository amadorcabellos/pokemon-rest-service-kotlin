package com.ballastlane.pokemon.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PokemonTest {

	@Test
	void copyUpstreamFromReplacesUpstreamFieldsOnly() {
		Pokemon stored = new Pokemon(25, "pikachu-old", 4, 60, 112, "old.png", List.of("electric"),
				List.of(new PokemonStat("hp", 30)), List.of(new PokemonAbility("static", false)), null, null);
		stored.onCreate();
		Instant createdAt = stored.getCreatedAt();
		Pokemon upstream = new Pokemon(999, "pikachu", 5, 61, 113, "new.png", List.of("electric", "fairy"),
				List.of(new PokemonStat("hp", 35), new PokemonStat("speed", 90)),
				List.of(new PokemonAbility("lightning-rod", true)), 25, "pikachu");

		stored.copyUpstreamFrom(upstream);

		assertThat(stored.getPokeApiId()).isEqualTo(25);
		assertThat(stored.getCreatedAt()).isEqualTo(createdAt);
		assertThat(stored.getName()).isEqualTo("pikachu");
		assertThat(stored.getHeight()).isEqualTo(5);
		assertThat(stored.getWeight()).isEqualTo(61);
		assertThat(stored.getBaseExperience()).isEqualTo(113);
		assertThat(stored.getSpriteUrl()).isEqualTo("new.png");
		assertThat(stored.getTypes()).containsExactly("electric", "fairy");
		assertThat(stored.getStats()).containsExactly(new PokemonStat("hp", 35), new PokemonStat("speed", 90));
		assertThat(stored.getAbilities()).containsExactly(new PokemonAbility("lightning-rod", true));
		assertThat(stored.getSpeciesId()).isEqualTo(25);
		assertThat(stored.getSpeciesName()).isEqualTo("pikachu");
	}

	@Test
	void tagsAreStoredInLowercase() {
		assertThat(new PokemonTag(null, "  Starter-Kanto ").getTag()).isEqualTo("starter-kanto");
	}

}
