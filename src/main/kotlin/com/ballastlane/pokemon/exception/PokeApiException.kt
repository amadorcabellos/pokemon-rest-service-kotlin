package com.ballastlane.pokemon.exception

/**
 * Raised when PokeAPI is unreachable or answers with an unexpected error.
 */
class PokeApiException(message: String, cause: Throwable?) : RuntimeException(message, cause)
