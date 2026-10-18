package com.ballastlane.pokemon.sync;

import com.ballastlane.pokemon.domain.SyncTrigger;
import com.ballastlane.pokemon.exception.SyncAlreadyRunningException;
import com.ballastlane.pokemon.web.dto.SyncRunReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Starts a full synchronization on the {@code app.sync.schedule.cron} schedule. Only active when
 * {@code app.sync.schedule.enabled=true}.
 */
@Component
@ConditionalOnProperty(prefix = "app.sync.schedule", name = "enabled", havingValue = "true")
public class ScheduledSync {

	private static final Logger log = LoggerFactory.getLogger(ScheduledSync.class);

	private final PokemonSyncService syncService;

	public ScheduledSync(PokemonSyncService syncService) {
		this.syncService = syncService;
	}

	@Scheduled(cron = "${app.sync.schedule.cron}")
	public void startScheduledSync() {
		try {
			SyncRunReport run = syncService.startFullSync(SyncTrigger.SCHEDULED);
			log.info("Started scheduled synchronization run {}", run.id());
		}
		catch (SyncAlreadyRunningException ex) {
			log.info("Skipping scheduled synchronization: {}", ex.getMessage());
		}
	}

}
