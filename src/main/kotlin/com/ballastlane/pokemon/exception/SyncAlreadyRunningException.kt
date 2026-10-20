package com.ballastlane.pokemon.exception

/**
 * Raised when a full synchronization is requested while another one is still running.
 *
 * @property runningRunId id of the run that is still running, or `null` if it finished in the meantime
 */
class SyncAlreadyRunningException(val runningRunId: Long?) : RuntimeException(
    if (runningRunId != null) "Synchronization run $runningRunId is still running"
    else "A synchronization run is still running",
)
