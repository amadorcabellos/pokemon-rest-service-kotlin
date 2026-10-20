package com.ballastlane.pokemon.web.dto

import java.math.BigDecimal

/**
 * Catalogue entry: the pokemon's sprite, category (types, in slot order), mass and skills (abilities).
 */
data class PokemonSummary(
    val id: Int,
    val name: String?,
    val spriteUrl: String?,
    val category: List<String?>,
    val massKg: BigDecimal,
    val skills: List<Skill>,
)
