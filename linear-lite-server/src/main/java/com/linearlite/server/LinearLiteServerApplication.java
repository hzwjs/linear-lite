package com.linearlite.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.linearlite.server.time.BeijingTime;

import java.util.TimeZone;

@SpringBootApplication
@MapperScan("com.linearlite.server.mapper")
@EnableScheduling
public class LinearLiteServerApplication {

    public static void main(String[] args) {
        // 在 Spring 初始化前固定 JVM 时区，保证启动阶段创建的时间对象也使用北京时间。
        TimeZone.setDefault(TimeZone.getTimeZone(BeijingTime.ZONE_ID));
        SpringApplication.run(LinearLiteServerApplication.class, args);
    }
}
