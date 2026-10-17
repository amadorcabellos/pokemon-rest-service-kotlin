package com.ballastlane.poke.mapper;

import com.ballastlane.poke.client.PokeApiUrls;
import com.ballastlane.poke.client.dto.PokeApiEvolutionChain;
import com.ballastlane.poke.client.dto.PokeApiEvolutionChain.ChainLink;
import com.ballastlane.poke.client.dto.PokeApiEvolutionChain.EvolutionDetail;
import com.ballastlane.poke.client.dto.PokeApiPokemon;
import com.ballastlane.poke.client.dto.PokeApiSpecies;
import com.ballastlane.poke.web.dto.EvolutionStage;
import com.ballastlane.poke.web.dto.PokemonDetails;
import com.ballastlane.poke.web.dto.Stat;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Builds the detailed pokemon view from the PokeAPI pokemon, species and evolution chain resources.
 */
@Component
public class PokemonDetailsMapper {

	private static final String ENGLISH = "en";

	/**
	 * Assembles the view; the evolution tree is marked with the requested pokemon's species as current.
	 */
	public PokemonDetails toDetails(PokeApiPokemon pokemon, PokeApiSpecies species, EvolutionStage evolution) {
		List<Stat> stats = Optional.ofNullable(pokemon.stats()).orElse(List.of()).stream()
				.map(slot -> new Stat(slot.stat().name(), slot.baseStat()))
				.toList();
		int totalStats = stats.stream().mapToInt(Stat::value).sum();

		return new PokemonDetails(pokemon.id(), pokemon.name(), imageUrl(pokemon), stats, totalStats,
				description(species), evolution.markCurrent(species.id()));
	}

	/**
	 * Converts a PokeAPI evolution chain into a tree of stages, none marked as current.
	 */
	public EvolutionStage toEvolutionStage(PokeApiEvolutionChain chain) {
		return toStage(chain.chain());
	}

	/**
	 * Fallback tree for a species without an evolution chain.
	 */
	public EvolutionStage singleStage(PokeApiSpecies species) {
		return new EvolutionStage(species.id(), species.name(), null, false, List.of());
	}

	/**
	 * Official artwork when available, otherwise the default front sprite.
	 */
	static String imageUrl(PokeApiPokemon pokemon) {
		PokeApiPokemon.Sprites sprites = pokemon.sprites();
		if (sprites == null) {
			return null;
		}
		String artwork = sprites.other() != null && sprites.other().officialArtwork() != null
				? sprites.other().officialArtwork().frontDefault()
				: null;
		return artwork != null ? artwork : sprites.frontDefault();
	}

	/**
	 * English flavor text from the most recent game version (highest version id), cleaned into a single line.
	 */
	static String description(PokeApiSpecies species) {
		return Optional.ofNullable(species.flavorTextEntries()).orElse(List.of()).stream()
				.filter(entry -> entry.language() != null && ENGLISH.equals(entry.language().name()))
				.filter(entry -> entry.flavorText() != null && entry.version() != null)
				.max(Comparator.comparingInt(entry -> PokeApiUrls.idFrom(entry.version().url())))
				.map(entry -> clean(entry.flavorText()))
				.orElse(null);
	}

	static String clean(String flavorText) {
		return flavorText
				.replace("­\n", "")
				.replace("­", "")
				.replaceAll("[\\f\\r\\n]", " ")
				.replaceAll("\\s+", " ")
				.trim();
	}

	/**
	 * Short summary of how a stage evolves (e.g. "level 16", "use thunder-stone"); distinct conditions from
	 * different games are joined with " or ". Returns {@code null} when there are no conditions (base stage).
	 */
	static String summarize(List<EvolutionDetail> details) {
		String summary = Optional.ofNullable(details).orElse(List.of()).stream()
				.map(PokemonDetailsMapper::summarize)
				.filter(Objects::nonNull)
				.distinct()
				.collect(Collectors.joining(" or "));
		return summary.isEmpty() ? null : summary;
	}

	private static String summarize(EvolutionDetail detail) {
		String trigger = detail.trigger() != null ? detail.trigger().name() : null;
		if (trigger == null) {
			return null;
		}
		return switch (trigger) {
			case "level-up" -> {
				if (detail.minLevel() != null) {
					yield "level " + detail.minLevel();
				}
				yield (detail.minHappiness() != null ? "friendship" : "level up") + timeOfDay(detail);
			}
			case "use-item" -> detail.item() != null ? "use " + detail.item().name() : "use item";
			case "trade" -> detail.heldItem() != null ? "trade holding " + detail.heldItem().name() : "trade";
			default -> trigger;
		};
	}

	private static String timeOfDay(EvolutionDetail detail) {
		String time = detail.timeOfDay();
		return time != null && !time.isBlank() ? " (" + time + ")" : "";
	}

	private static EvolutionStage toStage(ChainLink link) {
		List<EvolutionStage> next = Optional.ofNullable(link.evolvesTo()).orElse(List.of()).stream()
				.map(PokemonDetailsMapper::toStage)
				.toList();
		return new EvolutionStage(PokeApiUrls.idFrom(link.species().url()), link.species().name(),
				summarize(link.evolutionDetails()), false, next);
	}

}
