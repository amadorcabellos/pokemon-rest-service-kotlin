package com.ballastlane.poke.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.ballastlane.poke.client.dto.PokeApiPokemon;
import com.ballastlane.poke.client.dto.PokeApiPokemon.NamedResource;
import com.ballastlane.poke.client.dto.PokeApiPokemon.Sprites;
import com.ballastlane.poke.client.dto.PokeApiPokemon.TypeSlot;
import com.ballastlane.poke.domain.Pokemon;
import com.ballastlane.poke.web.dto.PokemonResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class PokemonMapperTest {

	private final PokemonMapper mapper = new PokemonMapper();

	@Test
	void toEntityOrdersTypesBySlot() {
		PokeApiPokemon source = new PokeApiPokemon(1, "bulbasaur", 7, 69, 64, new Sprites("sprite.png"),
				List.of(new TypeSlot(2, new NamedResource("poison", null)),
						new TypeSlot(1, new NamedResource("grass", null))));

		Pokemon entity = mapper.toEntity(source);

		assertThat(entity.getPokeApiId()).isEqualTo(1);
		assertThat(entity.getName()).isEqualTo("bulbasaur");
		assertThat(entity.getSpriteUrl()).isEqualTo("sprite.png");
		assertThat(entity.getTypes()).containsExactly("grass", "poison");
	}

	@Test
	void toEntityHandlesMissingOptionalFields() {
		PokeApiPokemon source = new PokeApiPokemon(132, "ditto", 3, 40, null, null, null);

		Pokemon entity = mapper.toEntity(source);

		assertThat(entity.getSpriteUrl()).isNull();
		assertThat(entity.getBaseExperience()).isNull();
		assertThat(entity.getTypes()).isEmpty();
	}

	@Test
	void toResponseUsesPokeApiId() {
		Pokemon entity = new Pokemon(25, "pikachu", 4, 60, 112, "sprite.png", List.of("electric"));

		PokemonResponse response = mapper.toResponse(entity);

		assertThat(response).isEqualTo(new PokemonResponse(25, "pikachu", 4, 60, 112, "sprite.png",
				List.of("electric")));
	}

}
