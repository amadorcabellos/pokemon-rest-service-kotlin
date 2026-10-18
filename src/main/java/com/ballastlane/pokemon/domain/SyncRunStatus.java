package com.ballastlane.pokemon.domain;

public enum SyncRunStatus {

	RUNNING,

	COMPLETED,

	/** Finished, but some pokemon could not be synchronized (see the run's failures). */
	COMPLETED_WITH_ERRORS,

	/** Stopped early: the catalogue could not be read, or PokeAPI kept rejecting calls. */
	FAILED,

	/** Still running when the application stopped. */
	INTERRUPTED

}
