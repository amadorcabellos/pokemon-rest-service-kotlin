package com.ballastlane.pokemon.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.Persistable

/**
 * What one sign-in starts. Access tokens carry its id and current `tokenGeneration`; refreshing moves the
 * generation on, so only the newest access token is accepted. Ending the session invalidates all its tokens.
 */
@Entity
@Table(name = "auth_session")
class AuthSession(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    val user: AppUser,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,

    /** The maximum session lifetime: no refresh keeps the session alive past this. */
    @Column(name = "expires_at", nullable = false, updatable = false)
    val expiresAt: Instant,
) : Persistable<UUID> {

    @Id
    private val id: UUID = UUID.randomUUID()

    @Column(name = "token_generation", nullable = false)
    var tokenGeneration: Int = 0
        protected set

    @Column(name = "ended_at")
    var endedAt: Instant? = null
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason")
    var endReason: SessionEndReason? = null
        protected set

    @Transient
    private var newEntity = true

    fun isActive(now: Instant): Boolean = endedAt == null && now.isBefore(expiresAt)

    /**
     * Moves to the next token generation, retiring the access token of the current one.
     */
    fun nextGeneration(): Int = ++tokenGeneration

    fun end(reason: SessionEndReason, now: Instant) {
        if (endedAt == null) {
            endedAt = now
            endReason = reason
        }
    }

    @PostLoad
    @PostPersist
    internal fun markNotNew() {
        newEntity = false
    }

    override fun getId(): UUID = id

    override fun isNew(): Boolean = newEntity
}
