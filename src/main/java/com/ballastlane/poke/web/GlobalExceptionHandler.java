package com.ballastlane.poke.web;

import com.ballastlane.poke.exception.InvalidPageRequestException;
import com.ballastlane.poke.exception.PokeApiException;
import com.ballastlane.poke.exception.PokemonNotFoundException;
import com.ballastlane.poke.exception.SyncAlreadyRunningException;
import com.ballastlane.poke.exception.SyncRunNotFoundException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps domain exceptions to RFC 9457 problem details.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(PokemonNotFoundException.class)
	ProblemDetail handleNotFound(PokemonNotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "Pokemon not found", ex.getMessage());
	}

	@ExceptionHandler(PokeApiException.class)
	ProblemDetail handleUpstreamFailure(PokeApiException ex) {
		log.error("PokeAPI call failed", ex);
		return problem(HttpStatus.BAD_GATEWAY, "Upstream service error", "PokeAPI is currently unavailable");
	}

	@ExceptionHandler(CallNotPermittedException.class)
	ProblemDetail handleCircuitOpen(CallNotPermittedException ex) {
		log.warn("Rejected PokeAPI call: {}", ex.getMessage());
		return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable",
				"PokeAPI is failing; requests are paused for a short time. Please retry later");
	}

	@ExceptionHandler(RequestNotPermitted.class)
	ProblemDetail handleRateLimited(RequestNotPermitted ex) {
		log.warn("Rejected PokeAPI call: {}", ex.getMessage());
		return problem(HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
				"The PokeAPI request quota is exhausted. Please retry later");
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	ProblemDetail handleValidation(HandlerMethodValidationException ex) {
		String detail = ex.getAllErrors().stream()
				.map(error -> error.getDefaultMessage())
				.findFirst()
				.orElse("Invalid request");
		return problem(HttpStatus.BAD_REQUEST, "Invalid request", detail);
	}

	@ExceptionHandler(InvalidPageRequestException.class)
	ProblemDetail handleInvalidPage(InvalidPageRequestException ex) {
		return problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage());
	}

	@ExceptionHandler(SyncAlreadyRunningException.class)
	ProblemDetail handleSyncAlreadyRunning(SyncAlreadyRunningException ex) {
		Long runId = ex.getRunningRunId();
		if (runId == null) {
			return problem(HttpStatus.CONFLICT, "Synchronization already running", ex.getMessage());
		}
		ProblemDetail problem = problem(HttpStatus.CONFLICT, "Synchronization already running",
				"%s; follow it at %s".formatted(ex.getMessage(), SyncController.runUri(runId)));
		problem.setProperty("runId", runId);
		return problem;
	}

	@ExceptionHandler(SyncRunNotFoundException.class)
	ProblemDetail handleSyncRunNotFound(SyncRunNotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "Synchronization run not found", ex.getMessage());
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		return problem(HttpStatus.BAD_REQUEST, "Invalid request", "'%s' must be a number".formatted(ex.getName()));
	}

	private static ProblemDetail problem(HttpStatus status, String title, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(title);
		return problem;
	}

}
