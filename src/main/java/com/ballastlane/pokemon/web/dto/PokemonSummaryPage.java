package com.ballastlane.pokemon.web.dto;

import java.util.List;

/**
 * One page of the pokemon catalogue. A concrete (non-generic) type so it can be cached as JSON in Redis.
 */
public record PokemonSummaryPage(
		List<PokemonSummary> content,
		int page,
		int size,
		long totalElements,
		int totalPages) {
}
