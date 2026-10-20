package com.ballastlane.pokemon.exception

import java.time.Duration

class TooManyLoginAttemptsException(val retryAfter: Duration) :
    RuntimeException("Too many failed sign-in attempts; try again later")
