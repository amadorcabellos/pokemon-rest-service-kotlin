package com.ballastlane.pokemon.auth

import com.ballastlane.pokemon.config.AuthProperties
import com.ballastlane.pokemon.exception.TooManyLoginAttemptsException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Component

/**
 * Limits failed sign-ins per username: after `maxFailures` failures within `window`, the username is locked for
 * `lockout`. A successful sign-in clears it.
 *
 * Kept in memory, so it counts per application instance and starts again after a restart. The number of tracked
 * usernames is capped, so sign-in attempts with random usernames can't exhaust memory.
 */
@Component
class LoginAttemptLimiter(properties: AuthProperties, private val clock: Clock) {

    private val settings: AuthProperties.LoginAttempts = properties.loginAttempts

    private val attempts = ConcurrentHashMap<String, Attempts>()

    /**
     * @throws TooManyLoginAttemptsException if the username is locked
     */
    fun checkAllowed(username: String) {
        val entry = attempts[username] ?: return
        val now = clock.instant()
        synchronized(entry) {
            val lockedUntil = entry.lockedUntil
            if (lockedUntil != null && now.isBefore(lockedUntil)) {
                throw TooManyLoginAttemptsException(Duration.between(now, lockedUntil))
            }
        }
    }

    /**
     * @return `true` if this failure locked the username
     */
    fun recordFailure(username: String): Boolean {
        val now = clock.instant()
        if (!attempts.containsKey(username) && attempts.size >= MAX_TRACKED_USERNAMES) {
            evict(now)
        }
        val entry = attempts.computeIfAbsent(username) { Attempts() }
        synchronized(entry) {
            entry.lastActivity = now
            val windowStart = now.minus(settings.window)
            while (entry.failures.isNotEmpty() && !entry.failures.first().isAfter(windowStart)) {
                entry.failures.removeFirst()
            }
            entry.failures.addLast(now)
            if (entry.failures.size >= settings.maxFailures) {
                entry.failures.clear()
                entry.lockedUntil = now.plus(settings.lockout)
                return true
            }
            return false
        }
    }

    fun recordSuccess(username: String) {
        attempts.remove(username)
    }

    /** Forgets every username; for tests. */
    fun reset() {
        attempts.clear()
    }

    /**
     * Drops entries with nothing left to remember, then the least recently active ones if still full.
     */
    private fun evict(now: Instant) {
        val stale = now.minus(maxOf(settings.window, settings.lockout))
        attempts.entries.removeIf { it.value.lastActivity.isBefore(stale) }
        if (attempts.size >= MAX_TRACKED_USERNAMES) {
            attempts.entries.minByOrNull { it.value.lastActivity }?.let { attempts.remove(it.key) }
        }
    }

    internal fun trackedUsernames(): Int = attempts.size

    private class Attempts {

        val failures = ArrayDeque<Instant>()

        var lockedUntil: Instant? = null

        var lastActivity: Instant = Instant.EPOCH
    }

    internal companion object {

        const val MAX_TRACKED_USERNAMES = 10_000
    }
}
