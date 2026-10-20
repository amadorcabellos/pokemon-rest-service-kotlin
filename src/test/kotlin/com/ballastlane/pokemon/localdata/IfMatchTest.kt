package com.ballastlane.pokemon.localdata

import com.ballastlane.pokemon.exception.InvalidIfMatchException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class IfMatchTest {

    @Test
    fun matchesStrongTagsOnly() {
        IfMatch.parse("\"3\"").matches(3) shouldBe true
        IfMatch.parse("\"3\"").matches(4) shouldBe false
        IfMatch.parse(" \"1\" , \"4\" ").matches(4) shouldBe true
        IfMatch.parse("W/\"3\"").matches(3) shouldBe false
        IfMatch.parse("*").matches(42) shouldBe true
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "  ", "3", "\"3", "\"3\" \"4\"", "W/3", "\"3\";x"])
    fun rejectsMalformedHeaders(header: String) {
        shouldThrow<InvalidIfMatchException> { IfMatch.parse(header) }
    }

    @Test
    fun formatsEntityTags() {
        IfMatch.entityTag(7) shouldBe "\"7\""
    }
}
