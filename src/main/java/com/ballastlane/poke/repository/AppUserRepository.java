package com.ballastlane.poke.repository;

import com.ballastlane.poke.domain.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	/**
	 * @param username already normalized (trimmed, lowercase)
	 */
	Optional<AppUser> findByUsername(String username);

}
