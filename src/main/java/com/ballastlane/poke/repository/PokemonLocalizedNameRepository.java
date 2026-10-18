package com.ballastlane.poke.repository;

import com.ballastlane.poke.domain.PokemonLocalizedName;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PokemonLocalizedNameRepository extends JpaRepository<PokemonLocalizedName, Long> {

	List<PokemonLocalizedName> findByPokemonId(Long pokemonId);

}
