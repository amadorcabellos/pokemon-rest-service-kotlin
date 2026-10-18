package com.ballastlane.pokemon.repository;

import com.ballastlane.pokemon.domain.SyncRun;
import com.ballastlane.pokemon.domain.SyncRunStatus;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SyncRunRepository extends JpaRepository<SyncRun, Long> {

	Optional<SyncRun> findFirstByStatus(SyncRunStatus status);

	Optional<SyncRun> findTopByOrderByIdDesc();

	/**
	 * Moves every run in status {@code from} to status {@code to}, finishing it at {@code finishedAt}.
	 */
	@Modifying
	@Query("update SyncRun r set r.status = :to, r.finishedAt = :finishedAt where r.status = :from")
	int finishAll(@Param("from") SyncRunStatus from, @Param("to") SyncRunStatus to,
			@Param("finishedAt") Instant finishedAt);

}
