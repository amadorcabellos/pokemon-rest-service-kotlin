package com.ballastlane.poke.web.dto;

import com.ballastlane.poke.sync.SyncOutcome;
import java.time.Instant;

/**
 * Result of synchronizing one pokemon into the local store.
 */
public record SyncedPokemon(Integer id, String name, SyncOutcome outcome, Instant lastSyncedAt) {
}
