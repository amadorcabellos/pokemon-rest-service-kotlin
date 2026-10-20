package com.ballastlane.pokemon.localdata

import com.ballastlane.pokemon.exception.InvalidIfMatchException
import com.ballastlane.pokemon.text.isJavaBlank
import com.ballastlane.pokemon.text.javaStrip
import java.util.regex.Pattern

/**
 * A parsed `If-Match` header (RFC 9110): `*`, or a comma-separated list of entity tags.
 *
 * Matching uses strong comparison, so a weak tag such as `W/"3"` never matches.
 */
class IfMatch private constructor(private val any: Boolean, private val strongTags: List<String>) {

    fun matches(version: Long): Boolean = any || version.toString() in strongTags

    companion object {

        private val ENTITY_TAG = Pattern.compile("""\s*(W/)?"([\x21\x23-\x7E]*)"\s*(,|$)""")

        /**
         * @throws InvalidIfMatchException if the header is not valid
         */
        @JvmStatic
        fun parse(header: String): IfMatch {
            if (header.isJavaBlank()) {
                throw InvalidIfMatchException()
            }
            if (header.javaStrip() == "*") {
                return IfMatch(true, emptyList())
            }
            val strongTags = mutableListOf<String>()
            val matcher = ENTITY_TAG.matcher(header)
            var position = 0
            while (position < header.length) {
                if (!matcher.find(position) || matcher.start() != position || matcher.end() == position) {
                    throw InvalidIfMatchException()
                }
                if (matcher.group(1) == null) {
                    strongTags.add(matcher.group(2))
                }
                position = matcher.end()
            }
            return IfMatch(false, strongTags.toList())
        }

        /**
         * The entity tag of a version of the proprietary data, as sent in the `ETag` header.
         */
        @JvmStatic
        fun entityTag(version: Long): String = "\"$version\""
    }
}
