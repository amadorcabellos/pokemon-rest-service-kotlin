package com.ballastlane.poke.repository;

import com.ballastlane.poke.domain.PokemonGeoMetadata;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PokemonGeoMetadataRepository extends JpaRepository<PokemonGeoMetadata, Long> {

	List<PokemonGeoMetadata> findByPokemonId(Long pokemonId);

}
