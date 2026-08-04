package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.linearlite.server.dto.DailySummaryTaskDto;
import com.linearlite.server.dto.DigestMailContent;
import com.linearlite.server.entity.ProjectEmailDispatch;
import com.linearlite.server.mapper.ProjectEmailDispatchMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DailySummaryDispatchService {

    private static final Logger log = LoggerFactory.getLogger(DailySummaryDispatchService.class);
    private static final LocalTime DAILY_SUMMARY_TIME = LocalTime.of(16, 30);

    private final ProjectEmailPreferenceService preferenceService;
    private final DailySummaryQueryService queryService;
    private final DigestMailComposer composer;
    private final DigestMailSender sender;
    private final ProjectEmailDispatchMapper dispatchMapper;

    public DailySummaryDispatchService(
            ProjectEmailPreferenceService preferenceService,
            DailySummaryQueryService queryService,
            DigestMailComposer composer,
            DigestMailSender sender,
            ProjectEmailDispatchMapper dispatchMapper) {
        this.preferenceService = preferenceService;
        this.queryService = queryService;
        this.composer = composer;
        this.sender = sender;
        this.dispatchMapper = dispatchMapper;
    }

    public void dispatchForDate(LocalDate businessDate) {
        List<Long> projectIds = preferenceService.listEnabledProjectIds(
                ProjectEmailPreferenceService.SCENARIO_DAILY_SUMMARY);
        if (projectIds.isEmpty()) return;

        LocalDateTime startOfToday = businessDate.atStartOfDay();
        LocalDateTime endOfToday = businessDate.plusDays(1).atStartOfDay();
        // 每日汇总的完成窗口必须首尾相接：本次 16:30 统计上次 16:30 之后的完成任务。
        LocalDateTime completedWindowStart = businessDate.minusDays(1).atTime(DAILY_SUMMARY_TIME);
        LocalDateTime completedWindowEnd = businessDate.atTime(DAILY_SUMMARY_TIME);
        List<DailySummaryTaskDto> tasks = queryService.findDueTasks(
                projectIds, startOfToday, endOfToday, completedWindowStart, completedWindowEnd);
        if (tasks.isEmpty()) return;

        // 汇总到用户后发送，确保同一用户当天只收到一封跨项目邮件。
        Map<Long, List<DailySummaryTaskDto>> tasksByAssignee = tasks.stream()
                .filter(t -> t.getAssigneeId() != null)
                .collect(Collectors.groupingBy(DailySummaryTaskDto::getAssigneeId));

        for (Map.Entry<Long, List<DailySummaryTaskDto>> entry : tasksByAssignee.entrySet()) {
            DailySummaryTaskDto first = entry.getValue().get(0);
            String email = first.getAssigneeEmail();
            String username = first.getAssigneeUsername();
            if (email == null || email.isBlank()) continue;

            dispatchOne(entry.getKey(), username, email, entry.getValue(), businessDate,
                    completedWindowStart, completedWindowEnd);
        }
    }

    private void dispatchOne(Long recipientUserId, String recipientName,
                             String recipientEmail, List<DailySummaryTaskDto> tasks, LocalDate businessDate,
                             LocalDateTime completedWindowStart, LocalDateTime completedWindowEnd) {
        ProjectEmailDispatch record = dispatchMapper.selectOne(
                new LambdaQueryWrapper<ProjectEmailDispatch>()
                        .eq(ProjectEmailDispatch::getScenarioKey,
                                ProjectEmailPreferenceService.SCENARIO_DAILY_SUMMARY)
                        .eq(ProjectEmailDispatch::getBusinessDate, businessDate)
                        .eq(ProjectEmailDispatch::getRecipientUserId, recipientUserId));
        if (record != null && "sent".equals(record.getStatus())) return;

        DigestMailContent content;
        try {
            content = composer.compose(recipientName, businessDate, completedWindowStart, completedWindowEnd, tasks);
        } catch (RuntimeException e) {
            log.warn("今日汇总邮件编排失败 userId={}", recipientUserId, e);
            return;
        }

        if (record == null) {
            record = new ProjectEmailDispatch();
            record.setScenarioKey(ProjectEmailPreferenceService.SCENARIO_DAILY_SUMMARY);
            record.setBusinessDate(businessDate);
            record.setRecipientUserId(recipientUserId);
            record.setStatus("pending");
            record.setSubject(content.getSubject());
            record.setTaskCount(content.getTaskCount());
            record.setCreatedAt(LocalDateTime.now());
            record.setUpdatedAt(LocalDateTime.now());
            try {
                dispatchMapper.insert(record);
            } catch (DuplicateKeyException ignored) {
                record = dispatchMapper.selectOne(
                        new LambdaQueryWrapper<ProjectEmailDispatch>()
                                .eq(ProjectEmailDispatch::getScenarioKey,
                                        ProjectEmailPreferenceService.SCENARIO_DAILY_SUMMARY)
                                .eq(ProjectEmailDispatch::getBusinessDate, businessDate)
                                .eq(ProjectEmailDispatch::getRecipientUserId, recipientUserId));
                if (record == null || "sent".equals(record.getStatus())) {
                    return;
                }
            }
        }

        LocalDateTime now = LocalDateTime.now();
        int claimed = dispatchMapper.update(null,
                new UpdateWrapper<ProjectEmailDispatch>()
                        .eq("id", record.getId())
                        .in("status", "pending", "failed")
                        .set("status", "sending")
                        .set("updated_at", now));
        if (claimed != 1) {
            return;
        }

        record.setStatus("sending");
        record.setUpdatedAt(now);
        record.setSubject(content.getSubject());
        record.setTaskCount(content.getTaskCount());

        try {
            sender.send(recipientEmail, content);
            record.setStatus("sent");
            record.setSentAt(LocalDateTime.now());
            record.setUpdatedAt(LocalDateTime.now());
            dispatchMapper.updateById(record);
        } catch (RuntimeException e) {
            record.setStatus("failed");
            String msg = e.getMessage();
            record.setLastError(msg == null ? null : msg.substring(0, Math.min(msg.length(), 1024)));
            record.setUpdatedAt(LocalDateTime.now());
            dispatchMapper.updateById(record);
            log.warn("今日汇总邮件发送失败 userId={}", recipientUserId, e);
        }
    }
}
