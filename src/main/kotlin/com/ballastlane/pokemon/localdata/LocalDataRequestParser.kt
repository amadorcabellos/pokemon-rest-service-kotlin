package com.ballastlane.pokemon.localdata

import com.ballastlane.pokemon.domain.GeoKind
import com.ballastlane.pokemon.exception.InvalidLocalDataException
import com.ballastlane.pokemon.localdata.LocalDataLimits.MAX_GEO_ENTRIES
import com.ballastlane.pokemon.localdata.LocalDataLimits.MAX_GEO_NOTE_LENGTH
import com.ballastlane.pokemon.localdata.LocalDataLimits.MAX_GEO_VALUE_LENGTH
import com.ballastlane.pokemon.localdata.LocalDataLimits.MAX_LOCALE_LENGTH
import com.ballastlane.pokemon.localdata.LocalDataLimits.MAX_LOCALIZED_NAMES
import com.ballastlane.pokemon.localdata.LocalDataLimits.MAX_LOCALIZED_NAME_LENGTH
import com.ballastlane.pokemon.localdata.LocalDataLimits.MAX_TAGS
import com.ballastlane.pokemon.localdata.LocalDataLimits.MAX_TAG_LENGTH
import com.ballastlane.pokemon.localdata.LocalDataUpdate.GeoEntryValue
import com.ballastlane.pokemon.localdata.LocalDataUpdate.LocalizedNameValue
import com.ballastlane.pokemon.text.javaStrip
import java.util.IllformedLocaleException
import java.util.Locale
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadConstraints
import tools.jackson.core.StreamReadFeature
import tools.jackson.core.exc.StreamConstraintsException
import tools.jackson.core.json.JsonFactory
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Turns the raw body of a local data update into a validated, normalized [LocalDataUpdate].
 *
 * The body is parsed here, with a strict mapper of its own, instead of being bound by Spring MVC. That keeps the
 * application-wide mapper lenient (PokeAPI responses rely on it), and it means every problem, including a wrong
 * JSON type, comes back as a field-level message instead of a Jackson error. All problems are collected and
 * reported together.
 */
@Component
class LocalDataRequestParser {

