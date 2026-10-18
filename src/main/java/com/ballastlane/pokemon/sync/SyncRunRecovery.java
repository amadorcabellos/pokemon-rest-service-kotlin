package com.ballastlane.pokemon.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On startup, marks runs that were still running when the application stopped as interrupted, so no run stays
 * "running" forever and a new full sync can start. Assumes a single application instance.
 */
@Component
public class SyncRunRecovery {

	private static final Logger log = LoggerFactory.getLogger(SyncRunRecovery.class);

	private final SyncRunStore store;

	public SyncRunRecovery(SyncRunStore store) {
		this.store = store;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void interruptLeftoverRuns() {
		int interrupted = store.interruptRunning();
		if (interrupted > 0) {
			log.warn("Marked {} synchronization run(s) left running by a previous instance as interrupted", interrupted);
		}
	}

}
