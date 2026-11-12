package com.linearlite.server.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import com.linearlite.server.time.BeijingTime;
import com.linearlite.server.exception.ForbiddenOperationException;

/** 数据库租约沿服务端本地时钟生成；对外使用绝对时间，消除设备时区差异。 */
public final class CollaborationLeaseTime {
    private CollaborationLeaseTime() { }
    public static Instant toInstant(LocalDateTime leaseUntil) {
        return leaseUntil == null ? null : leaseUntil.atZone(BeijingTime.ZONE_ID).toInstant();
    }

    /** 任务租约不得晚于批次运行租约和批次时限。 */
    public static LocalDateTime bounded(Instant batchLease, Instant deadline) {
        if (batchLease == null || deadline == null) throw new ForbiddenOperationException("批次运行租约无效");
        Instant now = Instant.now();
        Instant end = now.plus(5, ChronoUnit.MINUTES);
        if (batchLease.isBefore(end)) end = batchLease;
        if (deadline.isBefore(end)) end = deadline;
        end = end.truncatedTo(ChronoUnit.SECONDS);
        if (!end.isAfter(now)) throw new ForbiddenOperationException("批次运行租约到期");
        return LocalDateTime.ofInstant(end, BeijingTime.ZONE_ID);
    }
}
