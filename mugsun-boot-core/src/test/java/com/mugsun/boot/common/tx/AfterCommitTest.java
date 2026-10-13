package com.mugsun.boot.common.tx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AfterCommitTest {

	@AfterEach
	void clear() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	private static void fireAfterCommit() {
		List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
		syncs.forEach(TransactionSynchronization::afterCommit);
	}

	@Test
	void runsImmediatelyWithoutTransaction() {
		List<String> log = new ArrayList<>();
		AfterCommit.execute(() -> log.add("run"));
		assertThat(log).containsExactly("run");
	}

	@Test
	void deferredUntilCommit() {
		TransactionSynchronizationManager.initSynchronization();
		List<String> log = new ArrayList<>();
		AfterCommit.execute(() -> log.add("run"));
		assertThat(log).isEmpty();
		fireAfterCommit();
		assertThat(log).containsExactly("run");
	}

	@Test
	void nestedCallInsideAfterCommitRunsImmediately() {
		TransactionSynchronizationManager.initSynchronization();
		List<String> log = new ArrayList<>();
		AfterCommit.execute(() -> {
			log.add("outer");
			AfterCommit.execute(() -> log.add("inner"));
		});
		fireAfterCommit();
		assertThat(log).containsExactly("outer", "inner");
	}
}
