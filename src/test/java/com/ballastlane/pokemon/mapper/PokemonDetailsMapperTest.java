package com.ballastlane.pokemon.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.ballastlane.pokemon.client.dto.PokeApiEvolutionChain;
import com.ballastlane.pokemon.client.dto.PokeApiEvolutionChain.ChainLink;
import com.ballastlane.pokemon.client.dto.PokeApiEvolutionChain.EvolutionDetail;
import com.ballastlane.pokemon.client.dto.PokeApiPokemon;
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.Artwork;
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.NamedResource;
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.OtherSprites;
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.Sprites;
import com.ballastlane.pokemon.client.dto.PokeApiPokemon.StatSlot;
import com.ballastlane.pokemon.client.dto.PokeApiSpecies;
import com.ballastlane.pokemon.client.dto.PokeApiSpecies.FlavorText;
import com.ballastlane.pokemon.web.dto.EvolutionStage;
import com.ballastlane.pokemon.web.dto.PokemonDetails;
import com.ballastlane.pokemon.web.dto.Stat;
import java.util.List;
import org.junit.jupiter.api.Test;

class PokemonDetailsMapperTest {

	private static final String API = "https://pokeapi.co/api/v2";

	private final PokemonDetailsMapper mapper = new PokemonDetailsMapper();

	@Test
	void toDetailsMapsStatsTotalImageDescriptionAndMarksCurrentStage() {
		PokeApiPokemon pikachu = pokemon(new Sprites("sprite.png", new OtherSprites(new Artwork("artwork.png"))),
				List.of(stat("hp", 35), stat("attack", 55), stat("defense", 40), stat("special-attack", 50),
						stat("special-defense", 50), stat("speed", 90)));
		PokeApiSpecies species = species(25, "pikachu", List.of(flavor("Pika pika.", "en", 1)));
		EvolutionStage chain = new EvolutionStage(172, "pichu", null, false,
				List.of(new EvolutionStage(25, "pikachu", "friendship", false, List.of())));

		PokemonDetails details = mapper.toDetails(pikachu, species, chain);

		assertThat(details.imageUrl()).isEqualTo("artwork.png");
		assertThat(details.stats()).extracting(Stat::name)
				.containsExactly("hp", "attack", "defense", "special-attack", "special-defense", "speed");
		assertThat(details.totalStats()).isEqualTo(320);
		assertThat(details.description()).isEqualTo("Pika pika.");
		assertThat(details.evolution().current()).isFalse();
		assertThat(details.evolution().evolvesTo().get(0).current()).isTrue();
		// Local data is never part of the cached PokeAPI view.
		assertThat(details.localData()).isNull();
	}

	@Test
	void imageFallsBackToSpriteThenNull() {
		assertThat(PokemonDetailsMapper.imageUrl(pokemon(new Sprites("sprite.png", new OtherSprites(null)), List.of())))
				.isEqualTo("sprite.png");
		assertThat(PokemonDetailsMapper.imageUrl(pokemon(new Sprites("sprite.png", null), List.of())))
				.isEqualTo("sprite.png");
		assertThat(PokemonDetailsMapper.imageUrl(pokemon(null, List.of()))).isNull();
	}

	@Test
	void descriptionUsesMostRecentEnglishVersionAndIsCleaned() {
		PokeApiSpecies species = species(25, "pikachu", List.of(
				flavor("Old\ntext.", "en", 1),
				flavor("ピカチュウ", "ja", 40),
				flavor("It can gen­\nerate\fpower-\nful  electricity.\n", "en", 34)));

		assertThat(PokemonDetailsMapper.description(species)).isEqualTo("It can generate power- ful electricity.");
	}

	@Test
	void descriptionIsNullWithoutEnglishText() {
		assertThat(PokemonDetailsMapper.description(species(1, "x", List.of(flavor("Texte", "fr", 2))))).isNull();
		assertThat(PokemonDetailsMapper.description(species(1, "x", null))).isNull();
	}

