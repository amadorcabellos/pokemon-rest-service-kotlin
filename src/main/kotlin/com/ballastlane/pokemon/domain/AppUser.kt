package com.ballastlane.pokemon.domain

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.PrePersist
import jakarta.persistence.Table
import java.time.Instant
import java.util.EnumSet

/**
 * A user of the auth API. The username is stored normalized (trimmed, lowercase); the password only as a hash.
 */
@Entity
@Table(name = "app_user")
class AppUser(
    @Column(nullable = false, unique = true, updatable = false)
    val username: String,

    @Column(name = "password_hash", nullable = false)
    val passwordHash: String,

    roles: Set<Role>,
) {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "app_user_role", joinColumns = [JoinColumn(name = "user_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private val _roles: MutableSet<Role> = EnumSet.copyOf(roles)

    val roles: Set<Role>
        get() = _roles.toSet()

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        protected set

    @PrePersist
    internal fun onCreate() {
        createdAt = Instant.now()
    }

    fun removeRole(role: Role) {
        _roles.remove(role)
    }
}
