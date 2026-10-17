package com.ballastlane.poke.mapper;

import com.ballastlane.poke.client.dto.PokeApiPokemon;
import com.ballastlane.poke.domain.Pokemon;
import com.ballastlane.poke.web.dto.PokemonResponse;
import com.ballastlane.poke.web.dto.PokemonSummary;
import com.ballastlane.poke.web.dto.Skill;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class PokemonMapper {

	public Pokemon toEntity(PokeApiPokemon source) {
		return new Pokemon(source.id(), source.name(), source.height(), source.weight(), source.baseExperience(),
				spriteUrl(source), typeNames(source));
	}

	/**
	 * Builds a catalogue entry: category is the pokemon's types and mass is converted from hectograms to kilograms.
	 */
	public PokemonSummary toSummary(PokeApiPokemon source) {
		List<Skill> skills = Optional.ofNullable(source.abilities()).orElse(List.of()).stream()
				.sorted(Comparator.comparingInt(PokeApiPokemon.AbilitySlot::slot))
				.map(slot -> new Skill(slot.ability().name(), slot.hidden()))
				.toList();
		BigDecimal massKg = BigDecimal.valueOf(source.weight()).movePointLeft(1);

		return new PokemonSummary(source.id(), source.name(), spriteUrl(source), typeNames(source), massKg, skills);
	}

	public PokemonResponse toResponse(Pokemon pokemon) {
		return new PokemonResponse(pokemon.getPokeApiId(), pokemon.getName(), pokemon.getHeight(),
				pokemon.getWeight(), pokemon.getBaseExperience(), pokemon.getSpriteUrl(), pokemon.getTypes());
	}

	private static List<String> typeNames(PokeApiPokemon source) {
		return Optional.ofNullable(source.types()).orElse(List.of()).stream()
				.sorted(Comparator.comparingInt(PokeApiPokemon.TypeSlot::slot))
				.map(slot -> slot.type().name())
				.toList();
	}

	private static String spriteUrl(PokeApiPokemon source) {
		return source.sprites() != null ? source.sprites().frontDefault() : null;
	}

}
