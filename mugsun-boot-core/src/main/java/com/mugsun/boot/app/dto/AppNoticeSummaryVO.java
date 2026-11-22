package com.mugsun.boot.app.dto;

import java.util.List;

/**
 * 首页要看的公告。
 *
 * @param unread 没读的条数
 * @param recent 最近的几条
 */
public record AppNoticeSummaryVO(long unread, List<AppNoticeItemVO> recent) {
}
