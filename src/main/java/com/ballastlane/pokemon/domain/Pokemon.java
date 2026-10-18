package com.ballastlane.pokemon.domain;

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

/**
 * Local copy of a PokeAPI pokemon. Every field here is upstream data, owned by PokeAPI and refreshed by the sync.
 * <p>
 * Proprietary data ({@link PokemonLocalizedName}, {@link PokemonGeoMetadata}, {@link PokemonTag}) lives in its
 * own tables and points at this entity. It is deliberately not mapped here, so saving a pokemon can never
 * cascade into it.
 */
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

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "pokemon_stat", joinColumns = @JoinColumn(name = "pokemon_id"))
	@OrderColumn(name = "slot")
	private List<PokemonStat> stats = new ArrayList<>();

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "pokemon_ability", joinColumns = @JoinColumn(name = "pokemon_id"))
	@OrderColumn(name = "slot")
	private List<PokemonAbility> abilities = new ArrayList<>();

	@Column(name = "species_id")
	private Integer speciesId;

	@Column(name = "species_name")
	private String speciesName;

	/**
	 * When the sync last refreshed this row; {@code null} if it was only recorded by a lookup.
	 */
	@Column(name = "last_synced_at")
	private Instant lastSyncedAt;

	/*
	 * Version of the proprietary data, written only by PokemonRepository#bumpLocalDataVersion. Read only here on
	 * purpose: the sync saves whole Pokemon entities, and an updatable mapping would let it write a stale version
	 * back over a newer one.
	 */
	@Column(name = "local_data_version", insertable = false, updatable = false)
	private long localDataVersion;

	@Column(name = "local_data_modified_at", insertable = false, updatable = false)
	private Instant localDataModifiedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Pokemon() {
		// required by JPA
	}

	public Pokemon(Integer pokeApiId, String name, Integer height, Integer weight, Integer baseExperience,
			String spriteUrl, List<String> types) {
		this(pokeApiId, name, height, weight, baseExperience, spriteUrl, types, List.of(), List.of(), null, null);
	}

	public Pokemon(Integer pokeApiId, String name, Integer height, Integer weight, Integer baseExperience,
			String spriteUrl, List<String> types, List<PokemonStat> stats, List<PokemonAbility> abilities,
			Integer speciesId, String speciesName) {
		this.pokeApiId = pokeApiId;
		this.name = name;
		this.height = height;
		this.weight = weight;
		this.baseExperience = baseExperience;
		this.spriteUrl = spriteUrl;
		this.types = new ArrayList<>(types);
		this.stats = new ArrayList<>(stats);
		this.abilities = new ArrayList<>(abilities);
		this.speciesId = speciesId;
		this.speciesName = speciesName;
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

	/**
	 * Overwrites the upstream fields with those of {@code source}. Identity ({@code id}, {@code pokeApiId}) and
	 * audit timestamps are kept.
	 */
	public void copyUpstreamFrom(Pokemon source) {
		this.name = source.name;
		this.height = source.height;
		this.weight = source.weight;
		this.baseExperience = source.baseExperience;
		this.spriteUrl = source.spriteUrl;
		replace(this.types, source.types);
		replace(this.stats, source.stats);
		replace(this.abilities, source.abilities);
		this.speciesId = source.speciesId;
		this.speciesName = source.speciesName;
	}

	public void markSynced(Instant syncedAt) {
		this.lastSyncedAt = syncedAt;
	}

	// Element collections are updated in place so Hibernate keeps tracking the same collection instance.
	private static <T> void replace(List<T> target, List<T> source) {
		target.clear();
		target.addAll(source);
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

	public List<PokemonStat> getStats() {
		return List.copyOf(stats);
	}

	public List<PokemonAbility> getAbilities() {
		return List.copyOf(abilities);
	}

	public Integer getSpeciesId() {
		return speciesId;
	}

	public String getSpeciesName() {
		return speciesName;
	}

	public Instant getLastSyncedAt() {
		return lastSyncedAt;
	}

	public long getLocalDataVersion() {
		return localDataVersion;
	}

	public Instant getLocalDataModifiedAt() {
		return localDataModifiedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
