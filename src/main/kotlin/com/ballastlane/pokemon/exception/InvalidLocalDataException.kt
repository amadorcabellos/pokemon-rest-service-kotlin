package com.ballastlane.pokemon.exception

import com.ballastlane.pokemon.localdata.FieldProblem

/**
 * A local data update request is malformed or breaks a validation rule. Lists every problem found.
 */
class InvalidLocalDataException(problems: List<FieldProblem>) :
    RuntimeException("The request is invalid: ${problems.size} problem(s) found") {

    val problems: List<FieldProblem> = problems.toList()
}
