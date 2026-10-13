package com.mugsun.boot.common.tx;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务提交后回调：事务激活时注册提交后执行（避免回滚后执行到不存在数据的动作），否则立即执行。
 * <p>
 * 嵌套场景：在某个 afterCommit 回调内部再次调用本方法时，事务已提交但同步仍处于激活态，
 * 此时新注册的同步永远不会被触发（Spring 不会回调提交阶段新增的同步），因此直接立即执行。
 */
public final class AfterCommit {

	private static final ThreadLocal<Boolean> IN_AFTER_COMMIT = ThreadLocal.withInitial(() -> Boolean.FALSE);

	private AfterCommit() {
	}

	public static void execute(Runnable action) {
		if (TransactionSynchronizationManager.isSynchronizationActive() && !IN_AFTER_COMMIT.get()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					IN_AFTER_COMMIT.set(Boolean.TRUE);
					try {
						action.run();
					} finally {
						IN_AFTER_COMMIT.remove();
					}
				}
			});
		} else {
			action.run();
		}
	}
}
