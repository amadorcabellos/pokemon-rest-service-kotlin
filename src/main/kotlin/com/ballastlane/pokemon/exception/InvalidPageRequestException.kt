package com.ballastlane.pokemon.exception

/**
 * Raised when the requested page number or page size is outside the allowed range.
 */
class InvalidPageRequestException(message: String) : RuntimeException(message)