    private val mapper: JsonMapper = JsonMapper.builder(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints.builder().maxNestingDepth(LocalDataLimits.MAX_JSON_DEPTH).build(),
            )
            .build(),
    )
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build()

    /**
     * Parses a full replacement (PUT): every kind of proprietary data must be present.
     */
    fun parseReplacement(body: ByteArray?): LocalDataUpdate = parse(body, partial = false)

    /**
     * Parses a partial update (PATCH, JSON Merge Patch): a missing kind is kept and `null` clears it.
     */
    fun parsePatch(body: ByteArray?): LocalDataUpdate = parse(body, partial = true)

    private fun parse(body: ByteArray?, partial: Boolean): LocalDataUpdate {
        val problems = Problems()
        val root = readTree(body, problems) ?: throw InvalidLocalDataException(problems.list)

        for (field in root.properties()) {
            val name = field.key
            if (name in READ_ONLY_FIELDS) {
                problems.add(name, "is read only")
            } else if (name !in KINDS) {
                problems.add(name, "is not a known field")
            }
        }

        val update = LocalDataUpdate(
            list(root, LOCALIZED_NAMES, MAX_LOCALIZED_NAMES, partial, problems, ::localizedName),
            list(root, GEO_METADATA, MAX_GEO_ENTRIES, partial, problems, ::geoEntry),
            list(root, TAGS, MAX_TAGS, partial, problems, ::tag),
        )
        checkDuplicates(update, problems)

        if (problems.list.isNotEmpty()) {
            throw InvalidLocalDataException(problems.list)
        }
        return update
    }

    private fun readTree(body: ByteArray?, problems: Problems): JsonNode? {
        if (body == null || body.isEmpty()) {
            problems.add("body", "is required")
            return null
        }
        val root: JsonNode? = try {
            mapper.readTree(body)
        } catch (ex: StreamConstraintsException) {
            problems.add("body", "is nested too deeply")
            return null
        } catch (ex: JacksonException) {
            // The parser's own message can quote the input, so it is never passed on.
            val duplicate = ex.originalMessage?.startsWith("Duplicate") == true
            problems.add("body", if (duplicate) "has a duplicate field" else "is not valid JSON")
            return null
        }
        if (root == null || root.isMissingNode) {
            problems.add("body", "is required")
            return null
        }
        if (!root.isObject) {
            problems.add("body", "must be a JSON object")
            return null
        }
        return root
    }

    /**
     * @return the parsed entries; `null` to keep this kind of data (absent from a partial update, or invalid)
     */
    private fun <T : Any> list(
        root: JsonNode,
        kind: String,
        maxSize: Int,
        partial: Boolean,
        problems: Problems,
        entryParser: (String, JsonNode?, Problems) -> T?,
    ): List<T>? {
        val node = root.get(kind)
        if (node == null) {
            if (!partial) {
                problems.add(kind, "is required")
            }
            return null
        }
        if (node.isNull) {
            if (!partial) {
                problems.add(kind, "is required; send an empty list to clear it")
                return null
            }
            return emptyList()
        }
        if (!node.isArray) {
            problems.add(kind, "must be a list")
            return null
        }
        if (node.size() > maxSize) {
            problems.add(kind, "must have at most $maxSize entries")
            return null
        }
        return (0 until node.size()).mapNotNull { i -> entryParser("$kind[$i]", node.get(i), problems) }
    }

    private fun localizedName(path: String, node: JsonNode?, problems: Problems): LocalizedNameValue? {
        val entry = objectNode(path, node, problems, setOf("locale", "name")) ?: return null
        val locale = locale("$path.locale", entry.get("locale"), problems)
        val name = text("$path.name", entry.get("name"), true, MAX_LOCALIZED_NAME_LENGTH, false, problems)
        return if (locale != null && name != null) LocalizedNameValue(locale, name) else null
    }

    private fun geoEntry(path: String, node: JsonNode?, problems: Problems): GeoEntryValue? {
        val entry = objectNode(path, node, problems, setOf("kind", "value", "note")) ?: return null
        val kind = geoKind("$path.kind", entry.get("kind"), problems)
        val value = text("$path.value", entry.get("value"), true, MAX_GEO_VALUE_LENGTH, false, problems)
        val noteNode = entry.get("note")
        val noteAbsent = noteNode == null || noteNode.isNull
        val note = if (noteAbsent) null else text("$path.note", noteNode, false, MAX_GEO_NOTE_LENGTH, true, problems)
        val noteValid = noteAbsent || note != null
        return if (kind != null && value != null && noteValid) GeoEntryValue(kind, value, note?.ifEmpty { null }) else null
    }

    private fun tag(path: String, node: JsonNode?, problems: Problems): String? {
        val tag = text(path, node, true, MAX_TAG_LENGTH, false, problems) ?: return null
        val normalized = tag.lowercase(Locale.ROOT)
        if (!TAG.matches(normalized)) {
            problems.add(path, "may only contain letters, digits, '-' and '_'")
            return null
        }
        return normalized
    }

    private fun locale(path: String, node: JsonNode?, problems: Problems): String? {
        val raw = text(path, node, true, MAX_LOCALE_LENGTH, false, problems) ?: return null
        return try {
            val canonical = Locale.Builder().setLanguageTag(raw).build().toLanguageTag()
            if (canonical.length > MAX_LOCALE_LENGTH) {
                problems.add(path, "must be at most $MAX_LOCALE_LENGTH characters")
                null
            } else {
                canonical
            }
        } catch (ex: IllformedLocaleException) {
            problems.add(path, "must be a well-formed language tag, such as 'en' or 'ja-JP'")
            null
        }
    }

    private fun geoKind(path: String, node: JsonNode?, problems: Problems): GeoKind? {
        val raw = text(path, node, true, 20, false, problems) ?: return null
        return try {
            GeoKind.valueOf(raw.uppercase(Locale.ROOT))
        } catch (ex: IllegalArgumentException) {
            problems.add(path, "must be one of: region, area, habitat")
            null
        }
    }

    private class Problems {

        val list = mutableListOf<FieldProblem>()

        fun add(field: String, message: String) {
            list.add(FieldProblem(field, message))
        }
    }

    internal companion object {

        const val LOCALIZED_NAMES = "localizedNames"

        const val GEO_METADATA = "geoMetadata"

        const val TAGS = "tags"

        private val KINDS = setOf(LOCALIZED_NAMES, GEO_METADATA, TAGS)

        /** Fields of the detailed view and of the upstream record: they exist, but they can't be changed here. */
        private val READ_ONLY_FIELDS = setOf(
            "id", "pokeApiId", "name", "imageUrl", "spriteUrl", "stats", "totalStats", "description", "evolution",
            "types", "abilities", "height", "weight", "baseExperience", "species", "lastSyncedAt", "localData",
            "version", "modifiedAt",
        )

        private val TAG = Regex("[a-z0-9_-]+")

        /**
         * @return the trimmed text, or `null` after recording why it is invalid
         */
        private fun text(
            path: String,
            node: JsonNode?,
            required: Boolean,
            maxLength: Int,
            multiline: Boolean,
            problems: Problems,
        ): String? {
            if (node == null || node.isNull) {
                problems.add(path, "is required")
                return null
            }
            if (!node.isString) {
                problems.add(path, "must be a string")
                return null
            }
            val value = node.stringValue().javaStrip()
            if (required && value.isEmpty()) {
                problems.add(path, "must not be blank")
                return null
            }
            if (value.codePointCount(0, value.length) > maxLength) {
                problems.add(path, "must be at most $maxLength characters")
                return null
            }
            if (value.codePoints().anyMatch { Character.isISOControl(it) && !(multiline && isLineBreakOrTab(it)) }) {
                problems.add(path, "must not contain control characters")
                return null
            }
            return value
        }

        private fun isLineBreakOrTab(c: Int): Boolean = c == '\n'.code || c == '\r'.code || c == '\t'.code

        /**
         * @return the node if it is an object (unknown fields are recorded), otherwise `null` after recording why
         */
        private fun objectNode(path: String, node: JsonNode?, problems: Problems, fields: Set<String>): JsonNode? {
            if (node == null || !node.isObject) {
                problems.add(path, "must be an object")
                return null
            }
            for (name in node.propertyNames()) {
                if (name !in fields) {
                    problems.add("$path.$name", "is not a known field")
                }
            }
            return node
        }

        private fun checkDuplicates(update: LocalDataUpdate, problems: Problems) {
            update.localizedNames?.let { names ->
                val seen = HashSet<String>()
                names.map { it.locale }
                    .filter { !seen.add(it.lowercase(Locale.ROOT)) }
                    .distinct()
                    .forEach { problems.add(LOCALIZED_NAMES, "has more than one name for locale $it") }
            }
            update.tags?.let { tags ->
                val seen = HashSet<String>()
                tags.filter { !seen.add(it) }
                    .distinct()
                    .forEach { problems.add(TAGS, "has the tag '$it' more than once") }
            }
            // Geographical entries may repeat: the same kind and value can be recorded more than once.
        }
    }
}
