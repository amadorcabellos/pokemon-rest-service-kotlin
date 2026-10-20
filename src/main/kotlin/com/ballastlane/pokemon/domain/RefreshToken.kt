package com.ballastlane.pokemon.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import java.time.Instant
import org.springframework.data.domain.Persistable

/**
 * A refresh token, stored only as its SHA-256 hash. It works once: using it sets `usedAt`, and the row is
 * kept so a second use can be recognised as reuse.
 */
@Entity
@Table(name = "auth_refresh_token")
class RefreshToken(
    @Id
    @Column(name = "token_hash")
    private val tokenHash: String,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false, updatable = false)
    val session: AuthSession,

    @Column(nullable = false, updatable = false)
    val generation: Int,

    @Column(name = "issued_at", nullable = false, updatable = false)
    val issuedAt: Instant,

    @Column(name = "expires_at", nullable = false, updatable = false)
    val expiresAt: Instant,
) : Persistable<String> {

    @Column(name = "used_at")
    var usedAt: Instant? = null
        protected set

    @Transient
    private var newEntity = true

    val isUsed: Boolean
        get() = usedAt != null

    fun isExpired(now: Instant): Boolean = !now.isBefore(expiresAt)

    fun markUsed(now: Instant) {
        usedAt = now
    }

    @PostLoad
    @PostPersist
    internal fun markNotNew() {
        newEntity = false
    }

    override fun getId(): String = tokenHash

    override fun isNew(): Boolean = newEntity
}
