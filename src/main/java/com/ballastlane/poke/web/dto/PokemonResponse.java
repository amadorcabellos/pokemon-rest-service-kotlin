package com.ballastlane.poke.web.dto;

import java.util.List;

public record PokemonResponse(
		Integer id,
		String name,
		Integer height,
		Integer weight,
		Integer baseExperience,
		String spriteUrl,
		List<String> types) {
}
