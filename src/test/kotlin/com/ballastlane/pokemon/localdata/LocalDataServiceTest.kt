package com.ballastlane.pokemon.localdata

import com.ballastlane.pokemon.domain.GeoKind
import com.ballastlane.pokemon.domain.Pokemon
import com.ballastlane.pokemon.domain.PokemonGeoMetadata
import com.ballastlane.pokemon.domain.PokemonLocalizedName
import com.ballastlane.pokemon.domain.PokemonTag
import com.ballastlane.pokemon.exception.LocalDataVersionMismatchException
import com.ballastlane.pokemon.exception.PokemonNotStoredLocallyException
import com.ballastlane.pokemon.localdata.LocalDataUpdate.GeoEntryValue
import com.ballastlane.pokemon.localdata.LocalDataUpdate.LocalizedNameValue
import com.ballastlane.pokemon.repository.LocalDataHeader
import com.ballastlane.pokemon.repository.PokemonGeoMetadataRepository
import com.ballastlane.pokemon.repository.PokemonLocalizedNameRepository
import com.ballastlane.pokemon.repository.PokemonRepository
import com.ballastlane.pokemon.repository.PokemonTagRepository
import com.ballastlane.pokemon.web.dto.GeoEntry
import com.ballastlane.pokemon.web.dto.LocalData
import com.ballastlane.pokemon.web.dto.LocalizedName
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Instant
import java.util.Optional
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LocalDataServiceTest {

    private val pokemonRepository = mockk<PokemonRepository>()

    private val localizedNameRepository = mockk<PokemonLocalizedNameRepository>(relaxUnitFun = true)

    private val geoMetadataRepository = mockk<PokemonGeoMetadataRepository>(relaxUnitFun = true)

    private val tagRepository = mockk<PokemonTagRepository>(relaxUnitFun = true)

    private val pikachu = mockk<Pokemon>()

    private lateinit var service: LocalDataService

    @BeforeEach
    fun setUp() {
        service = LocalDataService(pokemonRepository, localizedNameRepository, geoMetadataRepository, tagRepository)
        every { pikachu.id } returns ID
        // What Mockito answered on its own: nothing stored, and saves hand back what they were given.
        every { pokemonRepository.findLocalDataHeaderByName(any()) } returns Optional.empty()
        every { pokemonRepository.findLocalDataHeaderByPokeApiId(any()) } returns Optional.empty()
        every { pokemonRepository.bumpLocalDataVersion(any(), any()) } returns 1
        every { localizedNameRepository.findByPokemonId(any()) } returns listOf()
        every { localizedNameRepository.save(any()) } answers { firstArg() }
        every { geoMetadataRepository.findByPokemonId(any()) } returns listOf()
        every { geoMetadataRepository.saveAll(any<Iterable<PokemonGeoMetadata>>()) } answers { firstArg<Iterable<PokemonGeoMetadata>>().toList() }
        every { tagRepository.findByPokemonId(any()) } returns listOf()
        every { tagRepository.saveAll(any<Iterable<PokemonTag>>()) } answers { firstArg<Iterable<PokemonTag>>().toList() }

        every { pokemonRepository.findLocalDataHeaderByName("pikachu") } returns Optional.of(HEADER)
        every { pokemonRepository.findLocalDataHeaderByPokeApiId(25) } returns Optional.of(HEADER)
        every { pokemonRepository.findLocalDataHeaderById(ID) } returns Optional.of(HEADER)
        every { pokemonRepository.lockLocalDataVersion(ID) } returns 2L
        every { pokemonRepository.getReferenceById(ID) } returns pikachu
    }

    @Test
    fun readsSortedLocalData() {
        every { localizedNameRepository.findByPokemonId(ID) } returns listOf(
            PokemonLocalizedName(pikachu, "ja-JP", "ピカチュウ"), PokemonLocalizedName(pikachu, "en", "Pikachu"),
        )
        every { geoMetadataRepository.findByPokemonId(ID) } returns listOf(
            PokemonGeoMetadata(pikachu, GeoKind.REGION, "kanto", null),
            PokemonGeoMetadata(pikachu, GeoKind.HABITAT, "forest", "Viridian"),
        )
        every { tagRepository.findByPokemonId(ID) } returns listOf(PokemonTag(pikachu, "starter"), PokemonTag(pikachu, "mascot"))

        val data = service.read(25)

        data.localizedNames.shouldContainExactly(LocalizedName("en", "Pikachu"), LocalizedName("ja-JP", "ピカチュウ"))
        data.geoMetadata.shouldContainExactly(GeoEntry("habitat", "forest", "Viridian"), GeoEntry("region", "kanto", null))
        data.tags.shouldContainExactly("mascot", "starter")
        data.version shouldBe 2
        data.modifiedAt shouldBe HEADER.modifiedAt
    }

    @Test
    fun readsNothingForAPokemonThatIsNotStored() {
        service.read(9999) shouldBe LocalData.EMPTY
    }

    @Test
    fun replacesOnlyWhatChangedAndBumpsTheVersion() {
        val english = PokemonLocalizedName(pikachu, "en", "Pika")
        val french = PokemonLocalizedName(pikachu, "fr", "Pikachu")
        every { localizedNameRepository.findByPokemonId(ID) } returns listOf(english, french)
        val oldGeo = PokemonGeoMetadata(pikachu, GeoKind.REGION, "johto", null)
        every { geoMetadataRepository.findByPokemonId(ID) } returns listOf(oldGeo)
        val mascot = PokemonTag(pikachu, "mascot")
        val starter = PokemonTag(pikachu, "starter")
        every { tagRepository.findByPokemonId(ID) } returns listOf(mascot, starter)

        service.update(
            "pikachu",
            LocalDataUpdate(
                listOf(LocalizedNameValue("en", "Pikachu"), LocalizedNameValue("ja-JP", "ピカチュウ")),
                listOf(GeoEntryValue(GeoKind.REGION, "kanto", null)),
                listOf("starter", "electric"),
            ),
            null,
        )

        english.name shouldBe "Pikachu"
        verify(exactly = 1) { localizedNameRepository.deleteAll(listOf(french)) }
        verify(exactly = 1) { localizedNameRepository.flush() }
        val savedName = slot<PokemonLocalizedName>()
        verify(exactly = 1) { localizedNameRepository.save(capture(savedName)) }
        savedName.captured.locale shouldBe "ja-JP"

        verify(exactly = 1) { geoMetadataRepository.deleteAll(listOf(oldGeo)) }
        verify(exactly = 1) { geoMetadataRepository.saveAll(any<Iterable<PokemonGeoMetadata>>()) }

        verify(exactly = 1) { tagRepository.deleteAll(listOf(mascot)) }
        val savedTags = slot<Iterable<PokemonTag>>()
        verify(exactly = 1) { tagRepository.saveAll(capture(savedTags)) }
        savedTags.captured.map { it.tag }.shouldContainExactly("electric")

        verify(exactly = 1) { pokemonRepository.bumpLocalDataVersion(any(), any()) }
    }

    @Test
    fun anUpdateThatChangesNothingKeepsTheVersion() {
        every { localizedNameRepository.findByPokemonId(ID) } returns listOf(PokemonLocalizedName(pikachu, "en", "Pikachu"))
        every { geoMetadataRepository.findByPokemonId(ID) } returns listOf(
            PokemonGeoMetadata(pikachu, GeoKind.REGION, "kanto", null),
            PokemonGeoMetadata(pikachu, GeoKind.HABITAT, "forest", null),
        )
        every { tagRepository.findByPokemonId(ID) } returns listOf(PokemonTag(pikachu, "mascot"))

        val data = service.update(
            "pikachu",
            LocalDataUpdate(
                listOf(LocalizedNameValue("en", "Pikachu")),
                // Same entries in another order.
                listOf(GeoEntryValue(GeoKind.HABITAT, "forest", null), GeoEntryValue(GeoKind.REGION, "kanto", null)),
                listOf("mascot"),
            ),
            null,
        )

        data.version shouldBe 2
        verify(exactly = 0) { pokemonRepository.bumpLocalDataVersion(any(), any()) }
        verify(exactly = 0) { localizedNameRepository.save(any()) }
        verify(exactly = 0) { geoMetadataRepository.saveAll(any<Iterable<PokemonGeoMetadata>>()) }
        verify(exactly = 0) { tagRepository.saveAll(any<Iterable<PokemonTag>>()) }
    }

    @Test
    fun aPartialUpdateOnlyTouchesTheKindsItSends() {
        service.update("pikachu", LocalDataUpdate(null, null, listOf("mascot")), null)

        verify(exactly = 1) { tagRepository.saveAll(any<Iterable<PokemonTag>>()) }
        verify(exactly = 0) { localizedNameRepository.deleteAll(any<Iterable<PokemonLocalizedName>>()) }
        verify(exactly = 0) { localizedNameRepository.save(any()) }
        verify(exactly = 0) { geoMetadataRepository.deleteAll(any<Iterable<PokemonGeoMetadata>>()) }
        verify(exactly = 0) { geoMetadataRepository.saveAll(any<Iterable<PokemonGeoMetadata>>()) }
    }

    @Test
    fun keepsDuplicateGeoEntries() {
        service.update(
            "pikachu",
            LocalDataUpdate(
                null,
                listOf(GeoEntryValue(GeoKind.REGION, "kanto", null), GeoEntryValue(GeoKind.REGION, "kanto", null)),
                null,
            ),
            null,
        )

        val saved = slot<Iterable<PokemonGeoMetadata>>()
        verify(exactly = 1) { geoMetadataRepository.saveAll(capture(saved)) }
        saved.captured.toList() shouldHaveSize 2
    }

    @Test
    fun rejectsAStaleVersionBeforeWritingAnything() {
        shouldThrow<LocalDataVersionMismatchException> {
            service.update("pikachu", LocalDataUpdate(null, null, listOf("mascot")), IfMatch.parse("\"1\""))
        }.currentVersion shouldBe 2

        verify(exactly = 0) { tagRepository.saveAll(any<Iterable<PokemonTag>>()) }
        verify(exactly = 0) { pokemonRepository.bumpLocalDataVersion(any(), any()) }
    }

    @Test
    fun appliesAnUpdateBasedOnTheCurrentVersion() {
        service.update("pikachu", LocalDataUpdate(null, null, listOf("mascot")), IfMatch.parse("\"2\""))

        verify(exactly = 1) { pokemonRepository.bumpLocalDataVersion(any(), any()) }
    }

    @Test
    fun findsPokemonByPokeApiIdIgnoringCaseAndWhitespace() {
        service.update(" 25 ", LocalDataUpdate(null, null, null), null)
        service.update("PikaChu", LocalDataUpdate(null, null, null), null)

        verify(exactly = 1) { pokemonRepository.findLocalDataHeaderByPokeApiId(25) }
        verify(exactly = 1) { pokemonRepository.findLocalDataHeaderByName("pikachu") }
    }

    @Test
    fun rejectsPokemonThatAreNotStoredLocally() {
        shouldThrow<PokemonNotStoredLocallyException> {
            service.update("raichu", LocalDataUpdate(null, null, null), null)
        }.message shouldContain "'raichu' is not stored locally"
        shouldThrow<PokemonNotStoredLocallyException> {
            service.update("99999999999", LocalDataUpdate(null, null, null), null)
        }

        verify(exactly = 0) { pokemonRepository.lockLocalDataVersion(any()) }
        verify(exactly = 0) { pokemonRepository.findLocalDataHeaderByName("99999999999") }
        verify(exactly = 0) { tagRepository.findByPokemonId(any()) }
        confirmVerified(geoMetadataRepository)
    }

    private companion object {

        const val ID = 7L

        val HEADER = LocalDataHeader(ID, 2, Instant.parse("2026-10-01T00:00:00Z"))
    }
}
