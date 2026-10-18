package com.ballastlane.pokemon.repository;

import com.ballastlane.pokemon.domain.PokemonTag;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PokemonTagRepository extends JpaRepository<PokemonTag, Long> {

	List<PokemonTag> findByPokemonId(Long pokemonId);

}
