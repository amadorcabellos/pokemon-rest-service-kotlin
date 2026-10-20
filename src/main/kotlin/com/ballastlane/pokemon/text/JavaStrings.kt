package com.ballastlane.pokemon.text

/*
 * Java's whitespace rules for user input. Kotlin's trim() and isBlank() also treat no-break spaces as whitespace,
 * so using them would change which usernames, tags and names are accepted.
 */

/** Same as Java's `String.strip()`: removes leading and trailing `Character.isWhitespace` characters. */
fun String.javaStrip(): String = trim { Character.isWhitespace(it) }

/** Same as Java's `String.isBlank()`: empty, or only `Character.isWhitespace` characters. */
fun String.isJavaBlank(): Boolean = all { Character.isWhitespace(it) }

/** Same as Java's `String.trim()`: removes leading and trailing characters up to and including the space. */
fun String.javaTrim(): String = trim { it <= ' ' }
