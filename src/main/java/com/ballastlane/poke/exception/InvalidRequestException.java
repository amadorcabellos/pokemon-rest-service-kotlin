package com.ballastlane.poke.exception;

import com.ballastlane.poke.localdata.FieldProblem;
import java.util.List;

/**
 * A request body breaks one or more rules. Lists every problem found.
 */
public class InvalidRequestException extends RuntimeException {

	private final List<FieldProblem> problems;

	public InvalidRequestException(List<FieldProblem> problems) {
		super("The request is invalid: %d problem(s) found".formatted(problems.size()));
		this.problems = List.copyOf(problems);
	}

	public List<FieldProblem> getProblems() {
		return problems;
	}

}
