package com.ballastlane.poke.localdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.ballastlane.poke.domain.GeoKind;
import com.ballastlane.poke.domain.Pokemon;
import com.ballastlane.poke.domain.PokemonGeoMetadata;
import com.ballastlane.poke.domain.PokemonLocalizedName;
import com.ballastlane.poke.domain.PokemonTag;
import com.ballastlane.poke.exception.LocalDataVersionMismatchException;
import com.ballastlane.poke.exception.PokemonNotStoredLocallyException;
import com.ballastlane.poke.localdata.LocalDataUpdate.GeoEntryValue;
import com.ballastlane.poke.localdata.LocalDataUpdate.LocalizedNameValue;
import com.ballastlane.poke.repository.LocalDataHeader;
import com.ballastlane.poke.repository.PokemonGeoMetadataRepository;
import com.ballastlane.poke.repository.PokemonLocalizedNameRepository;
import com.ballastlane.poke.repository.PokemonRepository;
import com.ballastlane.poke.repository.PokemonTagRepository;
import com.ballastlane.poke.web.dto.GeoEntry;
import com.ballastlane.poke.web.dto.LocalData;
import com.ballastlane.poke.web.dto.LocalizedName;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LocalDataServiceTest {

	private static final long ID = 7L;

	private static final LocalDataHeader HEADER = new LocalDataHeader(ID, 2, Instant.parse("2026-10-01T00:00:00Z"));

	@Mock
	private PokemonRepository pokemonRepository;

	@Mock
	private PokemonLocalizedNameRepository localizedNameRepository;

	@Mock
	private PokemonGeoMetadataRepository geoMetadataRepository;

	@Mock
	private PokemonTagRepository tagRepository;

	private final Pokemon pikachu = mock(Pokemon.class);

	private LocalDataService service;

	@BeforeEach
	void setUp() {
		service = new LocalDataService(pokemonRepository, localizedNameRepository, geoMetadataRepository,
				tagRepository);
		given(pikachu.getId()).willReturn(ID);
		given(pokemonRepository.findLocalDataHeaderByName("pikachu")).willReturn(Optional.of(HEADER));
		given(pokemonRepository.findLocalDataHeaderByPokeApiId(25)).willReturn(Optional.of(HEADER));
		given(pokemonRepository.findLocalDataHeaderById(ID)).willReturn(Optional.of(HEADER));
		given(pokemonRepository.lockLocalDataVersion(ID)).willReturn(2L);
		given(pokemonRepository.getReferenceById(ID)).willReturn(pikachu);
	}

	@Test
	void readsSortedLocalData() {
		given(localizedNameRepository.findByPokemonId(ID)).willReturn(List.of(
				new PokemonLocalizedName(pikachu, "ja-JP", "ピカチュウ"), new PokemonLocalizedName(pikachu, "en", "Pikachu")));
		given(geoMetadataRepository.findByPokemonId(ID)).willReturn(List.of(
				new PokemonGeoMetadata(pikachu, GeoKind.REGION, "kanto", null),
				new PokemonGeoMetadata(pikachu, GeoKind.HABITAT, "forest", "Viridian")));
		given(tagRepository.findByPokemonId(ID)).willReturn(List.of(
				new PokemonTag(pikachu, "starter"), new PokemonTag(pikachu, "mascot")));

		LocalData data = service.read(25);

		assertThat(data.localizedNames()).containsExactly(new LocalizedName("en", "Pikachu"),
				new LocalizedName("ja-JP", "ピカチュウ"));
		assertThat(data.geoMetadata()).containsExactly(new GeoEntry("habitat", "forest", "Viridian"),
				new GeoEntry("region", "kanto", null));
		assertThat(data.tags()).containsExactly("mascot", "starter");
		assertThat(data.version()).isEqualTo(2);
		assertThat(data.modifiedAt()).isEqualTo(HEADER.modifiedAt());
	}

	@Test
	void readsNothingForAPokemonThatIsNotStored() {
		assertThat(service.read(9999)).isEqualTo(LocalData.EMPTY);
	}

	@Test
	void replacesOnlyWhatChangedAndBumpsTheVersion() {
		PokemonLocalizedName english = new PokemonLocalizedName(pikachu, "en", "Pika");
		PokemonLocalizedName french = new PokemonLocalizedName(pikachu, "fr", "Pikachu");
		given(localizedNameRepository.findByPokemonId(ID)).willReturn(List.of(english, french));
		PokemonGeoMetadata oldGeo = new PokemonGeoMetadata(pikachu, GeoKind.REGION, "johto", null);
		given(geoMetadataRepository.findByPokemonId(ID)).willReturn(List.of(oldGeo));
		PokemonTag mascot = new PokemonTag(pikachu, "mascot");
		PokemonTag starter = new PokemonTag(pikachu, "starter");
		given(tagRepository.findByPokemonId(ID)).willReturn(List.of(mascot, starter));

		service.update("pikachu", new LocalDataUpdate(
				List.of(new LocalizedNameValue("en", "Pikachu"), new LocalizedNameValue("ja-JP", "ピカチュウ")),
				List.of(new GeoEntryValue(GeoKind.REGION, "kanto", null)),
				List.of("starter", "electric")), null);

		assertThat(english.getName()).isEqualTo("Pikachu");
		verify(localizedNameRepository).deleteAll(List.of(french));
		verify(localizedNameRepository).flush();
		ArgumentCaptor<PokemonLocalizedName> savedName = ArgumentCaptor.forClass(PokemonLocalizedName.class);
		verify(localizedNameRepository).save(savedName.capture());
		assertThat(savedName.getValue().getLocale()).isEqualTo("ja-JP");

		verify(geoMetadataRepository).deleteAll(List.of(oldGeo));
		verify(geoMetadataRepository).saveAll(anyList());

		verify(tagRepository).deleteAll(List.of(mascot));
		ArgumentCaptor<List<PokemonTag>> savedTags = ArgumentCaptor.captor();
		verify(tagRepository).saveAll(savedTags.capture());
		assertThat(savedTags.getValue()).extracting(PokemonTag::getTag).containsExactly("electric");

		verify(pokemonRepository).bumpLocalDataVersion(anyLong(), any(Instant.class));
	}

	@Test
	void anUpdateThatChangesNothingKeepsTheVersion() {
		given(localizedNameRepository.findByPokemonId(ID))
				.willReturn(List.of(new PokemonLocalizedName(pikachu, "en", "Pikachu")));
		given(geoMetadataRepository.findByPokemonId(ID)).willReturn(List.of(
				new PokemonGeoMetadata(pikachu, GeoKind.REGION, "kanto", null),
				new PokemonGeoMetadata(pikachu, GeoKind.HABITAT, "forest", null)));
		given(tagRepository.findByPokemonId(ID)).willReturn(List.of(new PokemonTag(pikachu, "mascot")));

		LocalData data = service.update("pikachu", new LocalDataUpdate(
				List.of(new LocalizedNameValue("en", "Pikachu")),
				// Same entries in another order.
				List.of(new GeoEntryValue(GeoKind.HABITAT, "forest", null),
						new GeoEntryValue(GeoKind.REGION, "kanto", null)),
				List.of("mascot")), null);

		assertThat(data.version()).isEqualTo(2);
		verify(pokemonRepository, never()).bumpLocalDataVersion(anyLong(), any());
		verify(localizedNameRepository, never()).save(any());
		verify(geoMetadataRepository, never()).saveAll(anyList());
		verify(tagRepository, never()).saveAll(anyList());
	}

	@Test
	void aPartialUpdateOnlyTouchesTheKindsItSends() {
		service.update("pikachu", new LocalDataUpdate(null, null, List.of("mascot")), null);

		verify(tagRepository).saveAll(anyList());
		verify(localizedNameRepository, never()).deleteAll(anyList());
		verify(localizedNameRepository, never()).save(any());
		verify(geoMetadataRepository, never()).deleteAll(anyList());
		verify(geoMetadataRepository, never()).saveAll(anyList());
	}

	@Test
	void keepsDuplicateGeoEntries() {
		service.update("pikachu", new LocalDataUpdate(null, List.of(
				new GeoEntryValue(GeoKind.REGION, "kanto", null), new GeoEntryValue(GeoKind.REGION, "kanto", null)),
				null), null);

		ArgumentCaptor<List<PokemonGeoMetadata>> saved = ArgumentCaptor.captor();
		verify(geoMetadataRepository).saveAll(saved.capture());
		assertThat(saved.getValue()).hasSize(2);
	}

	@Test
	void rejectsAStaleVersionBeforeWritingAnything() {
		assertThatThrownBy(() -> service.update("pikachu", new LocalDataUpdate(null, null, List.of("mascot")),
				IfMatch.parse("\"1\"")))
				.isInstanceOfSatisfying(LocalDataVersionMismatchException.class,
						ex -> assertThat(ex.getCurrentVersion()).isEqualTo(2));

		verify(tagRepository, never()).saveAll(anyList());
		verify(pokemonRepository, never()).bumpLocalDataVersion(anyLong(), any());
	}

	@Test
	void appliesAnUpdateBasedOnTheCurrentVersion() {
		service.update("pikachu", new LocalDataUpdate(null, null, List.of("mascot")), IfMatch.parse("\"2\""));

		verify(pokemonRepository).bumpLocalDataVersion(anyLong(), any(Instant.class));
	}

	@Test
	void findsPokemonByPokeApiIdIgnoringCaseAndWhitespace() {
		service.update(" 25 ", new LocalDataUpdate(null, null, null), null);
		service.update("PikaChu", new LocalDataUpdate(null, null, null), null);

		verify(pokemonRepository).findLocalDataHeaderByPokeApiId(25);
		verify(pokemonRepository).findLocalDataHeaderByName("pikachu");
	}

	@Test
	void rejectsPokemonThatAreNotStoredLocally() {
		assertThatThrownBy(() -> service.update("raichu", new LocalDataUpdate(null, null, null), null))
				.isInstanceOf(PokemonNotStoredLocallyException.class)
				.hasMessageContaining("'raichu' is not stored locally");
		assertThatThrownBy(() -> service.update("99999999999", new LocalDataUpdate(null, null, null), null))
				.isInstanceOf(PokemonNotStoredLocallyException.class);

		verify(pokemonRepository, never()).lockLocalDataVersion(anyLong());
		verify(pokemonRepository, never()).findLocalDataHeaderByName("99999999999");
		verify(tagRepository, never()).findByPokemonId(anyLong());
		verifyNoMoreInteractions(geoMetadataRepository);
	}

}
