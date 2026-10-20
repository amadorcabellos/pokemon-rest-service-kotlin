package com.ballastlane.pokemon.repository

import com.ballastlane.pokemon.domain.AppUser
import java.util.Optional
import org.springframework.data.jpa.repository.JpaRepository

interface AppUserRepository : JpaRepository<AppUser, Long> {

    /**
     * @param username already normalized (trimmed, lowercase)
     */
    fun findByUsername(username: String): Optional<AppUser>
}
