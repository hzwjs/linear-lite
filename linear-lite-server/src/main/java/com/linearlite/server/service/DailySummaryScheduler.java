package com.linearlite.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
public class DailySummaryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailySummaryScheduler.class);

    private final DailySummaryDispatchService dispatchService;
    private final ZoneId zoneId;

    public DailySummaryScheduler(
            DailySummaryDispatchService dispatchService,
            @Value("${app.email.digest.zone:Asia/Shanghai}") String zone) {
        this.dispatchService = dispatchService;
        this.zoneId = ZoneId.of(zone);
    }

    @Scheduled(cron = "${app.email.digest.cron:0 30 16 * * *}")
    public void runDaily() {
        LocalDate businessDate = LocalDate.now(zoneId);
        log.info("今日汇总调度开始 businessDate={}", businessDate);
        dispatchService.dispatchForDate(businessDate);
        log.info("今日汇总调度结束 businessDate={}", businessDate);
    }
}
