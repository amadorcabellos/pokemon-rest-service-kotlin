package com.ballastlane.pokemon.localdata

/**
 * One invalid part of a request.
 *
 * @property field where the problem is, e.g. `tags[2]` or `geoMetadata[0].kind`; `body` for the request body as a
 * whole
 * @property message what is wrong with it; never echoes the submitted value
 */
data class FieldProblem(val field: String, val message: String)
