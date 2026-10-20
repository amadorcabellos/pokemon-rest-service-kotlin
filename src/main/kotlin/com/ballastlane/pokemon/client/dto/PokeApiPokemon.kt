package com.ballastlane.pokemon.client.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Subset of the PokeAPI `/pokemon/{idOrName}` payload that this service uses.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PokeApiPokemon(
    val id: Int = 0,
    val name: String? = null,
    val height: Int = 0,
    val weight: Int = 0,
    @JsonProperty("base_experience") val baseExperience: Int? = null,
    val sprites: Sprites? = null,
    val types: List<TypeSlot>? = null,
    val abilities: List<AbilitySlot>? = null,
    val stats: List<StatSlot>? = null,
    val species: NamedResource? = null,
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Sprites(
        @JsonProperty("front_default") val frontDefault: String? = null,
        val other: OtherSprites? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class OtherSprites(@JsonProperty("official-artwork") val officialArtwork: Artwork? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Artwork(@JsonProperty("front_default") val frontDefault: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StatSlot(@JsonProperty("base_stat") val baseStat: Int = 0, val stat: NamedResource? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TypeSlot(val slot: Int = 0, val type: NamedResource? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AbilitySlot(
        val slot: Int = 0,
        @JsonProperty("is_hidden") val hidden: Boolean = false,
        val ability: NamedResource? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class NamedResource(val name: String? = null, val url: String? = null)
}
