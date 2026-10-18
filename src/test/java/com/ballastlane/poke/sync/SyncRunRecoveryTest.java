package com.ballastlane.poke.sync;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SyncRunRecoveryTest {

	@Mock
	private SyncRunStore store;

	@InjectMocks
	private SyncRunRecovery recovery;

	@Test
	void marksLeftoverRunningRunsAsInterrupted() {
		given(store.interruptRunning()).willReturn(1);

		recovery.interruptLeftoverRuns();

		verify(store).interruptRunning();
	}

}
