package com.ballastlane.pokemon.web.dto

/**
 * A base stat, e.g. `hp` = 35.
 */
@JvmRecord
data class Stat(val name: String?, val value: Int)
