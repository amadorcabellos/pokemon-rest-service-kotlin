package com.ballastlane.pokemon.repository

import com.ballastlane.pokemon.domain.AppUser
import com.ballastlane.pokemon.domain.AuthSession
import com.ballastlane.pokemon.domain.GeoKind
import com.ballastlane.pokemon.domain.Pokemon
import com.ballastlane.pokemon.domain.PokemonAbility
import com.ballastlane.pokemon.domain.PokemonGeoMetadata
import com.ballastlane.pokemon.domain.PokemonLocalizedName
import com.ballastlane.pokemon.domain.PokemonStat
import com.ballastlane.pokemon.domain.PokemonTag
import com.ballastlane.pokemon.domain.RefreshToken
import com.ballastlane.pokemon.domain.Role
import com.ballastlane.pokemon.domain.SyncRun
import com.ballastlane.pokemon.domain.SyncTrigger
import com.ballastlane.pokemon.support.ContainersConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.jvm.optionals.getOrNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ContainersConfiguration::class)
class PokemonRepositoryIntegrationTest @Autowired constructor(
    private val repository: PokemonRepository,
    private val tagRepository: PokemonTagRepository,
    private val localizedNameRepository: PokemonLocalizedNameRepository,
    private val geoMetadataRepository: PokemonGeoMetadataRepository,
    private val syncRunRepository: SyncRunRepository,
    private val userRepository: AppUserRepository,
    private val sessionRepository: AuthSessionRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val entityManager: TestEntityManager,
) {

    @BeforeEach
    fun startEmpty() {
        // API tests share this database and leave their rows behind; this transaction is rolled back afterwards.
        repository.deleteAll()
        repository.flush()
        entityManager.entityManager.createNativeQuery("DELETE FROM app_user").executeUpdate()
    }

    @Test
    fun findsPokeApiIdsByNameIgnoringCaseOrderedById() {
        repository.saveAndFlush(Pokemon(26, "raichu", 8, 300, 218, null, listOf()))
        repository.saveAndFlush(Pokemon(25, "pikachu", 4, 60, 112, null, listOf()))
        repository.saveAndFlush(Pokemon(1, "bulbasaur", 7, 69, 64, null, listOf()))
        repository.saveAndFlush(Pokemon(172, "pichu", 3, 20, 41, null, listOf()))

        val firstPage = repository.findPokeApiIdsByNameLike("%CHU%", PageRequest.of(0, 2))
        val secondPage = repository.findPokeApiIdsByNameLike("%CHU%", PageRequest.of(1, 2))

        firstPage.content.shouldContainExactly(25, 26)
        firstPage.totalElements shouldBe 3
        secondPage.content.shouldContainExactly(172)
    }

    @Test
    fun rowsWrittenBeforeTheSyncMigrationStillLoad() {
        // Only the V1 columns, as the lookup stored them before V2.
        entityManager.entityManager.createNativeQuery(
            """
            INSERT INTO pokemon (poke_api_id, name, height, weight, created_at, updated_at)
            VALUES (150, 'mewtwo', 20, 1220, now(), now())
            """.trimIndent(),
        ).executeUpdate()

        val pokemon = repository.findByPokeApiId(150).getOrNull().shouldNotBeNull()
        pokemon.stats.shouldBeEmpty()
        pokemon.abilities.shouldBeEmpty()
        pokemon.speciesId.shouldBeNull()
        pokemon.lastSyncedAt.shouldBeNull()
    }

    @Test
    fun keepsStatAndAbilityOrder() {
        repository.saveAndFlush(
            Pokemon(
                25, "pikachu", 4, 60, 112, null, listOf("electric"),
                listOf(PokemonStat("hp", 35), PokemonStat("attack", 55), PokemonStat("speed", 90)),
                listOf(PokemonAbility("static", false), PokemonAbility("lightning-rod", true)), 25, "pikachu",
            ),
        )
        entityManager.clear()

        val pokemon = repository.findByPokeApiId(25).getOrNull().shouldNotBeNull()
        pokemon.stats.map { it.name }.shouldContainExactly("hp", "attack", "speed")
        pokemon.abilities.shouldContainExactly(PokemonAbility("static", false), PokemonAbility("lightning-rod", true))
    }

    @Test
    fun storesProprietaryDataWithUniqueLowercaseTags() {
        val pikachu = repository.saveAndFlush(Pokemon(25, "pikachu", 4, 60, 112, null, listOf()))
        val pikachuId = checkNotNull(pikachu.id)
        geoMetadataRepository.saveAndFlush(PokemonGeoMetadata(pikachu, GeoKind.HABITAT, "forest", null))
        tagRepository.saveAndFlush(PokemonTag(pikachu, "Mascot"))

        geoMetadataRepository.findByPokemonId(pikachuId).map { it.kind }.shouldContainExactly(GeoKind.HABITAT)
        tagRepository.findByPokemonId(pikachuId).map { it.tag }.shouldContainExactly("mascot")
        shouldThrow<DataIntegrityViolationException> { tagRepository.saveAndFlush(PokemonTag(pikachu, "MASCOT")) }
    }

    @Test
    fun localDataVersionStartsAtZeroAndOnlyChangesThroughItsOwnUpdate() {
        val pikachu = repository.saveAndFlush(Pokemon(25, "pikachu", 4, 60, 112, null, listOf()))
        val pikachuId = checkNotNull(pikachu.id)
        val initial = repository.findLocalDataHeaderByPokeApiId(25).getOrNull().shouldNotBeNull()
        initial.version shouldBe 0
        initial.modifiedAt.shouldBeNull()

        val modifiedAt = Instant.parse("2026-10-18T10:00:00Z")
        repository.bumpLocalDataVersion(pikachuId, modifiedAt)
        // Saving the entity again (as the sync does) must not write the version back.
        pikachu.markSynced(Instant.now())
        repository.saveAndFlush(pikachu)
        entityManager.clear()

        val updated = repository.findLocalDataHeaderByName("PIKACHU").getOrNull().shouldNotBeNull()
        updated.version shouldBe 1
        updated.modifiedAt shouldBe modifiedAt
        repository.lockLocalDataVersion(pikachuId) shouldBe 1
    }

    @Test
    fun allowsRepeatedGeoEntriesButNotOverlongNotes() {
        val pikachu = repository.saveAndFlush(Pokemon(25, "pikachu", 4, 60, 112, null, listOf()))
        geoMetadataRepository.saveAndFlush(PokemonGeoMetadata(pikachu, GeoKind.REGION, "kanto", null))
        geoMetadataRepository.saveAndFlush(PokemonGeoMetadata(pikachu, GeoKind.REGION, "kanto", null))

        geoMetadataRepository.findByPokemonId(checkNotNull(pikachu.id)) shouldHaveSize 2
        shouldThrow<DataIntegrityViolationException> {
            geoMetadataRepository.saveAndFlush(PokemonGeoMetadata(pikachu, GeoKind.AREA, "route 1", "x".repeat(1001)))
        }
    }

    @Test
    fun storesUsersWithTheirRolesSessionsAndRefreshTokens() {
        val now = Instant.now()
        val ash = userRepository.saveAndFlush(AppUser("ash", "hash", setOf(Role.USER, Role.ADMIN)))
        val session = sessionRepository.saveAndFlush(AuthSession(ash, now, now.plusSeconds(3600)))
        refreshTokenRepository.saveAndFlush(RefreshToken("a".repeat(64), session, 0, now, now.plusSeconds(900)))
        entityManager.clear()

        val found = sessionRepository.findWithUserById(session.getId()).getOrNull().shouldNotBeNull()
        found.user.username shouldBe "ash"
        found.user.roles.shouldContainExactlyInAnyOrder(Role.USER, Role.ADMIN)
        refreshTokenRepository.findForUpdate("a".repeat(64)).getOrNull().shouldNotBeNull()
            .session.getId() shouldBe session.getId()
        shouldThrow<DataIntegrityViolationException> {
            userRepository.saveAndFlush(AppUser("ash", "other", setOf(Role.USER)))
        }
    }

    @Test
    fun storesUsernamesOnlyInLowercase() {
        shouldThrow<DataIntegrityViolationException> {
            userRepository.saveAndFlush(AppUser("Misty", "hash", setOf(Role.USER)))
        }
    }

    @Test
    fun deletingAUserDeletesItsSessionsAndRefreshTokens() {
        val now = Instant.now()
        val ash = userRepository.saveAndFlush(AppUser("ash", "hash", setOf(Role.USER)))
        val session = sessionRepository.saveAndFlush(AuthSession(ash, now, now.plusSeconds(3600)))
        refreshTokenRepository.saveAndFlush(RefreshToken("b".repeat(64), session, 0, now, now.plusSeconds(900)))

        entityManager.entityManager.createNativeQuery("DELETE FROM app_user").executeUpdate()
        entityManager.clear()

        sessionRepository.count() shouldBe 0
        refreshTokenRepository.count() shouldBe 0
    }

    @Test
    fun allowsOneLocalizedNamePerLocale() {
        val pikachu = repository.saveAndFlush(Pokemon(25, "pikachu", 4, 60, 112, null, listOf()))
        localizedNameRepository.saveAndFlush(PokemonLocalizedName(pikachu, "ja-JP", "Pikachu"))

        shouldThrow<DataIntegrityViolationException> {
            localizedNameRepository.saveAndFlush(PokemonLocalizedName(pikachu, "ja-JP", "Pika"))
        }
    }

    @Test
    fun allowsOnlyOneRunningSyncRun() {
        syncRunRepository.saveAndFlush(SyncRun(SyncTrigger.API, Instant.now()))

        shouldThrow<DataIntegrityViolationException> {
            syncRunRepository.saveAndFlush(SyncRun(SyncTrigger.SCHEDULED, Instant.now()))
        }
    }

    @Test
    fun savesAndFindsByNameIgnoringCase() {
        repository.saveAndFlush(Pokemon(4, "charmander", 6, 85, 62, "sprite.png", listOf("fire")))

        val pokemon = repository.findByNameIgnoreCase("CharMander").getOrNull().shouldNotBeNull()
        pokemon.id.shouldNotBeNull()
        pokemon.types.shouldContainExactly("fire")
        pokemon.createdAt.shouldNotBeNull()
    }

    @Test
    fun findsByPokeApiIdAndKeepsTypeOrder() {
        repository.saveAndFlush(Pokemon(6, "charizard", 17, 905, 267, null, listOf("fire", "flying")))

        repository.findByPokeApiId(6).getOrNull().shouldNotBeNull().types.shouldContainExactly("fire", "flying")
    }

    @Test
    fun enforcesUniqueName() {
        repository.saveAndFlush(Pokemon(7, "squirtle", 5, 90, 63, null, listOf("water")))

        shouldThrow<DataIntegrityViolationException> {
            repository.saveAndFlush(Pokemon(9999, "squirtle", 5, 90, 63, null, listOf("water")))
        }
    }
}
