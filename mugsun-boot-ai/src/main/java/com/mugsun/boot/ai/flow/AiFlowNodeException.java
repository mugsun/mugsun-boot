package com.mugsun.boot.ai.flow;

/**
 * AiFlow 节点级失败：携带契约错误码，供 {@link AiFlowExecutor} 写入 AiAppRun.errorCode。
 */
public class AiFlowNodeException extends RuntimeException {

	private final String errorCode;

	public AiFlowNodeException(String errorCode, String message) {
		super(message == null ? "" : message);
		this.errorCode = errorCode == null || errorCode.isBlank()
			? AiFlowControlConstants.ERR_EXECUTE : errorCode;
	}

	public AiFlowNodeException(String errorCode, String message, Throwable cause) {
		super(message == null ? "" : message, cause);
		this.errorCode = errorCode == null || errorCode.isBlank()
			? AiFlowControlConstants.ERR_EXECUTE : errorCode;
	}

	public String getErrorCode() {
		return errorCode;
	}
}
