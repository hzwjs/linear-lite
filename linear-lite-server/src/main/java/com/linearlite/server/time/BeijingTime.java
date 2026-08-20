package com.linearlite.server.time;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 系统唯一的北京时间入口。业务代码不得依赖机器默认时区获取当前时间。
 */
public final class BeijingTime {
    public static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.system(ZONE_ID);

    private BeijingTime() {
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(CLOCK);
    }

    public static LocalDate today() {
        return LocalDate.now(CLOCK);
    }
}
