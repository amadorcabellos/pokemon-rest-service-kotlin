package com.ballastlane.pokemon.domain;

/**
 * What a user may do. {@code ADMIN} implies {@code USER} (see {@code SecurityConfig}).
 */
public enum Role {

	/** Given on registration: may update local pokemon data. */
	USER,

	/** Also may run synchronizations. Only granted to the configured initial administrator. */
	ADMIN

}
