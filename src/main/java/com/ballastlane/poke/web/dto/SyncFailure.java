package com.ballastlane.poke.web.dto;

/**
 * A pokemon that a full synchronization could not synchronize, and why.
 */
public record SyncFailure(String name, String reason) {
}
