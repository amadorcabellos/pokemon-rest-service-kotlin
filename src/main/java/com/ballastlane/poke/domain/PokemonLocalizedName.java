package com.ballastlane.poke.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Proprietary data: the name of a pokemon in one locale (e.g. {@code es-ES}). One name per locale.
 */
@Entity
@Table(name = "pokemon_localized_name")
public class PokemonLocalizedName {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "pokemon_id", nullable = false, updatable = false)
	private Pokemon pokemon;

	@Column(nullable = false)
	private String locale;

	@Column(nullable = false)
	private String name;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected PokemonLocalizedName() {
		// required by JPA
	}

	public PokemonLocalizedName(Pokemon pokemon, String locale, String name) {
		this.pokemon = pokemon;
		this.locale = locale;
		this.name = name;
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}

	@PreUpdate
	void onUpdate() {
		this.updatedAt = Instant.now();
	}

	public void rename(String name) {
		this.name = name;
	}

	public Long getId() {
		return id;
	}

	public Pokemon getPokemon() {
		return pokemon;
	}

	public String getLocale() {
		return locale;
	}

	public String getName() {
		return name;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