	@Test
	void summarizesEvolutionConditions() {
		assertThat(PokemonDetailsMapper.summarize(List.of(detail("level-up", 16, null, null, null, "")))).isEqualTo("level 16");
		assertThat(PokemonDetailsMapper.summarize(List.of(detail("use-item", null, "thunder-stone", null, null, ""))))
				.isEqualTo("use thunder-stone");
		assertThat(PokemonDetailsMapper.summarize(List.of(detail("trade", null, null, "metal-coat", null, ""))))
				.isEqualTo("trade holding metal-coat");
		assertThat(PokemonDetailsMapper.summarize(List.of(detail("trade", null, null, null, null, "")))).isEqualTo("trade");
		assertThat(PokemonDetailsMapper.summarize(List.of(detail("level-up", null, null, null, 160, "day"))))
				.isEqualTo("friendship (day)");
		assertThat(PokemonDetailsMapper.summarize(List.of(detail("spin", null, null, null, null, "")))).isEqualTo("spin");
		assertThat(PokemonDetailsMapper.summarize(List.of())).isNull();
	}

	@Test
	void joinsDistinctConditionsFromDifferentGames() {
		assertThat(PokemonDetailsMapper.summarize(List.of(
				detail("level-up", null, null, null, 220, ""),
				detail("level-up", null, null, null, 160, ""),
				detail("use-item", null, "ice-stone", null, null, ""))))
				.isEqualTo("friendship or use ice-stone");
	}

	@Test
	void mapsLinearChain() {
		EvolutionStage root = mapper.toEvolutionStage(new PokeApiEvolutionChain(10,
				link(172, "pichu", List.of(), List.of(
						link(25, "pikachu", List.of(detail("level-up", null, null, null, 220, "")), List.of(
								link(26, "raichu", List.of(detail("use-item", null, "thunder-stone", null, null, "")),
										List.of())))))));

		assertThat(root.id()).isEqualTo(172);
		assertThat(root.evolvesVia()).isNull();
		EvolutionStage pikachu = root.evolvesTo().get(0);
		assertThat(pikachu.evolvesVia()).isEqualTo("friendship");
		assertThat(pikachu.evolvesTo().get(0).evolvesVia()).isEqualTo("use thunder-stone");
		assertThat(pikachu.evolvesTo().get(0).evolvesTo()).isEmpty();
	}

	@Test
	void mapsBranchingChainInOrder() {
		EvolutionStage eevee = mapper.toEvolutionStage(new PokeApiEvolutionChain(67, link(133, "eevee", List.of(), List.of(
				link(134, "vaporeon", List.of(detail("use-item", null, "water-stone", null, null, "")), List.of()),
				link(135, "jolteon", List.of(detail("use-item", null, "thunder-stone", null, null, "")), List.of()),
				link(196, "espeon", List.of(detail("level-up", null, null, null, 160, "day")), List.of())))));

		assertThat(eevee.evolvesTo()).extracting(EvolutionStage::name).containsExactly("vaporeon", "jolteon", "espeon");
		assertThat(eevee.markCurrent(135).evolvesTo()).extracting(EvolutionStage::current)
				.containsExactly(false, true, false);
	}

	@Test
	void mapsSingleStageChain() {
		EvolutionStage tauros = mapper.toEvolutionStage(new PokeApiEvolutionChain(53,
				link(128, "tauros", List.of(), List.of())));

		assertThat(tauros.evolvesTo()).isEmpty();
		assertThat(tauros.markCurrent(128).current()).isTrue();
	}

	private static PokeApiPokemon pokemon(Sprites sprites, List<StatSlot> stats) {
		return new PokeApiPokemon(25, "pikachu", 4, 60, 112, sprites, List.of(), List.of(), stats,
				new NamedResource("pikachu", API + "/pokemon-species/25/"));
	}

	private static StatSlot stat(String name, int value) {
		return new StatSlot(value, new NamedResource(name, null));
	}

	private static PokeApiSpecies species(int id, String name, List<FlavorText> texts) {
		return new PokeApiSpecies(id, name, texts, null);
	}

	private static FlavorText flavor(String text, String language, int versionId) {
		return new FlavorText(text, new NamedResource(language, null),
				new NamedResource("v" + versionId, API + "/version/" + versionId + "/"));
	}

	private static EvolutionDetail detail(String trigger, Integer minLevel, String item, String heldItem,
			Integer minHappiness, String timeOfDay) {
		return new EvolutionDetail(new NamedResource(trigger, null), minLevel,
				item != null ? new NamedResource(item, null) : null,
				heldItem != null ? new NamedResource(heldItem, null) : null, minHappiness, timeOfDay);
	}

	private static ChainLink link(int id, String name, List<EvolutionDetail> details, List<ChainLink> next) {
		return new ChainLink(new NamedResource(name, API + "/pokemon-species/" + id + "/"), details, next);
	}

}
