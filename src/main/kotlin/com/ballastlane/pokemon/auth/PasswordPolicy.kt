package com.ballastlane.pokemon.auth

import com.ballastlane.pokemon.localdata.FieldProblem
import com.ballastlane.pokemon.text.isJavaBlank
import com.ballastlane.pokemon.text.javaStrip
import java.util.Locale

/**
 * Rules for usernames and passwords.
 * - Username: 3 to 50 characters of letters, digits, `.`, `_` and `-`; trimmed and compared ignoring case.
 * - Password: 12 to 128 characters, any characters (including spaces and Unicode), no composition rules, and not
 *   the username.
 */
object PasswordPolicy {

    const val MIN_PASSWORD_LENGTH = 12

    const val MAX_PASSWORD_LENGTH = 128

    private val USERNAME = Regex("[a-zA-Z0-9._-]{3,50}")

    /**
     * Trimmed and lowercase, as usernames are stored and compared.
     */
    fun normalizeUsername(username: String): String = username.javaStrip().lowercase(Locale.ROOT)

    /**
     * @return every problem with the username and password; empty if both are acceptable
     */
    fun check(username: String?, password: String?): List<FieldProblem> {
        val problems = mutableListOf<FieldProblem>()
        val validUsername = when {
            username == null || username.isJavaBlank() -> {
                problems.add(FieldProblem("username", "is required"))
                null
            }
            !USERNAME.matches(username.javaStrip()) -> {
                problems.add(FieldProblem("username", "must be 3 to 50 characters of letters, digits, '.', '_' and '-'"))
                null
            }
            else -> username
        }

        if (password == null || password.isJavaBlank()) {
            problems.add(FieldProblem("password", "is required"))
        } else {
            val length = password.codePointCount(0, password.length)
            if (length < MIN_PASSWORD_LENGTH || length > MAX_PASSWORD_LENGTH) {
                problems.add(FieldProblem("password", "must be $MIN_PASSWORD_LENGTH to $MAX_PASSWORD_LENGTH characters"))
            } else if (validUsername != null && password.javaStrip().equals(validUsername.javaStrip(), ignoreCase = true)) {
                problems.add(FieldProblem("password", "must not be the username"))
            }
        }
        return problems
    }
}
