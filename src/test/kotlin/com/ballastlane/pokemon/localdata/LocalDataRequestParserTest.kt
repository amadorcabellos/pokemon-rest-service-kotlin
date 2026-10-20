package com.ballastlane.pokemon.localdata

import com.ballastlane.pokemon.domain.GeoKind
import com.ballastlane.pokemon.exception.InvalidLocalDataException
import com.ballastlane.pokemon.localdata.LocalDataUpdate.GeoEntryValue
import com.ballastlane.pokemon.localdata.LocalDataUpdate.LocalizedNameValue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class LocalDataRequestParserTest {

    private val parser = LocalDataRequestParser()

    @Test
    fun normalizesAValidReplacement() {
        val update = parser.parseReplacement(
            json(
                """
                {
                  "localizedNames": [ { "locale": " ja-jp ", "name": " ピカチュウ " } ],
                  "geoMetadata": [
                    { "kind": "Region", "value": "kanto", "note": "  " },
                    { "kind": "region", "value": "kanto", "note": "line one\nline two" }
                  ],
                  "tags": [ " Mascot ", "starter_2" ]
                }
                """.trimIndent(),
            ),
        )

        update.localizedNames.shouldContainExactlyNullable(LocalizedNameValue("ja-JP", "ピカチュウ"))
        // Geographical entries may repeat; a blank note is no note.
        update.geoMetadata.shouldContainExactlyNullable(
            GeoEntryValue(GeoKind.REGION, "kanto", null),
            GeoEntryValue(GeoKind.REGION, "kanto", "line one\nline two"),
        )
        update.tags.shouldContainExactlyNullable("mascot", "starter_2")
    }

    @Test
    fun replacementNeedsEveryKindAndAllowsEmptyLists() {
        parser.parseReplacement(json("""{ "localizedNames": [], "geoMetadata": [], "tags": [] }""")) shouldBe
            LocalDataUpdate(listOf(), listOf(), listOf())

        assertProblems({ parser.parseReplacement(json("""{ "localizedNames": null, "tags": [] }""")) },
            "localizedNames", "geoMetadata")
    }

    @Test
    fun patchKeepsMissingKindsAndClearsNullOnes() {
        val update = parser.parsePatch(json("""{ "tags": null, "geoMetadata": [ { "kind": "habitat", "value": "forest" } ] }"""))

        update.localizedNames.shouldBeNull()
        update.geoMetadata.shouldContainExactlyNullable(GeoEntryValue(GeoKind.HABITAT, "forest", null))
        update.tags.orEmpty().shouldBeEmpty()
        update.tags shouldBe emptyList()
        parser.parsePatch(json("{}")) shouldBe LocalDataUpdate(null, null, null)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "''                         | is required",
            "'{'                        | is not valid JSON",
            "'{} {}'                    | is not valid JSON",
            "'[]'                       | must be a JSON object",
            "'\"tags\"'                 | must be a JSON object",
            "'{\"tags\":[],\"tags\":[]}' | has a duplicate field",
            "'{\"tags\":[[[[[[[[[[[]]]]]]]]]]]}' | is nested too deeply",
        ],
    )
    fun rejectsMalformedBodies(body: String, message: String) {
        shouldThrow<InvalidLocalDataException> { parser.parsePatch(json(body)) }
            .problems.shouldContainExactly(FieldProblem("body", message))
    }

    @Test
    fun rejectsReadOnlyAndUnknownFields() {
        shouldThrow<InvalidLocalDataException> {
            parser.parsePatch(
                json(
                    """
                    { "name": "raichu", "stats": [], "colour": "yellow",
                      "localizedNames": [ { "locale": "en", "name": "Pika", "extra": 1 } ] }
                    """.trimIndent(),
                ),
            )
        }.problems.shouldContainExactlyInAnyOrder(
            FieldProblem("name", "is read only"),
            FieldProblem("stats", "is read only"),
            FieldProblem("colour", "is not a known field"),
            FieldProblem("localizedNames[0].extra", "is not a known field"),
        )
    }

    @Test
    fun reportsEveryInvalidFieldWithItsPath() {
        val longName = "x".repeat(101)
        shouldThrow<InvalidLocalDataException> {
            parser.parseReplacement(
                json(
                    """
                    {
                      "localizedNames": [
                        { "locale": "not a locale!", "name": "Pika" },
                        { "locale": "en", "name": "   " },
                        { "locale": "fr", "name": "$longName" },
                        { "locale": "de", "name": 42 },
                        null
                      ],
                      "geoMetadata": [ { "kind": "planet", "value": "kanto" }, { "kind": "area" } ],
                      "tags": [ "has space", "", "ok", 7, "bad\u0007bell" ]
                    }
                    """.trimIndent(),
                ),
            )
        }.problems.shouldContainExactly(
            FieldProblem("localizedNames[0].locale", "must be a well-formed language tag, such as 'en' or 'ja-JP'"),
            FieldProblem("localizedNames[1].name", "must not be blank"),
            FieldProblem("localizedNames[2].name", "must be at most 100 characters"),
            FieldProblem("localizedNames[3].name", "must be a string"),
            FieldProblem("localizedNames[4]", "must be an object"),
            FieldProblem("geoMetadata[0].kind", "must be one of: region, area, habitat"),
            FieldProblem("geoMetadata[1].value", "is required"),
            FieldProblem("tags[0]", "may only contain letters, digits, '-' and '_'"),
            FieldProblem("tags[1]", "must not be blank"),
            FieldProblem("tags[3]", "must be a string"),
            FieldProblem("tags[4]", "must not contain control characters"),
        )
    }

    @Test
    fun rejectsDuplicatesAfterNormalisation() {
        shouldThrow<InvalidLocalDataException> {
            parser.parsePatch(
                json(
                    """
                    { "localizedNames": [ { "locale": "en-us", "name": "A" }, { "locale": "en-US", "name": "B" } ],
                      "tags": [ "Mascot", "mascot " ] }
                    """.trimIndent(),
                ),
            )
        }.problems.shouldContainExactly(
            FieldProblem("localizedNames", "has more than one name for locale en-US"),
            FieldProblem("tags", "has the tag 'mascot' more than once"),
        )
    }

    @Test
    fun enforcesListSizesAndTypes() {
        val tags = List(31) { "\"t\"" }.joinToString(",")
        assertProblems(
            { parser.parsePatch(json("""{ "tags": [ $tags ], "geoMetadata": { "kind": "region" }, "localizedNames": "en" }""")) },
            "localizedNames", "geoMetadata", "tags",
        )
    }

    private fun assertProblems(call: () -> Unit, vararg fields: String) {
        shouldThrow<InvalidLocalDataException>(call).problems.map { it.field }.shouldContainExactly(*fields)
    }

    private fun <T> List<T>?.shouldContainExactlyNullable(vararg expected: T) {
        this.orEmpty().shouldContainExactly(*expected)
    }

    private fun json(value: String): ByteArray = value.toByteArray()
}
