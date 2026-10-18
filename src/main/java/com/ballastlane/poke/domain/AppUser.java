package com.ballastlane.poke.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * A user of the auth API. The username is stored normalized (trimmed, lowercase); the password only as a hash.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, updatable = false)
	private String username;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "app_user_role", joinColumns = @JoinColumn(name = "user_id"))
	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false)
	private Set<Role> roles = EnumSet.noneOf(Role.class);

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected AppUser() {
		// required by JPA
	}

	public AppUser(String username, String passwordHash, Set<Role> roles) {
		this.username = username;
		this.passwordHash = passwordHash;
		this.roles = EnumSet.copyOf(roles);
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
	}

	public void removeRole(Role role) {
		roles.remove(role);
	}

	public Long getId() {
		return id;
	}

	public String getUsername() {
		return username;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public Set<Role> getRoles() {
		return Set.copyOf(roles);
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
