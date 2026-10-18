package com.ballastlane.pokemon.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One full synchronization of the PokeAPI catalogue: its status and counts.
 * <p>
 * The database allows at most one run in {@link SyncRunStatus#RUNNING} at a time (partial unique index).
 */
@Entity
@Table(name = "sync_run")
public class SyncRun {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(name = "triggered_by", nullable = false, updatable = false)
	private SyncTrigger trigger;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SyncRunStatus status;

	@Column(name = "started_at", nullable = false, updatable = false)
	private Instant startedAt;

	@Column(name = "finished_at")
	private Instant finishedAt;

	@Column(name = "catalogue_count", nullable = false)
	private int catalogueCount;

	@Column(name = "created_count", nullable = false)
	private int createdCount;

	@Column(name = "updated_count", nullable = false)
	private int updatedCount;

	@Column(name = "failed_count", nullable = false)
	private int failedCount;

	@OneToMany(mappedBy = "syncRun", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("id")
	private List<SyncRunFailure> failures = new ArrayList<>();

	protected SyncRun() {
		// required by JPA
	}

	public SyncRun(SyncTrigger trigger, Instant startedAt) {
		this.trigger = trigger;
		this.status = SyncRunStatus.RUNNING;
		this.startedAt = startedAt;
	}

	public void recordProgress(int catalogueCount, int createdCount, int updatedCount, int failedCount) {
		this.catalogueCount = catalogueCount;
		this.createdCount = createdCount;
		this.updatedCount = updatedCount;
		this.failedCount = failedCount;
	}

	public void addFailure(String pokemonName, String reason) {
		failures.add(new SyncRunFailure(this, pokemonName, reason));
	}

	public void finish(SyncRunStatus status, Instant finishedAt) {
		this.status = status;
		this.finishedAt = finishedAt;
	}

	public Long getId() {
		return id;
	}

	public SyncTrigger getTrigger() {
		return trigger;
	}

	public SyncRunStatus getStatus() {
		return status;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getFinishedAt() {
		return finishedAt;
	}

	public int getCatalogueCount() {
		return catalogueCount;
	}

	public int getCreatedCount() {
		return createdCount;
	}

	public int getUpdatedCount() {
		return updatedCount;
	}

	public int getFailedCount() {
		return failedCount;
	}

	public List<SyncRunFailure> getFailures() {
		return List.copyOf(failures);
	}

}
