package com.mugsun.boot.common.tx;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务提交后回调：事务激活时注册提交后执行（避免回滚后执行到不存在数据的动作），否则立即执行。
 */
public final class AfterCommit {

	private AfterCommit() {
	}

	public static void execute(Runnable action) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					action.run();
				}
			});
		} else {
			action.run();
		}
	}
}
