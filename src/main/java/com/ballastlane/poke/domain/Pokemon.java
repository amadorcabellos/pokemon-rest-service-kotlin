package com.ballastlane.poke.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "pokemon")
public class Pokemon {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "poke_api_id", nullable = false, unique = true)
	private Integer pokeApiId;

	@Column(nullable = false, unique = true)
	private String name;

	private Integer height;

	private Integer weight;

	@Column(name = "base_experience")
	private Integer baseExperience;

	@Column(name = "sprite_url")
	private String spriteUrl;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "pokemon_type", joinColumns = @JoinColumn(name = "pokemon_id"))
	@OrderColumn(name = "slot")
	@Column(name = "type_name", nullable = false)
	private List<String> types = new ArrayList<>();

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Pokemon() {
		// required by JPA
	}

	public Pokemon(Integer pokeApiId, String name, Integer height, Integer weight, Integer baseExperience,
			String spriteUrl, List<String> types) {
		this.pokeApiId = pokeApiId;
		this.name = name;
		this.height = height;
		this.weight = weight;
		this.baseExperience = baseExperience;
		this.spriteUrl = spriteUrl;
		this.types = new ArrayList<>(types);
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

	public Integer getPokeApiId() {
		return pokeApiId;
	}

	public String getName() {
		return name;
	}

	public Integer getHeight() {
		return height;
	}

	public Integer getWeight() {
		return weight;
	}

	public Integer getBaseExperience() {
		return baseExperience;
	}

	public String getSpriteUrl() {
		return spriteUrl;
	}

	public List<String> getTypes() {
		return List.copyOf(types);
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
