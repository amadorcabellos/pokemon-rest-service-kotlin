package com.ballastlane.pokemon.exception

import com.ballastlane.pokemon.localdata.FieldProblem

/**
 * A request body breaks one or more rules. Lists every problem found.
 */
class InvalidRequestException(problems: List<FieldProblem>) :
    RuntimeException("The request is invalid: ${problems.size} problem(s) found") {

    val problems: List<FieldProblem> = problems.toList()
}
