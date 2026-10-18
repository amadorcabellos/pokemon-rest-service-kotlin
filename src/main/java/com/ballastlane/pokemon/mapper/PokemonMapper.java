package com.ballastlane.pokemon.mapper;

import com.ballastlane.pokemon.client.PokeApiUrls;
import com.ballastlane.pokemon.client.dto.PokeApiPokemon;
import com.ballastlane.pokemon.domain.Pokemon;
import com.ballastlane.pokemon.domain.PokemonAbility;
import com.ballastlane.pokemon.domain.PokemonStat;
import com.ballastlane.pokemon.web.dto.PokemonSummary;
import com.ballastlane.pokemon.web.dto.Skill;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class PokemonMapper {

	/**
	 * Builds the upstream data of a pokemon: stats in PokeAPI order, abilities in slot order and the linked species.
	 */
	public Pokemon toEntity(PokeApiPokemon source) {
		List<PokemonStat> stats = Optional.ofNullable(source.stats()).orElse(List.of()).stream()
				.map(slot -> new PokemonStat(slot.stat().name(), slot.baseStat()))
				.toList();
		List<PokemonAbility> abilities = abilitySlots(source)
				.map(slot -> new PokemonAbility(slot.ability().name(), slot.hidden()))
				.toList();
		PokeApiPokemon.NamedResource species = source.species();
		Integer speciesId = species != null && species.url() != null ? PokeApiUrls.idFrom(species.url()) : null;
		String speciesName = species != null ? species.name() : null;

		return new Pokemon(source.id(), source.name(), source.height(), source.weight(), source.baseExperience(),
				spriteUrl(source), typeNames(source), stats, abilities, speciesId, speciesName);
	}

	/**
	 * Builds a catalogue entry: category is the pokemon's types and mass is converted from hectograms to kilograms.
	 */
	public PokemonSummary toSummary(PokeApiPokemon source) {
		List<Skill> skills = abilitySlots(source)
				.map(slot -> new Skill(slot.ability().name(), slot.hidden()))
				.toList();
		BigDecimal massKg = BigDecimal.valueOf(source.weight()).movePointLeft(1);

		return new PokemonSummary(source.id(), source.name(), spriteUrl(source), typeNames(source), massKg, skills);
	}

	private static List<String> typeNames(PokeApiPokemon source) {
		return Optional.ofNullable(source.types()).orElse(List.of()).stream()
				.sorted(Comparator.comparingInt(PokeApiPokemon.TypeSlot::slot))
				.map(slot -> slot.type().name())
				.toList();
	}

	private static Stream<PokeApiPokemon.AbilitySlot> abilitySlots(PokeApiPokemon source) {
		return Optional.ofNullable(source.abilities()).orElse(List.of()).stream()
				.sorted(Comparator.comparingInt(PokeApiPokemon.AbilitySlot::slot));
	}

	private static String spriteUrl(PokeApiPokemon source) {
		return source.sprites() != null ? source.sprites().frontDefault() : null;
	}

}
