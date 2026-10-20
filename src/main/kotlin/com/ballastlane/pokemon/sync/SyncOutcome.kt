package com.ballastlane.pokemon.sync

enum class SyncOutcome {

    /** The pokemon was not stored locally yet. */
    CREATED,

    /** The pokemon was already stored; its upstream data was refreshed. */
    UPDATED,
}
