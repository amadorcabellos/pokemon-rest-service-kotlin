package com.ballastlane.pokemon.exception

class UsernameTakenException(username: String) : RuntimeException("The username '$username' is already taken")
