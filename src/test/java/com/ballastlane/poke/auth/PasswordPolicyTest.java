package com.ballastlane.poke.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.ballastlane.poke.localdata.FieldProblem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyTest {

	private static final String GOOD_PASSWORD = "correct horse battery";

	@Test
	void acceptsValidUsernamesAndPasswords() {
		assertThat(PasswordPolicy.check(" Ash.Ketchum_1-x ", GOOD_PASSWORD)).isEmpty();
		// Any characters count, including spaces and Unicode, measured in characters rather than bytes.
		assertThat(PasswordPolicy.check("ash", "ピカチュウピカチュウピカチュ")).isEmpty();
		assertThat(PasswordPolicy.check("ash", "😀".repeat(128))).isEmpty();
	}

	@Test
	void normalizesUsernames() {
		assertThat(PasswordPolicy.normalizeUsername("  Ash.KETCHUM ")).isEqualTo("ash.ketchum");
	}

	@ParameterizedTest
	@ValueSource(strings = { "ab", "has space", "ash!", "émile" })
	void rejectsInvalidUsernames(String username) {
		assertThat(PasswordPolicy.check(username, GOOD_PASSWORD)).extracting(FieldProblem::field)
				.containsExactly("username");
	}

	@Test
	void rejectsUsernamesThatAreTooLong() {
		assertThat(PasswordPolicy.check("a".repeat(51), GOOD_PASSWORD)).extracting(FieldProblem::field)
				.containsExactly("username");
	}

	@Test
	void rejectsPasswordsOfTheWrongLengthOrEqualToTheUsername() {
		assertThat(PasswordPolicy.check("ash", "short")).containsExactly(
				new FieldProblem("password", "must be 12 to 128 characters"));
		assertThat(PasswordPolicy.check("ash", "x".repeat(129))).hasSize(1);
		assertThat(PasswordPolicy.check("ash.ketchum1", "ASH.KETCHUM1")).containsExactly(
				new FieldProblem("password", "must not be the username"));
	}

	@Test
	void listsEveryProblem() {
		assertThat(PasswordPolicy.check(null, "   ")).containsExactly(
				new FieldProblem("username", "is required"),
				new FieldProblem("password", "is required"));
	}

}
