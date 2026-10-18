package com.ballastlane.poke.localdata;

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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and updates the proprietary data (localized names, geographical metadata, tags) of locally stored pokemon.
 * Only the local database is used; PokeAPI is never called.
 */
@Service
public class LocalDataService {

	private static final Logger log = LoggerFactory.getLogger(LocalDataService.class);

	private static final Comparator<GeoEntry> GEO_ORDER = Comparator.comparing(GeoEntry::kind)
			.thenComparing(GeoEntry::value)
			.thenComparing(GeoEntry::note, Comparator.nullsFirst(Comparator.naturalOrder()));

	private final PokemonRepository pokemonRepository;

	private final PokemonLocalizedNameRepository localizedNameRepository;

	private final PokemonGeoMetadataRepository geoMetadataRepository;

	private final PokemonTagRepository tagRepository;

	public LocalDataService(PokemonRepository pokemonRepository,
			PokemonLocalizedNameRepository localizedNameRepository,
			PokemonGeoMetadataRepository geoMetadataRepository, PokemonTagRepository tagRepository) {
		this.pokemonRepository = pokemonRepository;
		this.localizedNameRepository = localizedNameRepository;
		this.geoMetadataRepository = geoMetadataRepository;
		this.tagRepository = tagRepository;
	}

	/**
	 * The current local data of a pokemon, or {@link LocalData#EMPTY} if it isn't stored locally.
	 */
	@Transactional(readOnly = true)
	public LocalData read(int pokeApiId) {
		return pokemonRepository.findLocalDataHeaderByPokeApiId(pokeApiId)
				.map(this::toLocalData)
				.orElse(LocalData.EMPTY);
	}

	/**
	 * Applies an update in one transaction. Updates of the same pokemon run one after the other (row lock), and an
	 * update that changes nothing keeps the version and modified time.
	 *
	 * @param ifMatch the version the caller based the update on, or {@code null} for "last write wins"
	 * @throws PokemonNotStoredLocallyException if the pokemon is not in the local database
	 * @throws LocalDataVersionMismatchException if {@code ifMatch} doesn't match the current version
	 */
	@Transactional
	public LocalData update(String nameOrId, LocalDataUpdate update, IfMatch ifMatch) {
		Long id = findLocally(nameOrId)
				.orElseThrow(() -> new PokemonNotStoredLocallyException(nameOrId))
				.pokemonId();
		long version = pokemonRepository.lockLocalDataVersion(id);
		if (ifMatch != null && !ifMatch.matches(version)) {
			throw new LocalDataVersionMismatchException(version);
		}

		Pokemon pokemon = pokemonRepository.getReferenceById(id);
		List<String> changed = new ArrayList<>();
		if (update.localizedNames() != null && replaceLocalizedNames(pokemon, update.localizedNames())) {
			changed.add(LocalDataRequestParser.LOCALIZED_NAMES);
		}
		if (update.geoMetadata() != null && replaceGeoMetadata(pokemon, update.geoMetadata())) {
			changed.add(LocalDataRequestParser.GEO_METADATA);
		}
		if (update.tags() != null && replaceTags(pokemon, update.tags())) {
			changed.add(LocalDataRequestParser.TAGS);
		}

		if (!changed.isEmpty()) {
			pokemonRepository.bumpLocalDataVersion(id, Instant.now().truncatedTo(ChronoUnit.MICROS));
			log.info("Updated local data of pokemon {} to version {}: {}", nameOrId, version + 1, changed);
		}
		return toLocalData(pokemonRepository.findLocalDataHeaderById(id).orElseThrow());
	}

	/**
	 * Same rules as the lookup: trimmed and case-insensitive; digits only means a PokeAPI id.
	 */
	private Optional<LocalDataHeader> findLocally(String nameOrId) {
		String key = nameOrId.strip().toLowerCase(Locale.ROOT);
		if (key.isEmpty() || !key.chars().allMatch(Character::isDigit)) {
			return pokemonRepository.findLocalDataHeaderByName(key);
		}
		try {
			return pokemonRepository.findLocalDataHeaderByPokeApiId(Integer.parseInt(key));
		}
		catch (NumberFormatException ex) {
			// Too large to be a PokeAPI id.
			return Optional.empty();
		}
	}

