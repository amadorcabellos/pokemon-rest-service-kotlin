package com.ballastlane.pokemon.repository;

import com.ballastlane.pokemon.domain.Pokemon;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PokemonRepository extends JpaRepository<Pokemon, Long> {

	Optional<Pokemon> findByNameIgnoreCase(String name);

	Optional<Pokemon> findByPokeApiId(Integer pokeApiId);

	@Query("""
			select new com.ballastlane.pokemon.repository.LocalDataHeader(p.id, p.localDataVersion, p.localDataModifiedAt)
			from Pokemon p where p.pokeApiId = :pokeApiId""")
	Optional<LocalDataHeader> findLocalDataHeaderByPokeApiId(Integer pokeApiId);

	@Query("""
			select new com.ballastlane.pokemon.repository.LocalDataHeader(p.id, p.localDataVersion, p.localDataModifiedAt)
			from Pokemon p where lower(p.name) = lower(:name)""")
	Optional<LocalDataHeader> findLocalDataHeaderByName(String name);

	@Query("""
			select new com.ballastlane.pokemon.repository.LocalDataHeader(p.id, p.localDataVersion, p.localDataModifiedAt)
			from Pokemon p where p.id = :id""")
	Optional<LocalDataHeader> findLocalDataHeaderById(Long id);

	/**
	 * Locks the pokemon row until the end of the transaction, so updates of one pokemon's proprietary data run one
	 * after the other. {@code NO KEY UPDATE} still lets other transactions insert rows that reference it.
	 *
	 * @return the current version of its proprietary data
	 */
	@Query(value = "SELECT local_data_version FROM pokemon WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
	long lockLocalDataVersion(Long id);

	/**
	 * The only writer of the version columns, which are read only on {@link Pokemon}. Leaves {@code updated_at}
	 * alone: that one tracks upstream changes.
	 */
	@Modifying(flushAutomatically = true)
	@Query(value = """
			UPDATE pokemon SET local_data_version = local_data_version + 1, local_data_modified_at = :modifiedAt
			WHERE id = :id""", nativeQuery = true)
	int bumpLocalDataVersion(Long id, Instant modifiedAt);

}
