package com.ballastlane.pokemon.exception;

/**
 * Raised when a full synchronization is requested while another one is still running.
 */
public class SyncAlreadyRunningException extends RuntimeException {

	private final Long runningRunId;

	public SyncAlreadyRunningException(Long runningRunId) {
		super(runningRunId != null ? "Synchronization run %d is still running".formatted(runningRunId)
				: "A synchronization run is still running");
		this.runningRunId = runningRunId;
	}

	/**
	 * Id of the run that is still running, or {@code null} if it finished in the meantime.
	 */
	public Long getRunningRunId() {
		return runningRunId;
	}

}
