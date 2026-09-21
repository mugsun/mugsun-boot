package com.mugsun.boot.track.mapper;

import com.mugsun.boot.track.entity.TrackEvent;
import com.mybatisflex.core.BaseMapper;

/**
 * 埋点事件流水 Mapper（track 库，分区表；批量写入走摄入管道原生 SQL）。
 */
public interface TrackEventMapper extends BaseMapper<TrackEvent> {
}
