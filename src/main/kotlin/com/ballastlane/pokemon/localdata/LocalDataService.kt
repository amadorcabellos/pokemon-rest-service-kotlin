package com.ballastlane.pokemon.localdata

import com.ballastlane.pokemon.domain.Pokemon
import com.ballastlane.pokemon.domain.PokemonGeoMetadata
import com.ballastlane.pokemon.domain.PokemonLocalizedName
import com.ballastlane.pokemon.domain.PokemonTag
import com.ballastlane.pokemon.exception.LocalDataVersionMismatchException
import com.ballastlane.pokemon.exception.PokemonNotStoredLocallyException
import com.ballastlane.pokemon.localdata.LocalDataRequestParser.Companion.GEO_METADATA
import com.ballastlane.pokemon.localdata.LocalDataRequestParser.Companion.LOCALIZED_NAMES
import com.ballastlane.pokemon.localdata.LocalDataRequestParser.Companion.TAGS
import com.ballastlane.pokemon.localdata.LocalDataUpdate.GeoEntryValue
import com.ballastlane.pokemon.localdata.LocalDataUpdate.LocalizedNameValue
import com.ballastlane.pokemon.repository.LocalDataHeader
import com.ballastlane.pokemon.repository.PokemonGeoMetadataRepository
import com.ballastlane.pokemon.repository.PokemonLocalizedNameRepository
import com.ballastlane.pokemon.repository.PokemonRepository
import com.ballastlane.pokemon.repository.PokemonTagRepository
import com.ballastlane.pokemon.text.javaStrip
import com.ballastlane.pokemon.web.dto.GeoEntry
import com.ballastlane.pokemon.web.dto.LocalData
import com.ballastlane.pokemon.web.dto.LocalizedName
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.jvm.optionals.getOrNull
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Reads and updates the proprietary data (localized names, geographical metadata, tags) of locally stored pokemon.
 * Only the local database is used; PokeAPI is never called.
 */
