package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.linearlite.server.dto.DailySummaryTaskDto;
import com.linearlite.server.dto.DigestMailContent;
import com.linearlite.server.entity.Project;
import com.linearlite.server.entity.ProjectEmailDispatch;
import com.linearlite.server.mapper.ProjectEmailDispatchMapper;
import com.linearlite.server.mapper.ProjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DailySummaryDispatchService {

    private static final Logger log = LoggerFactory.getLogger(DailySummaryDispatchService.class);

    private final ProjectEmailPreferenceService preferenceService;
    private final DailySummaryQueryService queryService;
    private final ProjectMapper projectMapper;
    private final DigestMailComposer composer;
    private final DigestMailSender sender;
    private final ProjectEmailDispatchMapper dispatchMapper;

    public DailySummaryDispatchService(
            ProjectEmailPreferenceService preferenceService,
            DailySummaryQueryService queryService,
            ProjectMapper projectMapper,
            DigestMailComposer composer,
            DigestMailSender sender,
            ProjectEmailDispatchMapper dispatchMapper) {
        this.preferenceService = preferenceService;
        this.queryService = queryService;
        this.projectMapper = projectMapper;
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
        List<DailySummaryTaskDto> tasks = queryService.findDueTasks(projectIds, startOfToday, endOfToday);
        if (tasks.isEmpty()) return;

        Map<Long, List<DailySummaryTaskDto>> tasksByProject = tasks.stream()
                .collect(Collectors.groupingBy(DailySummaryTaskDto::getProjectId));

        for (Long projectId : projectIds) {
            List<DailySummaryTaskDto> projectTasks = tasksByProject.get(projectId);
            if (projectTasks == null || projectTasks.isEmpty()) continue;

            Project project = projectMapper.selectById(projectId);
            if (project == null) continue;

            dispatchForProject(project, projectTasks, businessDate);
        }
    }

    private void dispatchForProject(Project project, List<DailySummaryTaskDto> tasks, LocalDate businessDate) {
        Map<Long, List<DailySummaryTaskDto>> tasksByAssignee = tasks.stream()
                .filter(t -> t.getAssigneeId() != null)
                .collect(Collectors.groupingBy(DailySummaryTaskDto::getAssigneeId));

        for (Map.Entry<Long, List<DailySummaryTaskDto>> entry : tasksByAssignee.entrySet()) {
            DailySummaryTaskDto first = entry.getValue().get(0);
            String email = first.getAssigneeEmail();
            String username = first.getAssigneeUsername();
            if (email == null || email.isBlank()) continue;

            dispatchOne(project, entry.getKey(), username, email, entry.getValue(), businessDate);
        }
    }

    private void dispatchOne(Project project, Long recipientUserId, String recipientName,
                             String recipientEmail, List<DailySummaryTaskDto> tasks, LocalDate businessDate) {
        ProjectEmailDispatch record = dispatchMapper.selectOne(
                new LambdaQueryWrapper<ProjectEmailDispatch>()
                        .eq(ProjectEmailDispatch::getProjectId, project.getId())
                        .eq(ProjectEmailDispatch::getScenarioKey,
                                ProjectEmailPreferenceService.SCENARIO_DAILY_SUMMARY)
                        .eq(ProjectEmailDispatch::getBusinessDate, businessDate)
                        .eq(ProjectEmailDispatch::getRecipientUserId, recipientUserId));
        if (record != null && "sent".equals(record.getStatus())) return;

        if (record == null) {
            record = new ProjectEmailDispatch();
            record.setProjectId(project.getId());
            record.setScenarioKey(ProjectEmailPreferenceService.SCENARIO_DAILY_SUMMARY);
            record.setBusinessDate(businessDate);
            record.setRecipientUserId(recipientUserId);
            record.setStatus("pending");
            record.setCreatedAt(LocalDateTime.now());
            record.setUpdatedAt(LocalDateTime.now());
            try {
                dispatchMapper.insert(record);
            } catch (DuplicateKeyException ignored) {
                record = dispatchMapper.selectOne(
                        new LambdaQueryWrapper<ProjectEmailDispatch>()
                                .eq(ProjectEmailDispatch::getProjectId, project.getId())
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

        DigestMailContent content;
        try {
            content = composer.compose(project, recipientName, businessDate, tasks);
            record.setSubject(content.getSubject());
            record.setTaskCount(content.getTaskCount());
        } catch (RuntimeException e) {
            record.setStatus("failed");
            String msg = e.getMessage();
            record.setLastError(msg == null ? null : msg.substring(0, Math.min(msg.length(), 1024)));
            record.setUpdatedAt(LocalDateTime.now());
            dispatchMapper.updateById(record);
            log.warn("今日汇总邮件编排失败 projectId={} userId={}", project.getId(), recipientUserId, e);
            return;
        }

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
            log.warn("今日汇总邮件发送失败 projectId={} userId={}", project.getId(), recipientUserId, e);
        }
    }
}
