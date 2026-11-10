package com.linearlite.server.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import com.linearlite.server.time.BeijingTime;

/** 数据库租约沿服务端本地时钟生成；对外使用绝对时间，消除设备时区差异。 */
public final class CollaborationLeaseTime {
    private CollaborationLeaseTime() { }
    public static Instant toInstant(LocalDateTime leaseUntil) {
        return leaseUntil == null ? null : leaseUntil.atZone(BeijingTime.ZONE_ID).toInstant();
    }
}
