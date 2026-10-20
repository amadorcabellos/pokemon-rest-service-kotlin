package com.ballastlane.pokemon.web.dto

/**
 * One page of the pokemon catalogue. A concrete (non-generic) type so it can be cached as JSON in Redis.
 */
data class PokemonSummaryPage(
    val content: List<PokemonSummary>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)
