package com.ballastlane.pokemon.exception

class PokemonNotFoundException(nameOrId: String) : RuntimeException("Pokemon '$nameOrId' was not found")
