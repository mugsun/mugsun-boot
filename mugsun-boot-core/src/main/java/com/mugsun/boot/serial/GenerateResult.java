package com.mugsun.boot.serial;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次生成结果：末值 + 本批号列表（未格式化的纯数字）
 */
public record GenerateResult(String code, long lastNumber, LocalDateTime lastTime, List<Long> numberList) {
}
