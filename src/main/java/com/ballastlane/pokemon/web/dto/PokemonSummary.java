package com.ballastlane.pokemon.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Catalogue entry: the pokemon's sprite, category (types, in slot order), mass and skills (abilities).
 */
public record PokemonSummary(
		Integer id,
		String name,
		String spriteUrl,
		List<String> category,
		BigDecimal massKg,
		List<Skill> skills) {
}
