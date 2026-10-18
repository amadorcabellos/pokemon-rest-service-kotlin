package com.ballastlane.poke.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ballastlane.poke.domain.GeoKind;
import com.ballastlane.poke.domain.Pokemon;
import com.ballastlane.poke.domain.PokemonAbility;
import com.ballastlane.poke.domain.PokemonGeoMetadata;
import com.ballastlane.poke.domain.PokemonLocalizedName;
import com.ballastlane.poke.domain.PokemonStat;
import com.ballastlane.poke.domain.PokemonTag;
import com.ballastlane.poke.domain.SyncRun;
import com.ballastlane.poke.domain.SyncTrigger;
import com.ballastlane.poke.support.ContainersConfiguration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ContainersConfiguration.class)
class PokemonRepositoryIntegrationTest {

	@Autowired
	private PokemonRepository repository;

	@Autowired
	private PokemonTagRepository tagRepository;

	@Autowired
	private PokemonLocalizedNameRepository localizedNameRepository;

	@Autowired
	private PokemonGeoMetadataRepository geoMetadataRepository;

	@Autowired
	private SyncRunRepository syncRunRepository;

	@Autowired
	private TestEntityManager entityManager;

	@Test
	void rowsWrittenBeforeTheSyncMigrationStillLoad() {
		// Only the V1 columns, as the lookup stored them before V2.
		entityManager.getEntityManager().createNativeQuery("""
				INSERT INTO pokemon (poke_api_id, name, height, weight, created_at, updated_at)
				VALUES (150, 'mewtwo', 20, 1220, now(), now())""").executeUpdate();

		assertThat(repository.findByPokeApiId(150)).hasValueSatisfying(pokemon -> {
			assertThat(pokemon.getStats()).isEmpty();
			assertThat(pokemon.getAbilities()).isEmpty();
			assertThat(pokemon.getSpeciesId()).isNull();
			assertThat(pokemon.getLastSyncedAt()).isNull();
		});
	}

	@Test
	void keepsStatAndAbilityOrder() {
		repository.saveAndFlush(new Pokemon(25, "pikachu", 4, 60, 112, null, List.of("electric"),
				List.of(new PokemonStat("hp", 35), new PokemonStat("attack", 55), new PokemonStat("speed", 90)),
				List.of(new PokemonAbility("static", false), new PokemonAbility("lightning-rod", true)), 25,
				"pikachu"));
		entityManager.clear();

		assertThat(repository.findByPokeApiId(25)).hasValueSatisfying(pokemon -> {
			assertThat(pokemon.getStats()).extracting(PokemonStat::name).containsExactly("hp", "attack", "speed");
			assertThat(pokemon.getAbilities()).containsExactly(new PokemonAbility("static", false),
					new PokemonAbility("lightning-rod", true));
		});
	}

	@Test
	void storesProprietaryDataWithUniqueLowercaseTags() {
		Pokemon pikachu = repository.saveAndFlush(new Pokemon(25, "pikachu", 4, 60, 112, null, List.of()));
		geoMetadataRepository.saveAndFlush(new PokemonGeoMetadata(pikachu, GeoKind.HABITAT, "forest", null));
		tagRepository.saveAndFlush(new PokemonTag(pikachu, "Mascot"));

		assertThat(geoMetadataRepository.findByPokemonId(pikachu.getId())).extracting(PokemonGeoMetadata::getKind)
				.containsExactly(GeoKind.HABITAT);
		assertThat(tagRepository.findByPokemonId(pikachu.getId())).extracting(PokemonTag::getTag)
				.containsExactly("mascot");
		assertThatThrownBy(() -> tagRepository.saveAndFlush(new PokemonTag(pikachu, "MASCOT")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void allowsOneLocalizedNamePerLocale() {
		Pokemon pikachu = repository.saveAndFlush(new Pokemon(25, "pikachu", 4, 60, 112, null, List.of()));
		localizedNameRepository.saveAndFlush(new PokemonLocalizedName(pikachu, "ja-JP", "Pikachu"));

		assertThatThrownBy(() -> localizedNameRepository.saveAndFlush(
				new PokemonLocalizedName(pikachu, "ja-JP", "Pika")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void allowsOnlyOneRunningSyncRun() {
		syncRunRepository.saveAndFlush(new SyncRun(SyncTrigger.API, Instant.now()));

		assertThatThrownBy(() -> syncRunRepository.saveAndFlush(new SyncRun(SyncTrigger.SCHEDULED, Instant.now())))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void savesAndFindsByNameIgnoringCase() {
		repository.saveAndFlush(new Pokemon(4, "charmander", 6, 85, 62, "sprite.png", List.of("fire")));

		assertThat(repository.findByNameIgnoreCase("CharMander"))
				.hasValueSatisfying(pokemon -> {
					assertThat(pokemon.getId()).isNotNull();
					assertThat(pokemon.getTypes()).containsExactly("fire");
					assertThat(pokemon.getCreatedAt()).isNotNull();
				});
	}

	@Test
	void findsByPokeApiIdAndKeepsTypeOrder() {
		repository.saveAndFlush(new Pokemon(6, "charizard", 17, 905, 267, null, List.of("fire", "flying")));

		assertThat(repository.findByPokeApiId(6))
				.hasValueSatisfying(pokemon -> assertThat(pokemon.getTypes()).containsExactly("fire", "flying"));
	}

	@Test
	void enforcesUniqueName() {
		repository.saveAndFlush(new Pokemon(7, "squirtle", 5, 90, 63, null, List.of("water")));

		assertThatThrownBy(() -> repository.saveAndFlush(
				new Pokemon(9999, "squirtle", 5, 90, 63, null, List.of("water"))))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

}
