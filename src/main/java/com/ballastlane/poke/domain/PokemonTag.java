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
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;

/**
 * Proprietary data: an internal classification tag. Tags are free text, stored in lowercase and unique per pokemon.
 */
@Entity
@Table(name = "pokemon_tag")
public class PokemonTag {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "pokemon_id", nullable = false, updatable = false)
	private Pokemon pokemon;

	@Column(nullable = false, updatable = false)
	private String tag;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected PokemonTag() {
		// required by JPA
	}

	public PokemonTag(Pokemon pokemon, String tag) {
		this.pokemon = pokemon;
		this.tag = tag.trim().toLowerCase(Locale.ROOT);
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public Pokemon getPokemon() {
		return pokemon;
	}

	public String getTag() {
		return tag;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
