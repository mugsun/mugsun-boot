package com.mugsun.boot.ai.flow;

/**
 * AiFlow 迭代/并行节点契约常量：上限、默认超时、错误码。
 */
public final class AiFlowControlConstants {

	private AiFlowControlConstants() {
	}

	public static final String TYPE_FOREACH = "foreach";
	public static final String TYPE_PARALLEL = "parallel";

	public static final int FOREACH_DEFAULT_MAX_ITERATIONS = 20;
	public static final int FOREACH_HARD_MAX_ITERATIONS = 100;
	public static final int FOREACH_BODY_STEPS_HARD = 50;

	public static final int PARALLEL_DEFAULT_MAX_BRANCHES = 8;
	public static final int PARALLEL_HARD_MAX_BRANCHES = 16;

	public static final long DEFAULT_TIMEOUT_MS = 120_000L;
	public static final long MAX_TIMEOUT_MS = 300_000L;

	public static final String AGG_ARRAY = "array";
	public static final String AGG_LAST = "last";
	public static final String AGG_NONE = "none";

	public static final String ON_FAIL_CONTINUE = "continue";
	public static final String ON_FAIL_ABORT = "abort";

	public static final String ERR_FOREACH_INPUT_NOT_ARRAY = "AI_FOREACH_INPUT_NOT_ARRAY";
	public static final String ERR_FOREACH_MAX_ITERATIONS = "AI_FOREACH_MAX_ITERATIONS";
	public static final String ERR_FOREACH_TIMEOUT = "AI_FOREACH_TIMEOUT";
	public static final String ERR_FOREACH_BODY_INVALID = "AI_FOREACH_BODY_INVALID";
	public static final String ERR_FOREACH_ITEM_ERROR = "AI_FOREACH_ITEM_ERROR";
	public static final String ERR_PARALLEL_BRANCH_INVALID = "AI_PARALLEL_BRANCH_INVALID";
	public static final String ERR_PARALLEL_TIMEOUT = "AI_PARALLEL_TIMEOUT";
	public static final String ERR_PARALLEL_BRANCH_FAILED = "AI_PARALLEL_BRANCH_FAILED";
	public static final String ERR_EXECUTE = "EXECUTE_ERROR";

	public static final int SYNTHETIC_NODE_ID_MAX = 64;

	public static long clampTimeoutMs(long timeoutMs) {
		if (timeoutMs <= 0) {
			return DEFAULT_TIMEOUT_MS;
		}
		return Math.min(timeoutMs, MAX_TIMEOUT_MS);
	}

	public static int clampMaxIterations(int configured) {
		int v = configured <= 0 ? FOREACH_DEFAULT_MAX_ITERATIONS : configured;
		return Math.min(v, FOREACH_HARD_MAX_ITERATIONS);
	}

	public static int clampMaxBranches(int configured) {
		int v = configured <= 0 ? PARALLEL_DEFAULT_MAX_BRANCHES : configured;
		return Math.min(v, PARALLEL_HARD_MAX_BRANCHES);
	}

	/** parent#mid#child，超长时截断 parent 与 child 保总长 ≤64 */
	public static String syntheticNodeId(String parent, String mid, String child) {
		String p = parent == null ? "" : parent;
		String m = mid == null ? "" : mid;
		String c = child == null ? "" : child;
		String id = p + "#" + m + "#" + c;
		if (id.length() <= SYNTHETIC_NODE_ID_MAX) {
			return id;
		}
		int budget = SYNTHETIC_NODE_ID_MAX - m.length() - 2;
		if (budget < 2) {
			return id.substring(0, SYNTHETIC_NODE_ID_MAX);
		}
		int pLen = Math.min(p.length(), Math.max(1, budget / 2));
		int cLen = Math.min(c.length(), Math.max(1, budget - pLen));
		pLen = Math.min(p.length(), budget - cLen);
		return p.substring(0, pLen) + "#" + m + "#" + c.substring(0, cLen);
	}
}
