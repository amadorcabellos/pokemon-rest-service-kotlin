package com.ballastlane.pokemon.web.dto;

/**
 * A pokemon that a full synchronization could not synchronize, and why.
 */
public record SyncFailure(String name, String reason) {
}
