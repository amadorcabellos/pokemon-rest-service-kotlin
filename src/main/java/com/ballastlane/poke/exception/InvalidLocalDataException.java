package com.ballastlane.poke.exception;

import com.ballastlane.poke.localdata.FieldProblem;
import java.util.List;

/**
 * A local data update request is malformed or breaks a validation rule. Lists every problem found.
 */
public class InvalidLocalDataException extends RuntimeException {

	private final List<FieldProblem> problems;

	public InvalidLocalDataException(List<FieldProblem> problems) {
		super("The request is invalid: %d problem(s) found".formatted(problems.size()));
		this.problems = List.copyOf(problems);
	}

	public List<FieldProblem> getProblems() {
		return problems;
	}

}
