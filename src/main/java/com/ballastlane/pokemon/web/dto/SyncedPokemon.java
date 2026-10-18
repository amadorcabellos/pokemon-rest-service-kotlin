package com.ballastlane.pokemon.web.dto;

import com.ballastlane.pokemon.sync.SyncOutcome;
import java.time.Instant;

/**
 * Result of synchronizing one pokemon into the local store.
 */
public record SyncedPokemon(Integer id, String name, SyncOutcome outcome, Instant lastSyncedAt) {
}
