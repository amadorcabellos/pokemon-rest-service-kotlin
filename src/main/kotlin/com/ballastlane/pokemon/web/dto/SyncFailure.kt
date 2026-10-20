package com.ballastlane.pokemon.web.dto

/**
 * A pokemon that a full synchronization could not synchronize, and why.
 */
@JvmRecord
data class SyncFailure(val name: String, val reason: String)
