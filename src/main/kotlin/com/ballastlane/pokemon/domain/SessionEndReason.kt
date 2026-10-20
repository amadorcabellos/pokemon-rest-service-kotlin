package com.ballastlane.pokemon.domain

/**
 * Why an [AuthSession] ended before it expired.
 */
enum class SessionEndReason {

    /** The client signed out. */
    LOGOUT,

    /** A refresh token that had already been used was presented again: possibly stolen. */
    REUSE,
}
