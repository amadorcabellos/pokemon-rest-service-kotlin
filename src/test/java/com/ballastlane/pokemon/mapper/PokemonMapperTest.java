package com.ballastlane.pokemon.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.ballastlane.pokemon.client.dto.PokeApiPokemon;
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.AbilitySlot;
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.NamedResource;
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.Sprites;
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.StatSlot;
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.TypeSlot;
import com.ballastlane.pokemon.domain.Pokemon;
import com.ballastlane.pokemon.domain.PokemonAbility;
import com.ballastlane.pokemon.domain.PokemonStat;
import com.ballastlane.pokemon.web.dto.PokemonSummary;
import com.ballastlane.pokemon.web.dto.Skill;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PokemonMapperTest {

	private final PokemonMapper mapper = new PokemonMapper();

	@Test
	void toEntityOrdersTypesBySlot() {
		PokeApiPokemon source = new PokeApiPokemon(1, "bulbasaur", 7, 69, 64, new Sprites("sprite.png", null),
				List.of(new TypeSlot(2, new NamedResource("poison", null)),
						new TypeSlot(1, new NamedResource("grass", null))),
				List.of(), null, null);

		Pokemon entity = mapper.toEntity(source);

		assertThat(entity.getPokeApiId()).isEqualTo(1);
		assertThat(entity.getName()).isEqualTo("bulbasaur");
		assertThat(entity.getSpriteUrl()).isEqualTo("sprite.png");
		assertThat(entity.getTypes()).containsExactly("grass", "poison");
	}

	@Test
	void toEntityMapsStatsAbilitiesAndSpecies() {
		PokeApiPokemon source = new PokeApiPokemon(25, "pikachu", 4, 60, 112, null, List.of(),
				List.of(new AbilitySlot(3, true, new NamedResource("lightning-rod", null)),
						new AbilitySlot(1, false, new NamedResource("static", null))),
				List.of(new StatSlot(35, new NamedResource("hp", null)),
						new StatSlot(90, new NamedResource("speed", null))),
				new NamedResource("pikachu", "https://pokeapi.co/api/v2/pokemon-species/25/"));

		Pokemon entity = mapper.toEntity(source);

		assertThat(entity.getStats()).containsExactly(new PokemonStat("hp", 35), new PokemonStat("speed", 90));
		assertThat(entity.getAbilities()).containsExactly(new PokemonAbility("static", false),
				new PokemonAbility("lightning-rod", true));
		assertThat(entity.getSpeciesId()).isEqualTo(25);
		assertThat(entity.getSpeciesName()).isEqualTo("pikachu");
		assertThat(entity.getLastSyncedAt()).isNull();
	}

	@Test
	void toEntityHandlesMissingOptionalFields() {
		PokeApiPokemon source = new PokeApiPokemon(132, "ditto", 3, 40, null, null, null, null, null, null);

		Pokemon entity = mapper.toEntity(source);

		assertThat(entity.getSpriteUrl()).isNull();
		assertThat(entity.getBaseExperience()).isNull();
		assertThat(entity.getTypes()).isEmpty();
		assertThat(entity.getStats()).isEmpty();
		assertThat(entity.getAbilities()).isEmpty();
		assertThat(entity.getSpeciesId()).isNull();
	}

	@Test
	void toSummaryMapsCategoryMassAndSkills() {
		PokeApiPokemon source = new PokeApiPokemon(1, "bulbasaur", 7, 69, 64, new Sprites("sprite.png", null),
				List.of(new TypeSlot(2, new NamedResource("poison", null)),
						new TypeSlot(1, new NamedResource("grass", null))),
				List.of(new AbilitySlot(3, true, new NamedResource("chlorophyll", null)),
						new AbilitySlot(1, false, new NamedResource("overgrow", null))),
				null, null);

		PokemonSummary summary = mapper.toSummary(source);

		assertThat(summary).isEqualTo(new PokemonSummary(1, "bulbasaur", "sprite.png", List.of("grass", "poison"),
				new BigDecimal("6.9"), List.of(new Skill("overgrow", false), new Skill("chlorophyll", true))));
	}

	@Test
	void toSummaryConvertsHectogramsToKilograms() {
		assertThat(mapper.toSummary(new PokeApiPokemon(25, "pikachu", 4, 60, 112, null, null, null, null, null)).massKg())
				.isEqualByComparingTo("6.0");
		assertThat(mapper.toSummary(new PokeApiPokemon(143, "snorlax", 21, 4600, 189, null, null, null, null, null)).massKg())
				.isEqualByComparingTo("460.0");
	}

	@Test
	void toSummaryHandlesMissingSpriteTypesAndAbilities() {
		PokemonSummary summary = mapper.toSummary(new PokeApiPokemon(132, "ditto", 3, 40, null, null, null, null, null, null));

		assertThat(summary.spriteUrl()).isNull();
		assertThat(summary.category()).isEmpty();
		assertThat(summary.skills()).isEmpty();
	}

}
