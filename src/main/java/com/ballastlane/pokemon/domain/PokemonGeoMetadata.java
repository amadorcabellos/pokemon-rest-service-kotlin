package com.ballastlane.pokemon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Proprietary data: a geographical fact about a pokemon (a region, area or habitat), with an optional note.
 */
@Entity
@Table(name = "pokemon_geo_metadata")
public class PokemonGeoMetadata {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "pokemon_id", nullable = false, updatable = false)
	private Pokemon pokemon;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private GeoKind kind;

	@Column(nullable = false)
	private String value;

	private String note;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected PokemonGeoMetadata() {
		// required by JPA
	}

	public PokemonGeoMetadata(Pokemon pokemon, GeoKind kind, String value, String note) {
		this.pokemon = pokemon;
		this.kind = kind;
		this.value = value;
		this.note = note;
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

	public Long getId() {
		return id;
	}

	public Pokemon getPokemon() {
		return pokemon;
	}

	public GeoKind getKind() {
		return kind;
	}

	public String getValue() {
		return value;
	}

	public String getNote() {
		return note;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
