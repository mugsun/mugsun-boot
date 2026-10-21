package com.mugsun.boot.auth.register;

/** 自助注册模式：OFF 关闭（接口 404）；OPEN 开放；APPROVAL 注册后审核（预留，尚未实现，配置即拒绝启动） */
public enum RegisterMode {
	OFF, OPEN, APPROVAL
}
