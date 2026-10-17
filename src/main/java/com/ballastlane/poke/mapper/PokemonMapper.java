package com.ballastlane.poke.mapper;

import com.ballastlane.poke.client.dto.PokeApiPokemon;
import com.ballastlane.poke.domain.Pokemon;
import com.ballastlane.poke.web.dto.PokemonResponse;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class PokemonMapper {

	public Pokemon toEntity(PokeApiPokemon source) {
		List<String> types = Optional.ofNullable(source.types()).orElse(List.of()).stream()
				.sorted(Comparator.comparingInt(PokeApiPokemon.TypeSlot::slot))
				.map(slot -> slot.type().name())
				.toList();
		String spriteUrl = source.sprites() != null ? source.sprites().frontDefault() : null;

		return new Pokemon(source.id(), source.name(), source.height(), source.weight(), source.baseExperience(),
				spriteUrl, types);
	}

	public PokemonResponse toResponse(Pokemon pokemon) {
		return new PokemonResponse(pokemon.getPokeApiId(), pokemon.getName(), pokemon.getHeight(),
				pokemon.getWeight(), pokemon.getBaseExperience(), pokemon.getSpriteUrl(), pokemon.getTypes());
	}

}
