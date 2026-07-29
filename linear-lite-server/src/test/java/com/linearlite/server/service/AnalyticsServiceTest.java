package com.linearlite.server.service;

import com.linearlite.server.dto.AnalyticsQuery;
import com.linearlite.server.dto.AnalyticsSummaryResponse;
import com.linearlite.server.dto.TaskSnapshotPageResponse;
import com.linearlite.server.mapper.AnalyticsMapper;
import com.linearlite.server.mapper.ProjectMemberMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {
    @Mock private AnalyticsMapper analyticsMapper;
    @Mock private ProjectMemberMapper projectMemberMapper;

    @Test
    void overdueScopeUsesTheGlobalOverdueQuery() {
        AnalyticsQuery query = new AnalyticsQuery();
        query.setProjectId(7L);
        query.setGranularity("week");
        query.setFrom("2026-07-27T00:00:00");
        query.setTo("2026-08-02T23:59:59");
        query.setTaskListScope("overdue");
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);
        when(analyticsMapper.selectOverdueTaskSnapshot(7L, 50, 0)).thenReturn(List.of());
        when(analyticsMapper.countOverdue(7L)).thenReturn(3);

        TaskSnapshotPageResponse response = new AnalyticsService(analyticsMapper, projectMemberMapper)
                .getTaskSnapshot(7L, 11L, query, 1, 50);

        assertEquals(3, response.getTotal());
        verify(analyticsMapper).selectOverdueTaskSnapshot(7L, 50, 0);
        verify(analyticsMapper).countOverdue(7L);
    }

    @Test
    void weeklyReviewUsesDailyTrendBuckets() {
        AnalyticsQuery query = new AnalyticsQuery();
        query.setProjectId(7L);
        query.setGranularity("week");
        query.setFrom("2026-07-27T00:00:00");
        query.setTo("2026-08-02T23:59:59");
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);
        when(analyticsMapper.selectDailyCreatedCounts(7L, query.getFrom(), query.getTo())).thenReturn(List.of());
        when(analyticsMapper.selectDailyCompletedCounts(7L, query.getFrom(), query.getTo())).thenReturn(List.of());
        when(analyticsMapper.selectDailyDueCounts(7L, query.getFrom(), query.getTo())).thenReturn(List.of());

        AnalyticsSummaryResponse response = new AnalyticsService(analyticsMapper, projectMemberMapper)
                .getSummary(7L, 11L, query);

        assertEquals("day", response.getMeta().getBucketUnit());
        assertEquals(7, response.getTrend().size());
    }
}
