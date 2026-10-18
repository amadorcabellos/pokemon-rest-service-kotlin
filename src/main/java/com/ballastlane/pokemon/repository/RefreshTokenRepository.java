package com.ballastlane.pokemon.repository;

import com.ballastlane.pokemon.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

	/**
	 * Locks the token and its session until the end of the transaction, so concurrent refreshes or sign-outs
	 * with the same token run one after the other: the first one uses it, the next ones see it as used.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from RefreshToken t join fetch t.session s where t.tokenHash = :tokenHash")
	Optional<RefreshToken> findForUpdate(String tokenHash);

}
