package com.ballastlane.pokemon.exception

class InvalidIfMatchException : RuntimeException("'If-Match' must be '*' or a list of entity tags such as \"3\"")
