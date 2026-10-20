package com.ballastlane.pokemon.repository;

import com.ballastlane.pokemon.domain.AuthSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

	/**
	 * The session with its user and roles in one query: what every protected request checks.
	 */
	@Query("select s from AuthSession s join fetch s.user u left join fetch u._roles where s.id = :id")
	Optional<AuthSession> findWithUserById(UUID id);

}
