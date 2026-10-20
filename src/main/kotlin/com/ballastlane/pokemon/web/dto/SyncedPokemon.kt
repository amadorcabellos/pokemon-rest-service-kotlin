package com.ballastlane.pokemon.web.dto

import com.ballastlane.pokemon.sync.SyncOutcome
import java.time.Instant

/**
 * Result of synchronizing one pokemon into the local store.
 */
@JvmRecord
data class SyncedPokemon(val id: Int, val name: String, val outcome: SyncOutcome, val lastSyncedAt: Instant)
