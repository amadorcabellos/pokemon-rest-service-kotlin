package com.ballastlane.pokemon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * What one sign-in starts. Access tokens carry its id and current {@code tokenGeneration}; refreshing moves the
 * generation on, so only the newest access token is accepted. Ending the session invalidates all its tokens.
 */
@Entity
@Table(name = "auth_session")
public class AuthSession implements Persistable<UUID> {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	private AppUser user;

	@Column(name = "token_generation", nullable = false)
	private int tokenGeneration;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	/** The maximum session lifetime: no refresh keeps the session alive past this. */
	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "ended_at")
	private Instant endedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "end_reason")
	private SessionEndReason endReason;

	@Transient
	private boolean isNew = true;

	protected AuthSession() {
		// required by JPA
	}

	public AuthSession(AppUser user, Instant createdAt, Instant expiresAt) {
		this.id = UUID.randomUUID();
		this.user = user;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	public boolean isActive(Instant now) {
		return endedAt == null && now.isBefore(expiresAt);
	}

	/**
	 * Moves to the next token generation, retiring the access token of the current one.
	 */
	public int nextGeneration() {
		return ++tokenGeneration;
	}

	public void end(SessionEndReason reason, Instant now) {
		if (endedAt == null) {
			this.endedAt = now;
			this.endReason = reason;
		}
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		this.isNew = false;
	}

	@Override
	public UUID getId() {
		return id;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	public AppUser getUser() {
		return user;
	}

	public int getTokenGeneration() {
		return tokenGeneration;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getEndedAt() {
		return endedAt;
	}

	public SessionEndReason getEndReason() {
		return endReason;
	}

}
