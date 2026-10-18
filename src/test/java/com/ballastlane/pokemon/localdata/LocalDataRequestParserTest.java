package com.ballastlane.pokemon.localdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ballastlane.pokemon.domain.GeoKind;
import com.ballastlane.pokemon.exception.InvalidLocalDataException;
import com.ballastlane.pokemon.localdata.LocalDataUpdate.GeoEntryValue;
import com.ballastlane.pokemon.localdata.LocalDataUpdate.LocalizedNameValue;
import java.nio.charset.StandardCharsets;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LocalDataRequestParserTest {

	private final LocalDataRequestParser parser = new LocalDataRequestParser();

	@Test
	void normalizesAValidReplacement() {
		LocalDataUpdate update = parser.parseReplacement(json("""
				{
				  "localizedNames": [ { "locale": " ja-jp ", "name": " ピカチュウ " } ],
				  "geoMetadata": [
				    { "kind": "Region", "value": "kanto", "note": "  " },
				    { "kind": "region", "value": "kanto", "note": "line one\\nline two" }
				  ],
				  "tags": [ " Mascot ", "starter_2" ]
				}"""));

		assertThat(update.localizedNames()).containsExactly(new LocalizedNameValue("ja-JP", "ピカチュウ"));
		// Geographical entries may repeat; a blank note is no note.
		assertThat(update.geoMetadata()).containsExactly(new GeoEntryValue(GeoKind.REGION, "kanto", null),
				new GeoEntryValue(GeoKind.REGION, "kanto", "line one\nline two"));
		assertThat(update.tags()).containsExactly("mascot", "starter_2");
	}

	@Test
	void replacementNeedsEveryKindAndAllowsEmptyLists() {
		assertThat(parser.parseReplacement(json("""
				{ "localizedNames": [], "geoMetadata": [], "tags": [] }""")))
				.isEqualTo(new LocalDataUpdate(java.util.List.of(), java.util.List.of(), java.util.List.of()));

		assertProblems(() -> parser.parseReplacement(json("""
				{ "localizedNames": null, "tags": [] }""")),
				"localizedNames", "geoMetadata");
	}

	@Test
	void patchKeepsMissingKindsAndClearsNullOnes() {
		LocalDataUpdate update = parser.parsePatch(json("""
				{ "tags": null, "geoMetadata": [ { "kind": "habitat", "value": "forest" } ] }"""));

		assertThat(update.localizedNames()).isNull();
		assertThat(update.geoMetadata()).containsExactly(new GeoEntryValue(GeoKind.HABITAT, "forest", null));
		assertThat(update.tags()).isEmpty();
		assertThat(parser.parsePatch(json("{}"))).isEqualTo(new LocalDataUpdate(null, null, null));
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"''                         | is required",
			"'{'                        | is not valid JSON",
			"'{} {}'                    | is not valid JSON",
			"'[]'                       | must be a JSON object",
			"'\"tags\"'                 | must be a JSON object",
			"'{\"tags\":[],\"tags\":[]}' | has a duplicate field",
			"'{\"tags\":[[[[[[[[[[[]]]]]]]]]]]}' | is nested too deeply" })
	void rejectsMalformedBodies(String body, String message) {
		assertThatThrownBy(() -> parser.parsePatch(json(body)))
				.isInstanceOfSatisfying(InvalidLocalDataException.class, ex -> assertThat(ex.getProblems())
						.containsExactly(new FieldProblem("body", message)));
	}

	@Test
	void rejectsReadOnlyAndUnknownFields() {
		assertThatThrownBy(() -> parser.parsePatch(json("""
				{ "name": "raichu", "stats": [], "colour": "yellow",
				  "localizedNames": [ { "locale": "en", "name": "Pika", "extra": 1 } ] }""")))
				.isInstanceOfSatisfying(InvalidLocalDataException.class, ex -> assertThat(ex.getProblems())
						.containsExactlyInAnyOrder(
								new FieldProblem("name", "is read only"),
								new FieldProblem("stats", "is read only"),
								new FieldProblem("colour", "is not a known field"),
								new FieldProblem("localizedNames[0].extra", "is not a known field")));
	}

	@Test
	void reportsEveryInvalidFieldWithItsPath() {
		String longName = "x".repeat(101);
		assertThatThrownBy(() -> parser.parseReplacement(json("""
				{
				  "localizedNames": [
				    { "locale": "not a locale!", "name": "Pika" },
				    { "locale": "en", "name": "   " },
				    { "locale": "fr", "name": "%s" },
				    { "locale": "de", "name": 42 },
				    null
				  ],
				  "geoMetadata": [ { "kind": "planet", "value": "kanto" }, { "kind": "area" } ],
				  "tags": [ "has space", "", "ok", 7, "bad\\u0007bell" ]
				}""".formatted(longName))))
				.isInstanceOfSatisfying(InvalidLocalDataException.class, ex -> assertThat(ex.getProblems())
						.containsExactly(
								new FieldProblem("localizedNames[0].locale",
										"must be a well-formed language tag, such as 'en' or 'ja-JP'"),
								new FieldProblem("localizedNames[1].name", "must not be blank"),
								new FieldProblem("localizedNames[2].name", "must be at most 100 characters"),
								new FieldProblem("localizedNames[3].name", "must be a string"),
								new FieldProblem("localizedNames[4]", "must be an object"),
								new FieldProblem("geoMetadata[0].kind", "must be one of: region, area, habitat"),
								new FieldProblem("geoMetadata[1].value", "is required"),
								new FieldProblem("tags[0]", "may only contain letters, digits, '-' and '_'"),
								new FieldProblem("tags[1]", "must not be blank"),
								new FieldProblem("tags[3]", "must be a string"),
								new FieldProblem("tags[4]", "must not contain control characters")));
	}

	@Test
	void rejectsDuplicatesAfterNormalisation() {
		assertThatThrownBy(() -> parser.parsePatch(json("""
				{ "localizedNames": [ { "locale": "en-us", "name": "A" }, { "locale": "en-US", "name": "B" } ],
				  "tags": [ "Mascot", "mascot " ] }""")))
				.isInstanceOfSatisfying(InvalidLocalDataException.class, ex -> assertThat(ex.getProblems())
						.containsExactly(
								new FieldProblem("localizedNames", "has more than one name for locale en-US"),
								new FieldProblem("tags", "has the tag 'mascot' more than once")));
	}

	@Test
	void enforcesListSizesAndTypes() {
		String tags = "\"t\",".repeat(31);
		assertProblems(() -> parser.parsePatch(json("""
				{ "tags": [ %s ], "geoMetadata": { "kind": "region" }, "localizedNames": "en" }"""
				.formatted(tags.substring(0, tags.length() - 1)))),
				"localizedNames", "geoMetadata", "tags");
	}

	private static void assertProblems(ThrowingCallable call, String... fields) {
		assertThatThrownBy(call).isInstanceOfSatisfying(InvalidLocalDataException.class,
				ex -> assertThat(ex.getProblems()).extracting(FieldProblem::field).containsExactly(fields));
	}

	private static byte[] json(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

}
