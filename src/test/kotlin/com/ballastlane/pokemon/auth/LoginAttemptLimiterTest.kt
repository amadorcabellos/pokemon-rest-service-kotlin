package com.ballastlane.pokemon.auth

import com.ballastlane.pokemon.config.AuthProperties
import com.ballastlane.pokemon.exception.TooManyLoginAttemptsException
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Test

class LoginAttemptLimiterTest {

    private val clock = MutableClock(Instant.parse("2026-10-18T10:00:00Z"))

    private val limiter = LoginAttemptLimiter(
        AuthProperties(
            "0123456789abcdef0123456789abcdef", Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofDays(30),
            AuthProperties.LoginAttempts(5, Duration.ofMinutes(15), Duration.ofMinutes(15)),
            AuthProperties.InitialAdmin(null, null),
        ),
        clock,
    )

    @Test
    fun locksAUsernameAfterFiveFailuresUntilTheLockoutEnds() {
        repeat(4) { limiter.recordFailure("ash") shouldBe false }
        limiter.recordFailure("ash") shouldBe true

        shouldThrow<TooManyLoginAttemptsException> { limiter.checkAllowed("ash") }.retryAfter shouldBe
            Duration.ofMinutes(15)
        shouldNotThrowAny { limiter.checkAllowed("misty") }

        clock.advance(Duration.ofMinutes(15))
        shouldNotThrowAny { limiter.checkAllowed("ash") }
    }

    @Test
    fun onlyCountsFailuresInsideTheWindow() {
        repeat(4) { limiter.recordFailure("ash") }
        clock.advance(Duration.ofMinutes(16))

        limiter.recordFailure("ash") shouldBe false
        shouldNotThrowAny { limiter.checkAllowed("ash") }
    }

    @Test
    fun aSuccessfulSignInClearsTheFailures() {
        repeat(4) { limiter.recordFailure("ash") }
        limiter.recordSuccess("ash")

        limiter.recordFailure("ash") shouldBe false
    }

    @Test
    fun capsTheNumberOfTrackedUsernames() {
        repeat(LoginAttemptLimiter.MAX_TRACKED_USERNAMES + 50) { limiter.recordFailure("user$it") }

        limiter.trackedUsernames() shouldBeLessThanOrEqual LoginAttemptLimiter.MAX_TRACKED_USERNAMES
    }
}
