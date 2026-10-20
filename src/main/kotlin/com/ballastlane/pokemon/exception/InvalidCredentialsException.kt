package com.ballastlane.pokemon.exception

/**
 * Wrong username or wrong password: deliberately the same exception, so the response never tells them apart.
 */
class InvalidCredentialsException : RuntimeException("The username or password is incorrect")
