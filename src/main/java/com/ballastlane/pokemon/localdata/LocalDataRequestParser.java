package com.ballastlane.pokemon.localdata;

import static com.ballastlane.pokemon.localdata.LocalDataLimits.MAX_GEO_ENTRIES;
import static com.ballastlane.pokemon.localdata.LocalDataLimits.MAX_GEO_NOTE_LENGTH;
import static com.ballastlane.pokemon.localdata.LocalDataLimits.MAX_GEO_VALUE_LENGTH;
import static com.ballastlane.pokemon.localdata.LocalDataLimits.MAX_LOCALE_LENGTH;
import static com.ballastlane.pokemon.localdata.LocalDataLimits.MAX_LOCALIZED_NAMES;
import static com.ballastlane.pokemon.localdata.LocalDataLimits.MAX_LOCALIZED_NAME_LENGTH;
import static com.ballastlane.pokemon.localdata.LocalDataLimits.MAX_TAGS;
import static com.ballastlane.pokemon.localdata.LocalDataLimits.MAX_TAG_LENGTH;

import com.ballastlane.pokemon.domain.GeoKind;
import com.ballastlane.pokemon.exception.InvalidLocalDataException;
import com.ballastlane.pokemon.localdata.LocalDataUpdate.GeoEntryValue;
import com.ballastlane.pokemon.localdata.LocalDataUpdate.LocalizedNameValue;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the raw body of a local data update into a validated, normalized {@link LocalDataUpdate}.
 * <p>
 * The body is parsed here, with a strict mapper of its own, instead of being bound by Spring MVC. That keeps the
 * application-wide mapper lenient (PokeAPI responses rely on it), and it means every problem, including a wrong
 * JSON type, comes back as a field-level message instead of a Jackson error. All problems are collected and
 * reported together.
 */
@Component
public class LocalDataRequestParser {

	static final String LOCALIZED_NAMES = "localizedNames";

	static final String GEO_METADATA = "geoMetadata";

	static final String TAGS = "tags";

	private static final Set<String> KINDS = Set.of(LOCALIZED_NAMES, GEO_METADATA, TAGS);

	/** Fields of the detailed view and of the upstream record: they exist, but they can't be changed here. */
	private static final Set<String> READ_ONLY_FIELDS = Set.of("id", "pokeApiId", "name", "imageUrl", "spriteUrl",
			"stats", "totalStats", "description", "evolution", "types", "abilities", "height", "weight",
			"baseExperience", "species", "lastSyncedAt", "localData", "version", "modifiedAt");

	private static final Pattern TAG = Pattern.compile("[a-z0-9_-]+");

