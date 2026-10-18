package com.ballastlane.poke.auth;

import com.ballastlane.poke.config.AuthProperties;
import com.ballastlane.poke.exception.TooManyLoginAttemptsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Limits failed sign-ins per username: after {@code maxFailures} failures within {@code window}, the username is
 * locked for {@code lockout}. A successful sign-in clears it.
 * <p>
 * Kept in memory, so it counts per application instance and starts again after a restart. The number of tracked
 * usernames is capped, so sign-in attempts with random usernames can't exhaust memory.
 */
@Component
public class LoginAttemptLimiter {

	static final int MAX_TRACKED_USERNAMES = 10_000;

	private final AuthProperties.LoginAttempts settings;

	private final Clock clock;

	private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

	public LoginAttemptLimiter(AuthProperties properties, Clock clock) {
		this.settings = properties.loginAttempts();
		this.clock = clock;
	}

	/**
	 * @throws TooManyLoginAttemptsException if the username is locked
	 */
	public void checkAllowed(String username) {
		Attempts entry = attempts.get(username);
		if (entry == null) {
			return;
		}
		Instant now = clock.instant();
		synchronized (entry) {
			if (entry.lockedUntil != null && now.isBefore(entry.lockedUntil)) {
				Duration retryAfter = Duration.between(now, entry.lockedUntil);
				throw new TooManyLoginAttemptsException(retryAfter);
			}
		}
	}

	/**
	 * @return {@code true} if this failure locked the username
	 */
	public boolean recordFailure(String username) {
		Instant now = clock.instant();
		if (!attempts.containsKey(username) && attempts.size() >= MAX_TRACKED_USERNAMES) {
			evict(now);
		}
		Attempts entry = attempts.computeIfAbsent(username, key -> new Attempts());
		synchronized (entry) {
			entry.lastActivity = now;
			Instant windowStart = now.minus(settings.window());
			while (!entry.failures.isEmpty() && !entry.failures.peekFirst().isAfter(windowStart)) {
				entry.failures.pollFirst();
			}
			entry.failures.addLast(now);
			if (entry.failures.size() >= settings.maxFailures()) {
				entry.failures.clear();
				entry.lockedUntil = now.plus(settings.lockout());
				return true;
			}
			return false;
		}
	}

	public void recordSuccess(String username) {
		attempts.remove(username);
	}

	/** Forgets every username; for tests. */
	public void reset() {
		attempts.clear();
	}

	/**
	 * Drops entries with nothing left to remember, then the least recently active ones if still full.
	 */
	private void evict(Instant now) {
		Instant stale = now.minus(settings.window().compareTo(settings.lockout()) > 0
				? settings.window() : settings.lockout());
		attempts.entrySet().removeIf(entry -> entry.getValue().lastActivity.isBefore(stale));
		if (attempts.size() >= MAX_TRACKED_USERNAMES) {
			attempts.entrySet().stream()
					.min(Comparator.comparing(entry -> entry.getValue().lastActivity))
					.ifPresent(oldest -> attempts.remove(oldest.getKey()));
		}
	}

	int trackedUsernames() {
		return attempts.size();
	}

	private static final class Attempts {

		private final Deque<Instant> failures = new ArrayDeque<>();

		private Instant lockedUntil;

		private Instant lastActivity = Instant.EPOCH;

	}

}