@Service
class LocalDataService(
    private val pokemonRepository: PokemonRepository,
    private val localizedNameRepository: PokemonLocalizedNameRepository,
    private val geoMetadataRepository: PokemonGeoMetadataRepository,
    private val tagRepository: PokemonTagRepository,
) {

    /**
     * The current local data of a pokemon, or [LocalData.EMPTY] if it isn't stored locally.
     */
    @Transactional(readOnly = true)
    fun read(pokeApiId: Int): LocalData =
        pokemonRepository.findLocalDataHeaderByPokeApiId(pokeApiId).getOrNull()?.let(::toLocalData) ?: LocalData.EMPTY

    /**
     * Applies an update in one transaction. Updates of the same pokemon run one after the other (row lock), and an
     * update that changes nothing keeps the version and modified time.
     *
     * @param ifMatch the version the caller based the update on, or `null` for "last write wins"
     * @throws PokemonNotStoredLocallyException if the pokemon is not in the local database
     * @throws LocalDataVersionMismatchException if `ifMatch` doesn't match the current version
     */
    @Transactional
    fun update(nameOrId: String, update: LocalDataUpdate, ifMatch: IfMatch?): LocalData {
        val id = (findLocally(nameOrId) ?: throw PokemonNotStoredLocallyException(nameOrId)).pokemonId
        val version = pokemonRepository.lockLocalDataVersion(id)
        if (ifMatch != null && !ifMatch.matches(version)) {
            throw LocalDataVersionMismatchException(version)
        }

        val pokemon = pokemonRepository.getReferenceById(id)
        val changed = buildList {
            if (update.localizedNames != null && replaceLocalizedNames(pokemon, id, update.localizedNames)) {
                add(LOCALIZED_NAMES)
            }
            if (update.geoMetadata != null && replaceGeoMetadata(pokemon, id, update.geoMetadata)) {
                add(GEO_METADATA)
            }
            if (update.tags != null && replaceTags(pokemon, id, update.tags)) {
                add(TAGS)
            }
        }

        if (changed.isNotEmpty()) {
            pokemonRepository.bumpLocalDataVersion(id, Instant.now().truncatedTo(ChronoUnit.MICROS))
            log.info("Updated local data of pokemon {} to version {}: {}", nameOrId, version + 1, changed)
        }
        return toLocalData(pokemonRepository.findLocalDataHeaderById(id).orElseThrow())
    }

    /**
     * Same rules as the lookup: trimmed and case-insensitive; digits only means a PokeAPI id.
     */
    private fun findLocally(nameOrId: String): LocalDataHeader? {
        val key = nameOrId.javaStrip().lowercase(Locale.ROOT)
        if (key.isEmpty() || !key.all { Character.isDigit(it) }) {
            return pokemonRepository.findLocalDataHeaderByName(key).getOrNull()
        }
        return try {
            pokemonRepository.findLocalDataHeaderByPokeApiId(key.toInt()).getOrNull()
        } catch (ex: NumberFormatException) {
            // Too large to be a PokeAPI id.
            null
        }
    }

    /**
     * Keyed by locale: changed names are renamed in place, so only what differs is written.
     */
    private fun replaceLocalizedNames(pokemon: Pokemon, pokemonId: Long, wanted: List<LocalizedNameValue>): Boolean {
        val wantedByLocale = wanted.associate { it.locale to it.name }
        val current = localizedNameRepository.findByPokemonId(pokemonId)
        val currentByLocale = current.associateBy { it.locale }

        val removed = current.filter { it.locale !in wantedByLocale }
        deleteFirst(removed, localizedNameRepository::deleteAll, localizedNameRepository::flush)

        var changed = removed.isNotEmpty()
        for (value in wanted) {
            val existing = currentByLocale[value.locale]
            if (existing == null) {
                localizedNameRepository.save(PokemonLocalizedName(pokemon, value.locale, value.name))
                changed = true
            } else if (existing.name != value.name) {
                existing.rename(value.name)
                changed = true
            }
        }
        return changed
    }

    /**
     * Entries may repeat, so they have no key: compared as an unordered collection and replaced as a whole.
     */
    private fun replaceGeoMetadata(pokemon: Pokemon, pokemonId: Long, wanted: List<GeoEntryValue>): Boolean {
        val current = geoMetadataRepository.findByPokemonId(pokemonId)
        val currentValues = current.map { GeoEntryValue(it.kind, it.value, it.note) }
        if (sameElements(currentValues, wanted)) {
            return false
        }
        deleteFirst(current, geoMetadataRepository::deleteAll, geoMetadataRepository::flush)
        geoMetadataRepository.saveAll(wanted.map { PokemonGeoMetadata(pokemon, it.kind, it.value, it.note) })
        return true
    }

    private fun replaceTags(pokemon: Pokemon, pokemonId: Long, wanted: List<String>): Boolean {
        val wantedTags = wanted.toSet()
        val current = tagRepository.findByPokemonId(pokemonId)
        val currentTags = current.map { it.tag }.toSet()

        val removed = current.filter { it.tag !in wantedTags }
        deleteFirst(removed, tagRepository::deleteAll, tagRepository::flush)
        val added = wanted.filter { it !in currentTags }.map { PokemonTag(pokemon, it) }
        if (added.isNotEmpty()) {
            tagRepository.saveAll(added)
        }
        return removed.isNotEmpty() || added.isNotEmpty()
    }

    private fun toLocalData(header: LocalDataHeader): LocalData {
        val id = header.pokemonId
        val names = localizedNameRepository.findByPokemonId(id)
            .map { LocalizedName(it.locale, it.name) }
            .sortedBy { it.locale }
        val geo = geoMetadataRepository.findByPokemonId(id)
            .map { GeoEntry(it.kind.name.lowercase(Locale.ROOT), it.value, it.note) }
            .sortedWith(GEO_ORDER)
        val tags = tagRepository.findByPokemonId(id).map { it.tag }.sorted()
        return LocalData(names, geo, tags, header.modifiedAt, header.version)
    }

    private companion object {

        private val log = LoggerFactory.getLogger(LocalDataService::class.java)

        private val GEO_ORDER = compareBy<GeoEntry>({ it.kind }, { it.value }, { it.note })

        /**
         * Hibernate flushes inserts before deletes, which would break the unique constraints when a value is removed
         * and added back in one update. Flushing the deletes straight away avoids that.
         */
        private inline fun <T> deleteFirst(removed: List<T>, delete: (List<T>) -> Unit, flush: () -> Unit) {
            if (removed.isNotEmpty()) {
                delete(removed)
                flush()
            }
        }

        private fun <T> sameElements(first: List<T>, second: List<T>): Boolean =
            first.size == second.size && first.groupingBy { it }.eachCount() == second.groupingBy { it }.eachCount()
    }
}