	private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder()
					.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
					.streamReadConstraints(StreamReadConstraints.builder()
							.maxNestingDepth(LocalDataLimits.MAX_JSON_DEPTH)
							.build())
					.build())
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.build();

	/**
	 * Parses a full replacement (PUT): every kind of proprietary data must be present.
	 */
	public LocalDataUpdate parseReplacement(byte[] body) {
		return parse(body, false);
	}

	/**
	 * Parses a partial update (PATCH, JSON Merge Patch): a missing kind is kept and {@code null} clears it.
	 */
	public LocalDataUpdate parsePatch(byte[] body) {
		return parse(body, true);
	}

	private LocalDataUpdate parse(byte[] body, boolean partial) {
		Problems problems = new Problems();
		JsonNode root = readTree(body, problems);
		if (root == null) {
			throw new InvalidLocalDataException(problems.list);
		}

		for (Map.Entry<String, JsonNode> field : root.properties()) {
			String name = field.getKey();
			if (READ_ONLY_FIELDS.contains(name)) {
				problems.add(name, "is read only");
			}
			else if (!KINDS.contains(name)) {
				problems.add(name, "is not a known field");
			}
		}

		LocalDataUpdate update = new LocalDataUpdate(
				list(root, LOCALIZED_NAMES, MAX_LOCALIZED_NAMES, partial, problems, this::localizedName),
				list(root, GEO_METADATA, MAX_GEO_ENTRIES, partial, problems, this::geoEntry),
				list(root, TAGS, MAX_TAGS, partial, problems, this::tag));
		checkDuplicates(update, problems);

		if (!problems.list.isEmpty()) {
			throw new InvalidLocalDataException(problems.list);
		}
		return update;
	}

	private JsonNode readTree(byte[] body, Problems problems) {
		if (body == null || body.length == 0) {
			problems.add("body", "is required");
			return null;
		}
		JsonNode root;
		try {
			root = mapper.readTree(body);
		}
		catch (StreamConstraintsException ex) {
			problems.add("body", "is nested too deeply");
			return null;
		}
		catch (JacksonException ex) {
			// The parser's own message can quote the input, so it is never passed on.
			String message = ex.getOriginalMessage() != null && ex.getOriginalMessage().startsWith("Duplicate")
					? "has a duplicate field" : "is not valid JSON";
			problems.add("body", message);
			return null;
		}
		if (root == null || root.isMissingNode()) {
			problems.add("body", "is required");
			return null;
		}
		if (!root.isObject()) {
			problems.add("body", "must be a JSON object");
			return null;
		}
		return root;
	}

	/**
	 * @return the parsed entries; {@code null} to keep this kind of data (absent from a partial update, or invalid)
	 */
	private <T> List<T> list(JsonNode root, String kind, int maxSize, boolean partial, Problems problems,
			EntryParser<T> entryParser) {
		JsonNode node = root.get(kind);
		if (node == null) {
			if (!partial) {
				problems.add(kind, "is required");
			}
			return null;
		}
		if (node.isNull()) {
			if (!partial) {
				problems.add(kind, "is required; send an empty list to clear it");
				return null;
			}
			return List.of();
		}
		if (!node.isArray()) {
			problems.add(kind, "must be a list");
			return null;
		}
		if (node.size() > maxSize) {
			problems.add(kind, "must have at most %d entries".formatted(maxSize));
			return null;
		}
		List<T> entries = new ArrayList<>(node.size());
		for (int i = 0; i < node.size(); i++) {
			T entry = entryParser.apply("%s[%d]".formatted(kind, i), node.get(i), problems);
			if (entry != null) {
				entries.add(entry);
			}
		}
		return entries;
	}

	private LocalizedNameValue localizedName(String path, JsonNode node, Problems problems) {
		if (!isObject(path, node, problems, Set.of("locale", "name"))) {
			return null;
		}
		String locale = locale(path + ".locale", node.get("locale"), problems);
		String name = text(path + ".name", node.get("name"), true, MAX_LOCALIZED_NAME_LENGTH, false, problems);
		return locale != null && name != null ? new LocalizedNameValue(locale, name) : null;
	}

	private GeoEntryValue geoEntry(String path, JsonNode node, Problems problems) {
		if (!isObject(path, node, problems, Set.of("kind", "value", "note"))) {
			return null;
		}
		GeoKind kind = geoKind(path + ".kind", node.get("kind"), problems);
		String value = text(path + ".value", node.get("value"), true, MAX_GEO_VALUE_LENGTH, false, problems);
		JsonNode noteNode = node.get("note");
		String note = noteNode == null || noteNode.isNull() ? null
				: text(path + ".note", noteNode, false, MAX_GEO_NOTE_LENGTH, true, problems);
		boolean noteValid = noteNode == null || noteNode.isNull() || note != null;
		return kind != null && value != null && noteValid
				? new GeoEntryValue(kind, value, note == null || note.isEmpty() ? null : note)
				: null;
	}

	private String tag(String path, JsonNode node, Problems problems) {
		String tag = text(path, node, true, MAX_TAG_LENGTH, false, problems);
		if (tag == null) {
			return null;
		}
		String normalized = tag.toLowerCase(Locale.ROOT);
		if (!TAG.matcher(normalized).matches()) {
			problems.add(path, "may only contain letters, digits, '-' and '_'");
			return null;
		}
		return normalized;
	}

	private String locale(String path, JsonNode node, Problems problems) {
		String raw = text(path, node, true, MAX_LOCALE_LENGTH, false, problems);
		if (raw == null) {
			return null;
		}
		try {
			String canonical = new Locale.Builder().setLanguageTag(raw).build().toLanguageTag();
			if (canonical.length() > MAX_LOCALE_LENGTH) {
				problems.add(path, "must be at most %d characters".formatted(MAX_LOCALE_LENGTH));
				return null;
			}
			return canonical;
		}
		catch (IllformedLocaleException ex) {
			problems.add(path, "must be a well-formed language tag, such as 'en' or 'ja-JP'");
			return null;
		}
	}

	private GeoKind geoKind(String path, JsonNode node, Problems problems) {
		String raw = text(path, node, true, 20, false, problems);
		if (raw == null) {
			return null;
		}
		try {
			return GeoKind.valueOf(raw.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			problems.add(path, "must be one of: region, area, habitat");
			return null;
		}
	}

	/**
	 * @return the trimmed text, or {@code null} after recording why it is invalid
	 */
	private static String text(String path, JsonNode node, boolean required, int maxLength, boolean multiline,
			Problems problems) {
		if (node == null || node.isNull()) {
			problems.add(path, "is required");
			return null;
		}
		if (!node.isString()) {
			problems.add(path, "must be a string");
			return null;
		}
		String value = node.stringValue().strip();
		if (required && value.isEmpty()) {
			problems.add(path, "must not be blank");
			return null;
		}
		if (value.codePointCount(0, value.length()) > maxLength) {
			problems.add(path, "must be at most %d characters".formatted(maxLength));
			return null;
		}
		if (value.codePoints().anyMatch(c -> Character.isISOControl(c) && !(multiline && isLineBreakOrTab(c)))) {
			problems.add(path, "must not contain control characters");
			return null;
		}
		return value;
	}

	private static boolean isLineBreakOrTab(int c) {
		return c == '\n' || c == '\r' || c == '\t';
	}

	private static boolean isObject(String path, JsonNode node, Problems problems, Set<String> fields) {
		if (node == null || !node.isObject()) {
			problems.add(path, "must be an object");
			return false;
		}
		for (String name : node.propertyNames()) {
			if (!fields.contains(name)) {
				problems.add(path + "." + name, "is not a known field");
			}
		}
		return true;
	}

	private static void checkDuplicates(LocalDataUpdate update, Problems problems) {
		if (update.localizedNames() != null) {
			Set<String> seen = new HashSet<>();
			update.localizedNames().stream()
					.map(LocalizedNameValue::locale)
					.filter(locale -> !seen.add(locale.toLowerCase(Locale.ROOT)))
					.distinct()
					.forEach(locale -> problems.add(LOCALIZED_NAMES, "has more than one name for locale " + locale));
		}
		if (update.tags() != null) {
			Set<String> seen = new HashSet<>();
			update.tags().stream()
					.filter(tag -> !seen.add(tag))
					.distinct()
					.forEach(tag -> problems.add(TAGS, "has the tag '%s' more than once".formatted(tag)));
		}
		// Geographical entries may repeat: the same kind and value can be recorded more than once.
	}

	@FunctionalInterface
	private interface EntryParser<T> {

		T apply(String path, JsonNode node, Problems problems);

	}

	private static final class Problems {

		private final List<FieldProblem> list = new ArrayList<>();

		void add(String field, String message) {
			list.add(new FieldProblem(field, message));
		}

	}

}
