package com.ballastlane.pokemon.repository;

import com.ballastlane.pokemon.domain.PokemonGeoMetadata;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PokemonGeoMetadataRepository extends JpaRepository<PokemonGeoMetadata, Long> {

	List<PokemonGeoMetadata> findByPokemonId(Long pokemonId);

}
