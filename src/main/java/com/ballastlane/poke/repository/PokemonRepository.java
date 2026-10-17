package com.ballastlane.poke.repository;

import com.ballastlane.poke.domain.Pokemon;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PokemonRepository extends JpaRepository<Pokemon, Long> {

	Optional<Pokemon> findByNameIgnoreCase(String name);

	Optional<Pokemon> findByPokeApiId(Integer pokeApiId);

}
