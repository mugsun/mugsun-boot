package com.mugsun.boot.common.constant;

/**
 * 工作流候选人（办理人）标识常量：节点 permissionFlag 以前缀编码多类型候选人，运行期解析成实际用户。
 */
public interface FlowConstants {

	/** 角色候选：role:&lt;roleCode&gt; → 该角色全部用户 */
	String CANDIDATE_ROLE = "role:";
	/** 部门候选：dept:&lt;deptId&gt; → 该部门全部用户 */
	String CANDIDATE_DEPT = "dept:";
	/** 岗位候选：post:&lt;postId&gt; → 该岗位全部用户 */
	String CANDIDATE_POST = "post:";
	/** 指定用户：user:&lt;userId&gt; → 该用户 */
	String CANDIDATE_USER = "user:";

	/** 发起人本人审批占位（assignment 监听器替换为实例发起人 id） */
	String PLACEHOLDER_INITIATOR = "initiator";
	/** 发起人部门负责人占位（assignment 监听器按发起人所在部门 leader 解析） */
	String PLACEHOLDER_DEPT_LEADER = "deptLeader";

	/** 门控哨兵：连兜底超管都取不到时写入，无人持有→fail-closed（任务挂起而非人人可批） */
	String GATE_SENTINEL = "__flow_gate__";

	/** 节点 permissionFlag 多候选人分隔符（须与 warm-flow FlowCons.SPLIT_AT 一致，引擎按此拆分） */
	String SEPARATOR = "@@";

	/** 字段级权限·可写（默认，不处理） */
	String FIELD_PERM_WRITE = "WRITE";
	/** 字段级权限·只读（办理时禁用，值保留；写门控时剔除该字段防污染） */
	String FIELD_PERM_READ = "READ";
	/** 字段级权限·隐藏（办理时不渲染，不校验不提交；写门控时剔除该字段） */
	String FIELD_PERM_NONE = "NONE";
}
