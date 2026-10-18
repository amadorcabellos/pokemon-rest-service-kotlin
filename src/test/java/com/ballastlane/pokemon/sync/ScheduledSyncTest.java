package com.ballastlane.pokemon.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.ballastlane.pokemon.domain.SyncRunStatus;
import com.ballastlane.pokemon.domain.SyncTrigger;
import com.ballastlane.pokemon.exception.SyncAlreadyRunningException;
import com.ballastlane.pokemon.web.dto.SyncRunReport;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@ExtendWith(MockitoExtension.class)
class ScheduledSyncTest {

	@Mock
	private PokemonSyncService syncService;

	@InjectMocks
	private ScheduledSync scheduledSync;

	@Test
	void startsAScheduledFullSync() {
		given(syncService.startFullSync(SyncTrigger.SCHEDULED)).willReturn(new SyncRunReport(1L,
				SyncTrigger.SCHEDULED, SyncRunStatus.RUNNING, Instant.now(), null, 0, 0, 0, 0, List.of()));

		scheduledSync.startScheduledSync();

		verify(syncService).startFullSync(SyncTrigger.SCHEDULED);
	}

	@Test
	void skipsWhenAFullSyncIsAlreadyRunning() {
		given(syncService.startFullSync(SyncTrigger.SCHEDULED)).willThrow(new SyncAlreadyRunningException(3L));

		assertThatCode(scheduledSync::startScheduledSync).doesNotThrowAnyException();
	}

	@Test
	void isDisabledByDefault() {
		ApplicationContextRunner contextRunner = new ApplicationContextRunner()
				.withBean(PokemonSyncService.class, () -> syncService)
				.withUserConfiguration(ScheduledSync.class);

		contextRunner.run(context -> assertThat(context).doesNotHaveBean(ScheduledSync.class));
		contextRunner.withPropertyValues("app.sync.schedule.enabled=true")
				.run(context -> assertThat(context).hasSingleBean(ScheduledSync.class));
	}

}
