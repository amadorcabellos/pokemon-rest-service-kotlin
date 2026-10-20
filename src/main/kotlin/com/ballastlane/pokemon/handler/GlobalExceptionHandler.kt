package com.ballastlane.pokemon.handler

import com.ballastlane.pokemon.exception.InvalidCredentialsException
import com.ballastlane.pokemon.exception.InvalidIfMatchException
import com.ballastlane.pokemon.exception.InvalidLocalDataException
import com.ballastlane.pokemon.exception.InvalidPageRequestException
import com.ballastlane.pokemon.exception.InvalidRefreshTokenException
import com.ballastlane.pokemon.exception.InvalidRequestException
import com.ballastlane.pokemon.exception.LocalDataVersionMismatchException
import com.ballastlane.pokemon.exception.PokeApiException
import com.ballastlane.pokemon.exception.PokemonNotFoundException
import com.ballastlane.pokemon.exception.PokemonNotStoredLocallyException
import com.ballastlane.pokemon.exception.SyncAlreadyRunningException
import com.ballastlane.pokemon.exception.SyncRunNotFoundException
import com.ballastlane.pokemon.exception.TooManyLoginAttemptsException
import com.ballastlane.pokemon.exception.UsernameTakenException
import com.ballastlane.pokemon.filter.LocalDataRequestSizeFilter.RequestBodyTooLargeException
import com.ballastlane.pokemon.localdata.FieldProblem
import com.ballastlane.pokemon.web.SyncController
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * Maps domain exceptions to RFC 9457 problem details.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(PokemonNotFoundException::class)
    fun handleNotFound(ex: PokemonNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "Pokemon not found", ex.message)

    @ExceptionHandler(PokeApiException::class)
    fun handleUpstreamFailure(ex: PokeApiException): ProblemDetail {
        log.error("PokeAPI call failed", ex)
        return problem(HttpStatus.BAD_GATEWAY, "Upstream service error", "PokeAPI is currently unavailable")
    }

    @ExceptionHandler(CallNotPermittedException::class)
    fun handleCircuitOpen(ex: CallNotPermittedException): ProblemDetail {
        log.warn("Rejected PokeAPI call: {}", ex.message)
        return problem(
            HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable",
            "PokeAPI is failing; requests are paused for a short time. Please retry later",
        )
    }

    @ExceptionHandler(RequestNotPermitted::class)
    fun handleRateLimited(ex: RequestNotPermitted): ProblemDetail {
        log.warn("Rejected PokeAPI call: {}", ex.message)
        return problem(
            HttpStatus.TOO_MANY_REQUESTS, "Too many requests",
            "The PokeAPI request quota is exhausted. Please retry later",
        )
    }

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun handleValidation(ex: HandlerMethodValidationException): ProblemDetail {
        val detail = ex.allErrors.firstOrNull()?.defaultMessage ?: "Invalid request"
        val errors = ex.parameterValidationResults.flatMap { result ->
            result.resolvableErrors.map { error ->
                FieldProblem(result.methodParameter.parameterName.orEmpty(), error.defaultMessage.orEmpty())
            }
        }
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", detail).apply { setProperty("errors", errors) }
    }

    @ExceptionHandler(InvalidLocalDataException::class)
    fun handleInvalidLocalData(ex: InvalidLocalDataException): ProblemDetail {
        log.debug("Rejected local data update: {}", ex.problems)
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "The request body is invalid; see 'errors' for each problem")
            .apply { setProperty("errors", ex.problems) }
    }

    @ExceptionHandler(InvalidRequestException::class)
    fun handleInvalidRequest(ex: InvalidRequestException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "Invalid request", "The request body is invalid; see 'errors' for each problem")
            .apply { setProperty("errors", ex.problems) }

    @ExceptionHandler(UsernameTakenException::class)
    fun handleUsernameTaken(ex: UsernameTakenException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "Username already taken", ex.message)

    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentials(ex: InvalidCredentialsException): ProblemDetail =
        problem(HttpStatus.UNAUTHORIZED, "Invalid username or password", ex.message)

    @ExceptionHandler(InvalidRefreshTokenException::class)
    fun handleInvalidRefreshToken(ex: InvalidRefreshTokenException): ProblemDetail =
        problem(HttpStatus.UNAUTHORIZED, "Invalid refresh token", ex.message)

    @ExceptionHandler(TooManyLoginAttemptsException::class)
    fun handleTooManyLoginAttempts(ex: TooManyLoginAttemptsException): ResponseEntity<ProblemDetail> {
        val seconds = maxOf(1L, (ex.retryAfter.toMillis() + 999) / 1000)
        val problem = problem(HttpStatus.TOO_MANY_REQUESTS, "Too many sign-in attempts", "${ex.message} (in $seconds seconds)")
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, seconds.toString())
            .body(problem)
    }

    @ExceptionHandler(InvalidIfMatchException::class)
    fun handleInvalidIfMatch(ex: InvalidIfMatchException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.message)

    @ExceptionHandler(PokemonNotStoredLocallyException::class)
    fun handleNotStoredLocally(ex: PokemonNotStoredLocallyException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "Pokemon not found", ex.message)

    @ExceptionHandler(LocalDataVersionMismatchException::class)
    fun handleVersionMismatch(ex: LocalDataVersionMismatchException): ProblemDetail =
        problem(HttpStatus.PRECONDITION_FAILED, "Local data has changed", ex.message)
            .apply { setProperty("currentVersion", ex.currentVersion) }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(ex: HttpMessageNotReadableException): ProblemDetail {
        val tooLarge = ex.cause as? RequestBodyTooLargeException
        if (tooLarge != null) {
            return problem(HttpStatus.CONTENT_TOO_LARGE, "Request body too large", tooLarge.message)
        }
        // The converter's message can quote the input or name internal classes, so it is never passed on.
        return problem(HttpStatus.BAD_REQUEST, "Malformed request body", "The request body could not be read")
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleUnsupportedMediaType(ex: HttpMediaTypeNotSupportedException): ProblemDetail =
        problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type", "Supported content types: ${ex.supportedMediaTypes}")

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotAllowed(ex: HttpRequestMethodNotSupportedException): ResponseEntity<ProblemDetail> {
        val allowed = ex.supportedHttpMethods
        val problem = problem(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", "${ex.method} is not supported on this resource")
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .headers { headers -> if (allowed != null) headers.allow = allowed }
            .body(problem)
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleIntegrityViolation(ex: DataIntegrityViolationException): ProblemDetail {
        log.warn("Rejected a change that breaks a database rule: {}", ex.mostSpecificCause.message)
        return problem(
            HttpStatus.CONFLICT, "Conflicting change",
            "The change conflicts with the stored data; read it again and retry",
        )
    }

    @ExceptionHandler(CannotCreateTransactionException::class, DataAccessResourceFailureException::class)
    fun handleDatabaseUnavailable(ex: Exception): ProblemDetail {
        log.error("Database unavailable", ex)
        return problem(
            HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable",
            "The database is unavailable. Please retry later",
        )
    }

    @ExceptionHandler(InvalidPageRequestException::class)
    fun handleInvalidPage(ex: InvalidPageRequestException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "Invalid request", ex.message)

    @ExceptionHandler(SyncAlreadyRunningException::class)
    fun handleSyncAlreadyRunning(ex: SyncAlreadyRunningException): ProblemDetail {
        val runId = ex.runningRunId
            ?: return problem(HttpStatus.CONFLICT, "Synchronization already running", ex.message)
        return problem(
            HttpStatus.CONFLICT, "Synchronization already running",
            "${ex.message}; follow it at ${SyncController.runUri(runId)}",
        ).apply { setProperty("runId", runId) }
    }

    @ExceptionHandler(SyncRunNotFoundException::class)
    fun handleSyncRunNotFound(ex: SyncRunNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "Synchronization run not found", ex.message)

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "Invalid request", "'${ex.name}' must be a number")

    private companion object {

        private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

        fun problem(status: HttpStatus, title: String, detail: String?): ProblemDetail =
            ProblemDetail.forStatusAndDetail(status, detail).apply { this.title = title }
    }
}
