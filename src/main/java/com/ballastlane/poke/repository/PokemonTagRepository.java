package com.ballastlane.poke.repository;

import com.ballastlane.poke.domain.PokemonTag;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PokemonTagRepository extends JpaRepository<PokemonTag, Long> {

	List<PokemonTag> findByPokemonId(Long pokemonId);

}
