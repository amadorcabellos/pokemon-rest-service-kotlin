package com.ballastlane.pokemon.repository;

import com.ballastlane.pokemon.domain.PokemonLocalizedName;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PokemonLocalizedNameRepository extends JpaRepository<PokemonLocalizedName, Long> {

	List<PokemonLocalizedName> findByPokemonId(Long pokemonId);

}