	/**
	 * Keyed by locale: changed names are renamed in place, so only what differs is written.
	 */
	private boolean replaceLocalizedNames(Pokemon pokemon, List<LocalizedNameValue> wanted) {
		Map<String, String> wantedByLocale = wanted.stream()
				.collect(Collectors.toMap(LocalizedNameValue::locale, LocalizedNameValue::name));
		List<PokemonLocalizedName> current = localizedNameRepository.findByPokemonId(pokemon.getId());
		Map<String, PokemonLocalizedName> currentByLocale = current.stream()
				.collect(Collectors.toMap(PokemonLocalizedName::getLocale, Function.identity()));

		List<PokemonLocalizedName> removed = current.stream()
				.filter(name -> !wantedByLocale.containsKey(name.getLocale()))
				.toList();
		deleteFirst(localizedNameRepository::deleteAll, removed, localizedNameRepository::flush);

		boolean changed = !removed.isEmpty();
		for (LocalizedNameValue value : wanted) {
			PokemonLocalizedName existing = currentByLocale.get(value.locale());
			if (existing == null) {
				localizedNameRepository.save(new PokemonLocalizedName(pokemon, value.locale(), value.name()));
				changed = true;
			}
			else if (!existing.getName().equals(value.name())) {
				existing.rename(value.name());
				changed = true;
			}
		}
		return changed;
	}

	/**
	 * Entries may repeat, so they have no key: compared as an unordered collection and replaced as a whole.
	 */
	private boolean replaceGeoMetadata(Pokemon pokemon, List<GeoEntryValue> wanted) {
		List<PokemonGeoMetadata> current = geoMetadataRepository.findByPokemonId(pokemon.getId());
		List<GeoEntryValue> currentValues = current.stream()
				.map(geo -> new GeoEntryValue(geo.getKind(), geo.getValue(), geo.getNote()))
				.toList();
		if (sameElements(currentValues, wanted)) {
			return false;
		}
		deleteFirst(geoMetadataRepository::deleteAll, current, geoMetadataRepository::flush);
		geoMetadataRepository.saveAll(wanted.stream()
				.map(value -> new PokemonGeoMetadata(pokemon, value.kind(), value.value(), value.note()))
				.toList());
		return true;
	}

	private boolean replaceTags(Pokemon pokemon, List<String> wanted) {
		Set<String> wantedTags = Set.copyOf(wanted);
		List<PokemonTag> current = tagRepository.findByPokemonId(pokemon.getId());
		Set<String> currentTags = current.stream().map(PokemonTag::getTag).collect(Collectors.toSet());

		List<PokemonTag> removed = current.stream().filter(tag -> !wantedTags.contains(tag.getTag())).toList();
		deleteFirst(tagRepository::deleteAll, removed, tagRepository::flush);
		List<PokemonTag> added = wanted.stream()
				.filter(tag -> !currentTags.contains(tag))
				.map(tag -> new PokemonTag(pokemon, tag))
				.toList();
		if (!added.isEmpty()) {
			tagRepository.saveAll(added);
		}
		return !removed.isEmpty() || !added.isEmpty();
	}

	/**
	 * Hibernate flushes inserts before deletes, which would break the unique constraints when a value is removed
	 * and added back in one update. Flushing the deletes straight away avoids that.
	 */
	private static <T> void deleteFirst(Consumer<List<T>> delete, List<T> removed, Runnable flush) {
		if (!removed.isEmpty()) {
			delete.accept(removed);
			flush.run();
		}
	}

	private static <T> boolean sameElements(List<T> first, List<T> second) {
		return first.size() == second.size() && counts(first).equals(counts(second));
	}

	private static <T> Map<T, Long> counts(List<T> values) {
		return values.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
	}

	private LocalData toLocalData(LocalDataHeader header) {
		Long id = header.pokemonId();
		List<LocalizedName> names = localizedNameRepository.findByPokemonId(id).stream()
				.map(name -> new LocalizedName(name.getLocale(), name.getName()))
				.sorted(Comparator.comparing(LocalizedName::locale))
				.toList();
		List<GeoEntry> geo = geoMetadataRepository.findByPokemonId(id).stream()
				.map(entry -> new GeoEntry(entry.getKind().name().toLowerCase(Locale.ROOT), entry.getValue(),
						entry.getNote()))
				.sorted(GEO_ORDER)
				.toList();
		List<String> tags = tagRepository.findByPokemonId(id).stream()
				.map(PokemonTag::getTag)
				.sorted()
				.toList();
		return new LocalData(names, geo, tags, header.modifiedAt(), header.version());
	}

}
