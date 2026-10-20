package com.ballastlane.pokemon.auth

import com.ballastlane.pokemon.localdata.FieldProblem
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PasswordPolicyTest {

    @Test
    fun acceptsValidUsernamesAndPasswords() {
        PasswordPolicy.check(" Ash.Ketchum_1-x ", GOOD_PASSWORD).shouldBeEmpty()
        // Any characters count, including spaces and Unicode, measured in characters rather than bytes.
        PasswordPolicy.check("ash", "ピカチュウピカチュウピカチュ").shouldBeEmpty()
        PasswordPolicy.check("ash", "😀".repeat(128)).shouldBeEmpty()
    }

    @Test
    fun normalizesUsernames() {
        PasswordPolicy.normalizeUsername("  Ash.KETCHUM ") shouldBe "ash.ketchum"
    }

    @ParameterizedTest
    @ValueSource(strings = ["ab", "has space", "ash!", "émile"])
    fun rejectsInvalidUsernames(username: String) {
        PasswordPolicy.check(username, GOOD_PASSWORD).map { it.field }.shouldContainExactly("username")
    }

    @Test
    fun rejectsUsernamesThatAreTooLong() {
        PasswordPolicy.check("a".repeat(51), GOOD_PASSWORD).map { it.field }.shouldContainExactly("username")
    }

    @Test
    fun rejectsPasswordsOfTheWrongLengthOrEqualToTheUsername() {
        PasswordPolicy.check("ash", "short").shouldContainExactly(FieldProblem("password", "must be 12 to 128 characters"))
        PasswordPolicy.check("ash", "x".repeat(129)) shouldHaveSize 1
        PasswordPolicy.check("ash.ketchum1", "ASH.KETCHUM1")
            .shouldContainExactly(FieldProblem("password", "must not be the username"))
    }

    @Test
    fun listsEveryProblem() {
        PasswordPolicy.check(null, "   ").shouldContainExactly(
            FieldProblem("username", "is required"),
            FieldProblem("password", "is required"),
        )
    }

    private companion object {
        const val GOOD_PASSWORD = "correct horse battery"
    }
}
