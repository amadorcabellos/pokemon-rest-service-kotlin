package com.ballastlane.pokemon.domain

/**
 * What a user may do. `ADMIN` implies `USER` (see `SecurityConfig`).
 */
enum class Role {

    /** Given on registration: may update local pokemon data. */
    USER,

    /** Also may run synchronizations. Only granted to the configured initial administrator. */
    ADMIN,
}
