package com.ballastlane.poke.sync;

/**
 * Stops a full synchronization run early; the run is recorded as failed.
 */
class SyncAbortedException extends RuntimeException {

	SyncAbortedException(String message, Throwable cause) {
		super(message, cause);
	}

}
