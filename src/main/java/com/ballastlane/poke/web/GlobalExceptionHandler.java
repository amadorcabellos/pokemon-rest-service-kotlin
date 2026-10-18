package com.ballastlane.poke.web;

import com.ballastlane.poke.exception.InvalidCredentialsException;
import com.ballastlane.poke.exception.InvalidIfMatchException;
import com.ballastlane.poke.exception.InvalidLocalDataException;
import com.ballastlane.poke.exception.InvalidPageRequestException;
import com.ballastlane.poke.exception.InvalidRefreshTokenException;
import com.ballastlane.poke.exception.InvalidRequestException;
import com.ballastlane.poke.exception.LocalDataVersionMismatchException;
import com.ballastlane.poke.exception.PokeApiException;
import com.ballastlane.poke.exception.PokemonNotFoundException;
import com.ballastlane.poke.exception.PokemonNotStoredLocallyException;
import com.ballastlane.poke.exception.SyncAlreadyRunningException;
import com.ballastlane.poke.exception.SyncRunNotFoundException;
import com.ballastlane.poke.exception.TooManyLoginAttemptsException;
import com.ballastlane.poke.exception.UsernameTakenException;
import com.ballastlane.poke.localdata.FieldProblem;
import com.ballastlane.poke.web.LocalDataRequestSizeFilter.RequestBodyTooLargeException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
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
		List<FieldProblem> errors = ex.getParameterValidationResults().stream()
				.flatMap(result -> result.getResolvableErrors().stream()
						.map(error -> new FieldProblem(result.getMethodParameter().getParameterName(),
								error.getDefaultMessage())))
				.toList();
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request", detail);
		problem.setProperty("errors", errors);
		return problem;
	}

	@ExceptionHandler(InvalidLocalDataException.class)
	ProblemDetail handleInvalidLocalData(InvalidLocalDataException ex) {
		log.debug("Rejected local data update: {}", ex.getProblems());
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request",
				"The request body is invalid; see 'errors' for each problem");
		problem.setProperty("errors", ex.getProblems());
		return problem;
	}

	@ExceptionHandler(InvalidRequestException.class)
	ProblemDetail handleInvalidRequest(InvalidRequestException ex) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Invalid request",
				"The request body is invalid; see 'errors' for each problem");
		problem.setProperty("errors", ex.getProblems());
		return problem;
	}

	@ExceptionHandler(UsernameTakenException.class)
	ProblemDetail handleUsernameTaken(UsernameTakenException ex) {
		return problem(HttpStatus.CONFLICT, "Username already taken", ex.getMessage());
	}

	@ExceptionHandler(InvalidCredentialsException.class)
	ProblemDetail handleInvalidCredentials(InvalidCredentialsException ex) {
		return problem(HttpStatus.UNAUTHORIZED, "Invalid username or password", ex.getMessage());
	}

	@ExceptionHandler(InvalidRefreshTokenException.class)
	ProblemDetail handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
		return problem(HttpStatus.UNAUTHORIZED, "Invalid refresh token", ex.getMessage());
	}

	@ExceptionHandler(TooManyLoginAttemptsException.class)
	ResponseEntity<ProblemDetail> handleTooManyLoginAttempts(TooManyLoginAttemptsException ex) {
		long seconds = Math.max(1, (ex.getRetryAfter().toMillis() + 999) / 1000);
		ProblemDetail problem = problem(HttpStatus.TOO_MANY_REQUESTS, "Too many sign-in attempts",
				"%s (in %d seconds)".formatted(ex.getMessage(), seconds));
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
				.body(problem);
	}

	@ExceptionHandler(InvalidIfMatchException.class)
	ProblemDetail handleInvalidIfMatch(InvalidIfMatchException ex) {
		return problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage());
	}

	@ExceptionHandler(PokemonNotStoredLocallyException.class)
	ProblemDetail handleNotStoredLocally(PokemonNotStoredLocallyException ex) {
		return problem(HttpStatus.NOT_FOUND, "Pokemon not found", ex.getMessage());
	}

	@ExceptionHandler(LocalDataVersionMismatchException.class)
	ProblemDetail handleVersionMismatch(LocalDataVersionMismatchException ex) {
		ProblemDetail problem = problem(HttpStatus.PRECONDITION_FAILED, "Local data has changed", ex.getMessage());
		problem.setProperty("currentVersion", ex.getCurrentVersion());
		return problem;
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
		if (ex.getCause() instanceof RequestBodyTooLargeException tooLarge) {
			return problem(HttpStatus.CONTENT_TOO_LARGE, "Request body too large", tooLarge.getMessage());
		}
		// The converter's message can quote the input or name internal classes, so it is never passed on.
		return problem(HttpStatus.BAD_REQUEST, "Malformed request body", "The request body could not be read");
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ProblemDetail handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
		return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type",
				"Supported content types: %s".formatted(ex.getSupportedMediaTypes()));
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<ProblemDetail> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
		Set<HttpMethod> allowed = ex.getSupportedHttpMethods();
		ProblemDetail problem = problem(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed",
				"%s is not supported on this resource".formatted(ex.getMethod()));
		return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
				.headers(headers -> {
					if (allowed != null) {
						headers.setAllow(allowed);
					}
				})
				.body(problem);
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ProblemDetail handleIntegrityViolation(DataIntegrityViolationException ex) {
		log.warn("Rejected a change that breaks a database rule: {}", ex.getMostSpecificCause().getMessage());
		return problem(HttpStatus.CONFLICT, "Conflicting change",
				"The change conflicts with the stored data; read it again and retry");
	}

	@ExceptionHandler({ CannotCreateTransactionException.class, DataAccessResourceFailureException.class })
	ProblemDetail handleDatabaseUnavailable(Exception ex) {
		log.error("Database unavailable", ex);
		return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable",
				"The database is unavailable. Please retry later");
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
