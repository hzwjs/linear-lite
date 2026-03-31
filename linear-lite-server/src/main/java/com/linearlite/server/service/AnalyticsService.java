package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.*;
import com.linearlite.server.entity.ProjectMember;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.AnalyticsMapper;
import com.linearlite.server.mapper.ProjectMemberMapper;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * 项目统计聚合服务：趋势、多维度分布、全局快照、任务明细分页。
 */
@Service
public class AnalyticsService {

    private static final Set<String> VALID_GRANULARITY = Set.of("day", "week", "month", "year");
    private static final Set<String> VALID_TASK_LIST_SCOPE = Set.of("created", "completed", "due", "all");
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final AnalyticsMapper analyticsMapper;
    private final ProjectMemberMapper projectMemberMapper;

    public AnalyticsService(AnalyticsMapper analyticsMapper, ProjectMemberMapper projectMemberMapper) {
        this.analyticsMapper = analyticsMapper;
        this.projectMemberMapper = projectMemberMapper;
    }

    public void validateQuery(AnalyticsQuery query) {
        if (query.getProjectId() == null) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        if (query.getGranularity() == null || !VALID_GRANULARITY.contains(query.getGranularity())) {
            throw new IllegalArgumentException("不支持的粒度: " + query.getGranularity());
        }
        if (query.getFrom() == null || query.getTo() == null) {
            throw new IllegalArgumentException("from 和 to 不能为空");
        }
        LocalDateTime from = LocalDateTime.parse(query.getFrom());
        LocalDateTime to = LocalDateTime.parse(query.getTo());
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from 不能晚于 to");
        }
        String scope = query.getTaskListScope();
        if (scope != null && !scope.isBlank() && !VALID_TASK_LIST_SCOPE.contains(scope)) {
            throw new IllegalArgumentException("不支持的 taskListScope: " + scope);
        }
    }

    /**
     * 聚合统计：趋势 + 全局快照 + 各维度分布。
     */
    public AnalyticsSummaryResponse getSummary(Long projectId, Long userId, AnalyticsQuery query) {
        requireProjectMember(projectId, userId);
        validateQuery(query);

        ZoneId zone = ZoneId.systemDefault();
        String from = query.getFrom();
        String to = query.getTo();

        // 趋势
        List<TrendBucket> trend = buildTrend(projectId, from, to, query.getGranularity());

        // 全局快照（不受 from/to）
        int totalCount = analyticsMapper.countAllTasks(projectId);
        List<StatusCount> globalStatus = analyticsMapper.selectGlobalStatusBreakdown(projectId);
        int overdueCount = analyticsMapper.countOverdue(projectId);
        ProjectSnapshot snapshot = new ProjectSnapshot(totalCount, globalStatus, overdueCount);

        // 区间内各维度分布
        List<StatusCount> statusBreakdown = analyticsMapper.selectStatusBreakdown(projectId, from, to);
        List<AssigneeCount> assigneeBreakdown = analyticsMapper.selectAssigneeBreakdown(projectId, from, to);
        List<PriorityCount> priorityBreakdown = analyticsMapper.selectPriorityBreakdown(projectId, from, to);

        AnalyticsSummaryResponse response = new AnalyticsSummaryResponse();
        response.setMeta(new AnalyticsSummaryResponse.Meta(projectId, zone.getId(), query.getGranularity(), "MONDAY"));
        response.setTrend(trend);
        response.setCurrentSnapshot(snapshot);
        response.setStatusBreakdown(statusBreakdown);
        response.setAssigneeBreakdown(assigneeBreakdown);
        response.setPriorityBreakdown(priorityBreakdown);
        return response;
    }

    /**
     * 任务明细分页查询。
     */
    public TaskSnapshotPageResponse getTaskSnapshot(Long projectId, Long userId, AnalyticsQuery query, int page, int pageSize) {
        requireProjectMember(projectId, userId);
        validateQuery(query);

        int cappedPageSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        int safePage = Math.max(1, page);
        int offset = (safePage - 1) * cappedPageSize;

        String scope = query.getTaskListScope();
        String normalized = (scope == null || scope.isBlank() || "all".equals(scope) || "created".equals(scope))
                ? "created" : scope;

        List<TaskSnapshotItem> items;
        int total;
        if ("completed".equals(normalized)) {
            items = analyticsMapper.selectTaskSnapshotByCompleted(projectId, query.getFrom(), query.getTo(), cappedPageSize, offset);
            total = analyticsMapper.countTaskSnapshotByCompleted(projectId, query.getFrom(), query.getTo());
        } else if ("due".equals(normalized)) {
            items = analyticsMapper.selectTaskSnapshotByDue(projectId, query.getFrom(), query.getTo(), cappedPageSize, offset);
            total = analyticsMapper.countTaskSnapshotByDue(projectId, query.getFrom(), query.getTo());
        } else {
            items = analyticsMapper.selectTaskSnapshot(projectId, query.getFrom(), query.getTo(), cappedPageSize, offset);
            total = analyticsMapper.countTaskSnapshot(projectId, query.getFrom(), query.getTo());
        }

        return new TaskSnapshotPageResponse(items, total, safePage, cappedPageSize);
    }

    // -------- 趋势构建 --------

    private List<TrendBucket> buildTrend(Long projectId, String from, String to, String granularity) {
        // 从 DB 获取各口径的每日聚合
        List<Map<String, Object>> dailyCreated = analyticsMapper.selectDailyCreatedCounts(projectId, from, to);
        List<Map<String, Object>> dailyCompleted = analyticsMapper.selectDailyCompletedCounts(projectId, from, to);
        List<Map<String, Object>> dailyDue = analyticsMapper.selectDailyDueCounts(projectId, from, to);

        // 转为 date -> count 的 map
        Map<LocalDate, Integer> createdMap = toDailyMap(dailyCreated, "createdCount");
        Map<LocalDate, Integer> completedMap = toDailyMap(dailyCompleted, "completedCount");
        Map<LocalDate, Integer> dueMap = toDailyMap(dailyDue, "dueCount");

        // 生成时间桶并聚合
        LocalDate startDate = LocalDate.parse(from.substring(0, 10));
        LocalDate endDate = LocalDate.parse(to.substring(0, 10));

        List<TrendBucket> buckets = new ArrayList<>();
        LocalDate cursor = toBucketStart(startDate, granularity);

        while (!cursor.isAfter(endDate)) {
            LocalDate bucketEnd = toBucketEnd(cursor, granularity);
            LocalDate effectiveEnd = bucketEnd.isAfter(endDate) ? endDate : bucketEnd;

            int created = 0, completed = 0, due = 0;
            for (LocalDate d = cursor; !d.isAfter(effectiveEnd); d = d.plusDays(1)) {
                created += createdMap.getOrDefault(d, 0);
                completed += completedMap.getOrDefault(d, 0);
                due += dueMap.getOrDefault(d, 0);
            }

            buckets.add(new TrendBucket(
                    cursor.atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    effectiveEnd.atTime(23, 59, 59).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    created, completed, due));

            cursor = bucketEnd.plusDays(1);
        }

        return buckets;
    }

    private LocalDate toBucketStart(LocalDate date, String granularity) {
        return switch (granularity) {
            case "day" -> date;
            case "week" -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> date.withDayOfMonth(1);
            case "year" -> date.withDayOfYear(1);
            default -> date;
        };
    }

    private LocalDate toBucketEnd(LocalDate bucketStart, String granularity) {
        return switch (granularity) {
            case "day" -> bucketStart;
            case "week" -> bucketStart.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
            case "month" -> bucketStart.with(TemporalAdjusters.lastDayOfMonth());
            case "year" -> bucketStart.with(TemporalAdjusters.lastDayOfYear());
            default -> bucketStart;
        };
    }

    private Map<LocalDate, Integer> toDailyMap(List<Map<String, Object>> rows, String countKey) {
        Map<LocalDate, Integer> map = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object dateObj = row.get("bucketStart");
            Object countObj = row.get(countKey);
            if (dateObj == null || countObj == null) continue;
            LocalDate date;
            if (dateObj instanceof java.sql.Date) {
                date = ((java.sql.Date) dateObj).toLocalDate();
            } else if (dateObj instanceof LocalDate) {
                date = (LocalDate) dateObj;
            } else {
                date = LocalDate.parse(dateObj.toString().substring(0, 10));
            }
            map.put(date, ((Number) countObj).intValue());
        }
        return map;
    }

    private void requireProjectMember(Long projectId, Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("当前用户未登录");
        }
        Long count = projectMemberMapper.selectCount(
                new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, projectId)
                        .eq(ProjectMember::getUserId, userId));
        if (count == null || count == 0) {
            throw new ForbiddenOperationException("你不是该项目成员");
        }
    }
}
