package com.ballastlane.poke.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ballastlane.poke.config.AuthProperties;
import com.ballastlane.poke.exception.TooManyLoginAttemptsException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T10:00:00Z"));

	private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(new AuthProperties(
			"0123456789abcdef0123456789abcdef", Duration.ofMinutes(15), Duration.ofMinutes(15), Duration.ofDays(30),
			new AuthProperties.LoginAttempts(5, Duration.ofMinutes(15), Duration.ofMinutes(15)),
			new AuthProperties.InitialAdmin(null, null)), clock);

	@Test
	void locksAUsernameAfterFiveFailuresUntilTheLockoutEnds() {
		for (int i = 0; i < 4; i++) {
			assertThat(limiter.recordFailure("ash")).isFalse();
		}
		assertThat(limiter.recordFailure("ash")).isTrue();

		assertThatThrownBy(() -> limiter.checkAllowed("ash"))
				.isInstanceOfSatisfying(TooManyLoginAttemptsException.class,
						ex -> assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofMinutes(15)));
		assertThatCode(() -> limiter.checkAllowed("misty")).doesNotThrowAnyException();

		clock.advance(Duration.ofMinutes(15));
		assertThatCode(() -> limiter.checkAllowed("ash")).doesNotThrowAnyException();
	}

	@Test
	void onlyCountsFailuresInsideTheWindow() {
		for (int i = 0; i < 4; i++) {
			limiter.recordFailure("ash");
		}
		clock.advance(Duration.ofMinutes(16));

		assertThat(limiter.recordFailure("ash")).isFalse();
		assertThatCode(() -> limiter.checkAllowed("ash")).doesNotThrowAnyException();
	}

	@Test
	void aSuccessfulSignInClearsTheFailures() {
		for (int i = 0; i < 4; i++) {
			limiter.recordFailure("ash");
		}
		limiter.recordSuccess("ash");

		assertThat(limiter.recordFailure("ash")).isFalse();
	}

	@Test
	void capsTheNumberOfTrackedUsernames() {
		for (int i = 0; i < LoginAttemptLimiter.MAX_TRACKED_USERNAMES + 50; i++) {
			limiter.recordFailure("user" + i);
		}

		assertThat(limiter.trackedUsernames()).isLessThanOrEqualTo(LoginAttemptLimiter.MAX_TRACKED_USERNAMES);
	}

}
