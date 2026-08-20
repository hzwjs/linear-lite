package com.linearlite.server.config;

import com.linearlite.server.time.BeijingTime;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * 固定 JVM 默认时区，消除邮件、旧版日期格式化和第三方库对服务器时区的隐式依赖。
 */
@Configuration
public class TimeConfig {

    @PostConstruct
    void configureDefaultTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone(BeijingTime.ZONE_ID));
    }
}
