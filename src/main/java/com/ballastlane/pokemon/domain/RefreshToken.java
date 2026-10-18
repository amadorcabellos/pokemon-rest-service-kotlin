package com.ballastlane.pokemon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/**
 * A refresh token, stored only as its SHA-256 hash. It works once: using it sets {@code usedAt}, and the row is
 * kept so a second use can be recognised as reuse.
 */
@Entity
@Table(name = "auth_refresh_token")
public class RefreshToken implements Persistable<String> {

	@Id
	@Column(name = "token_hash")
	private String tokenHash;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "session_id", nullable = false, updatable = false)
	private AuthSession session;

	@Column(nullable = false, updatable = false)
	private int generation;

	@Column(name = "issued_at", nullable = false, updatable = false)
	private Instant issuedAt;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	@Transient
	private boolean isNew = true;

	protected RefreshToken() {
		// required by JPA
	}

	public RefreshToken(String tokenHash, AuthSession session, int generation, Instant issuedAt, Instant expiresAt) {
		this.tokenHash = tokenHash;
		this.session = session;
		this.generation = generation;
		this.issuedAt = issuedAt;
		this.expiresAt = expiresAt;
	}

	public boolean isUsed() {
		return usedAt != null;
	}

	public boolean isExpired(Instant now) {
		return !now.isBefore(expiresAt);
	}

	public void markUsed(Instant now) {
		this.usedAt = now;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		this.isNew = false;
	}

	@Override
	public String getId() {
		return tokenHash;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	public AuthSession getSession() {
		return session;
	}

	public int getGeneration() {
		return generation;
	}

	public Instant getIssuedAt() {
		return issuedAt;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getUsedAt() {
		return usedAt;
	}

}
