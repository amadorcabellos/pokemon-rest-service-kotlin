package com.ballastlane.pokemon.auth;

import com.ballastlane.pokemon.localdata.FieldProblem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Rules for usernames and passwords.
 * <ul>
 * <li>Username: 3 to 50 characters of letters, digits, {@code .}, {@code _} and {@code -}; trimmed and compared
 * ignoring case.</li>
 * <li>Password: 12 to 128 characters, any characters (including spaces and Unicode), no composition rules, and
 * not the username.</li>
 * </ul>
 */
public final class PasswordPolicy {

	public static final int MIN_PASSWORD_LENGTH = 12;

	public static final int MAX_PASSWORD_LENGTH = 128;

	private static final Pattern USERNAME = Pattern.compile("[a-zA-Z0-9._-]{3,50}");

	private PasswordPolicy() {
	}

	/**
	 * Trimmed and lowercase, as usernames are stored and compared.
	 */
	public static String normalizeUsername(String username) {
		return username.strip().toLowerCase(Locale.ROOT);
	}

	/**
	 * @return every problem with the username and password; empty if both are acceptable
	 */
	public static List<FieldProblem> check(String username, String password) {
		List<FieldProblem> problems = new ArrayList<>();
		boolean usernameValid = false;
		if (username == null || username.isBlank()) {
			problems.add(new FieldProblem("username", "is required"));
		}
		else if (!USERNAME.matcher(username.strip()).matches()) {
			problems.add(new FieldProblem("username",
					"must be 3 to 50 characters of letters, digits, '.', '_' and '-'"));
		}
		else {
			usernameValid = true;
		}

		if (password == null || password.isBlank()) {
			problems.add(new FieldProblem("password", "is required"));
		}
		else {
			int length = password.codePointCount(0, password.length());
			if (length < MIN_PASSWORD_LENGTH || length > MAX_PASSWORD_LENGTH) {
				problems.add(new FieldProblem("password", "must be %d to %d characters"
						.formatted(MIN_PASSWORD_LENGTH, MAX_PASSWORD_LENGTH)));
			}
			else if (usernameValid && password.strip().equalsIgnoreCase(username.strip())) {
				problems.add(new FieldProblem("password", "must not be the username"));
			}
		}
		return problems;
	}

}
