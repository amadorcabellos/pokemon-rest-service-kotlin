package com.ballastlane.poke.exception;

/**
 * The {@code If-Match} of a local data update doesn't match the current version, so the update was not applied.
 */
public class LocalDataVersionMismatchException extends RuntimeException {

	private final long currentVersion;

	public LocalDataVersionMismatchException(long currentVersion) {
		super("The local data has changed since it was read; it is now at version %d".formatted(currentVersion));
		this.currentVersion = currentVersion;
	}

	public long getCurrentVersion() {
		return currentVersion;
	}

}
