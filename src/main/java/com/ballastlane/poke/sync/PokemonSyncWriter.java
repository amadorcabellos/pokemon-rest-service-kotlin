package com.ballastlane.poke.sync;

import com.ballastlane.poke.client.dto.PokeApiPokemon;
import com.ballastlane.poke.domain.Pokemon;
import com.ballastlane.poke.mapper.PokemonMapper;
import com.ballastlane.poke.repository.PokemonRepository;
import com.ballastlane.poke.web.dto.SyncedPokemon;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes the upstream data of one pokemon into PostgreSQL, in its own transaction.
 * <p>
 * Pokemon are matched by PokeAPI id, so a renamed pokemon updates its existing row. Only upstream fields are
 * written; proprietary data is never touched.
 */
@Component
public class PokemonSyncWriter {

	private static final Logger log = LoggerFactory.getLogger(PokemonSyncWriter.class);

	private final PokemonRepository pokemonRepository;

	private final PokemonMapper pokemonMapper;

	private final TransactionTemplate transactionTemplate;

	public PokemonSyncWriter(PokemonRepository pokemonRepository, PokemonMapper pokemonMapper,
			TransactionTemplate transactionTemplate) {
		this.pokemonRepository = pokemonRepository;
		this.pokemonMapper = pokemonMapper;
		this.transactionTemplate = transactionTemplate;
	}

	public SyncedPokemon upsert(PokeApiPokemon source) {
		try {
			return transactionTemplate.execute(status -> upsertInTransaction(source));
		}
		catch (DataIntegrityViolationException ex) {
			// A concurrent lookup or sync inserted the same pokemon first; the retry takes the update path.
			log.debug("Pokemon {} was stored concurrently, retrying as an update", source.id());
			return transactionTemplate.execute(status -> upsertInTransaction(source));
		}
	}

	private SyncedPokemon upsertInTransaction(PokeApiPokemon source) {
		Pokemon upstream = pokemonMapper.toEntity(source);
		Optional<Pokemon> existing = pokemonRepository.findByPokeApiId(source.id());
		Pokemon pokemon = existing.orElse(upstream);
		existing.ifPresent(stored -> stored.copyUpstreamFrom(upstream));

		Instant now = Instant.now();
		pokemon.markSynced(now);
		// Flush now so constraint violations surface here, inside the retry above.
		pokemonRepository.saveAndFlush(pokemon);

		SyncOutcome outcome = existing.isPresent() ? SyncOutcome.UPDATED : SyncOutcome.CREATED;
		return new SyncedPokemon(pokemon.getPokeApiId(), pokemon.getName(), outcome, now);
	}

}
