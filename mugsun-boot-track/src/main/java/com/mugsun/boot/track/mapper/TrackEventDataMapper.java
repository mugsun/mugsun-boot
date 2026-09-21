package com.mugsun.boot.track.mapper;

import com.mugsun.boot.track.entity.TrackEventData;
import com.mybatisflex.core.BaseMapper;

/**
 * 长尾属性 EAV Mapper（track 库，分区表；按需拆入走摄入管道原生 SQL）。
 */
public interface TrackEventDataMapper extends BaseMapper<TrackEventData> {
}
