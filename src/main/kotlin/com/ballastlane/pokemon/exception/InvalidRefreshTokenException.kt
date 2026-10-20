package com.ballastlane.pokemon.exception

/**
 * The refresh token is unknown, expired, already used, or its session has ended: one message for every case.
 */
class InvalidRefreshTokenException : RuntimeException("The refresh token is invalid or has expired; sign in again")
