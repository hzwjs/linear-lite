package com.linearlite.server.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DailySummarySchedulerTest {

    private DailySummaryDispatchService dispatchService;
    private DailySummaryScheduler scheduler;

    @BeforeEach
    void setUp() {
        dispatchService = mock(DailySummaryDispatchService.class);
        scheduler = new DailySummaryScheduler(dispatchService, "Asia/Shanghai");
    }

    @Test
    void runDailyDispatchesForToday() {
        scheduler.runDaily();

        verify(dispatchService).dispatchForDate(any(LocalDate.class));
    }
}
