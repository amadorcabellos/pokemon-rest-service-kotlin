package com.ballastlane.pokemon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A pokemon that a full synchronization run could not synchronize, and why.
 */
@Entity
@Table(name = "sync_run_failure")
public class SyncRunFailure {

	static final int MAX_REASON_LENGTH = 500;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "sync_run_id", nullable = false, updatable = false)
	private SyncRun syncRun;

	@Column(name = "pokemon_name", nullable = false)
	private String pokemonName;

	@Column(nullable = false)
	private String reason;

	protected SyncRunFailure() {
		// required by JPA
	}

	SyncRunFailure(SyncRun syncRun, String pokemonName, String reason) {
		this.syncRun = syncRun;
		this.pokemonName = pokemonName;
		this.reason = reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason;
	}

	public Long getId() {
		return id;
	}

	public String getPokemonName() {
		return pokemonName;
	}

	public String getReason() {
		return reason;
	}

}
