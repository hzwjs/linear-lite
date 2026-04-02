package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.TaskLabelItemRequest;
import com.linearlite.server.dto.TaskLabelResponse;
import com.linearlite.server.entity.Label;
import com.linearlite.server.entity.ProjectMember;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskLabel;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.LabelMapper;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.TaskLabelMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class LabelService {

    public static final int NAME_MAX_LEN = 64;
    private static final int LIST_LABELS_LIMIT = 100;

    private final LabelMapper labelMapper;
    private final TaskLabelMapper taskLabelMapper;
    private final ProjectMemberMapper projectMemberMapper;

    public LabelService(
            LabelMapper labelMapper,
            TaskLabelMapper taskLabelMapper,
            ProjectMemberMapper projectMemberMapper) {
        this.labelMapper = labelMapper;
        this.taskLabelMapper = taskLabelMapper;
        this.projectMemberMapper = projectMemberMapper;
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

    public List<TaskLabelResponse> listForProject(long projectId, String query, Long userId) {
        requireProjectMember(projectId, userId);
        LambdaQueryWrapper<Label> w = new LambdaQueryWrapper<Label>()
                .eq(Label::getProjectId, projectId)
                .orderByAsc(Label::getName)
                .last("LIMIT " + LIST_LABELS_LIMIT);
        String q = query == null ? null : query.trim();
        if (q != null && !q.isEmpty()) {
            w.likeRight(Label::getName, q);
        }
        return labelMapper.selectList(w).stream()
                .map(l -> new TaskLabelResponse(l.getId(), l.getName()))
                .collect(Collectors.toList());
    }

    @Transactional(rollbackFor = Exception.class)
    public void replaceTaskLabels(long taskId, long projectId, List<TaskLabelItemRequest> items) {
        if (items == null) {
            return;
        }
        LinkedHashSet<Long> uniqueIds = new LinkedHashSet<>();
        for (TaskLabelItemRequest item : items) {
            if (item == null) {
                throw new IllegalArgumentException("labels 元素不能为空");
            }
            boolean hasId = item.getId() != null;
            boolean hasName = item.getName() != null && !item.getName().trim().isEmpty();
            if (hasId == hasName) {
                throw new IllegalArgumentException("labels 每项必须且仅能指定 id 或 name 之一");
            }
            long labelId;
            if (hasId) {
                Label label = labelMapper.selectById(item.getId());
                if (label == null) {
                    throw new ResourceNotFoundException("标签不存在: " + item.getId());
                }
                if (!label.getProjectId().equals(projectId)) {
                    throw new IllegalArgumentException("标签不属于当前任务所在项目");
                }
                labelId = label.getId();
            } else {
                String trimmed = validateAndTrimName(item.getName());
                Label label = findOrCreateLabel(projectId, trimmed);
                labelId = label.getId();
            }
            uniqueIds.add(labelId);
        }
        taskLabelMapper.delete(new LambdaQueryWrapper<TaskLabel>().eq(TaskLabel::getTaskId, taskId));
        for (Long labelId : uniqueIds) {
            TaskLabel row = new TaskLabel();
            row.setTaskId(taskId);
            row.setLabelId(labelId);
            taskLabelMapper.insert(row);
        }
    }

    private static String validateAndTrimName(String name) {
        String t = name.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("标签名称不能为空");
        }
        if (t.length() > NAME_MAX_LEN) {
            throw new IllegalArgumentException("标签名称最多 " + NAME_MAX_LEN + " 字符");
        }
        return t;
    }

    public Label findOrCreateLabel(long projectId, String trimmedName) {
        Label found = labelMapper.selectOne(
                new LambdaQueryWrapper<Label>()
                        .eq(Label::getProjectId, projectId)
                        .eq(Label::getName, trimmedName));
        if (found != null) {
            return found;
        }
        Label insert = new Label();
        insert.setProjectId(projectId);
        insert.setName(trimmedName);
        try {
            labelMapper.insert(insert);
            Label reloaded = labelMapper.selectById(insert.getId());
            if (reloaded != null) {
                return reloaded;
            }
        } catch (DataIntegrityViolationException ignored) {
            // 并发下另一事务已插入同 project+name
        }
        Label again = labelMapper.selectOne(
                new LambdaQueryWrapper<Label>()
                        .eq(Label::getProjectId, projectId)
                        .eq(Label::getName, trimmedName));
        if (again == null) {
            throw new IllegalStateException("标签创建失败: " + trimmedName);
        }
        return again;
    }

    public void fillLabelsForTasks(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        List<Long> taskIds = tasks.stream().map(Task::getId).filter(Objects::nonNull).distinct().toList();
        if (taskIds.isEmpty()) {
            return;
        }
        List<TaskLabel> links = taskLabelMapper.selectList(
                new LambdaQueryWrapper<TaskLabel>().in(TaskLabel::getTaskId, taskIds));
        if (links.isEmpty()) {
            for (Task t : tasks) {
                t.setLabels(Collections.emptyList());
            }
            return;
        }
        Set<Long> labelIds = links.stream().map(TaskLabel::getLabelId).collect(Collectors.toSet());
        List<Label> labels = labelMapper.selectList(
                new LambdaQueryWrapper<Label>().in(Label::getId, labelIds));
        Map<Long, Label> byId = labels.stream().collect(Collectors.toMap(Label::getId, l -> l));
        Map<Long, List<TaskLabelResponse>> byTask = new HashMap<>();
        for (TaskLabel link : links) {
            Label lab = byId.get(link.getLabelId());
            if (lab == null) {
                continue;
            }
            TaskLabelResponse resp = new TaskLabelResponse(lab.getId(), lab.getName());
            byTask.computeIfAbsent(link.getTaskId(), k -> new ArrayList<>()).add(resp);
        }
        for (Task t : tasks) {
            List<TaskLabelResponse> list = new ArrayList<>(byTask.getOrDefault(t.getId(), List.of()));
            list.sort(Comparator.comparing(TaskLabelResponse::getName, Comparator.naturalOrder()));
            t.setLabels(list);
        }
    }

    /** 按标签名排序后逗号拼接，用于活动记录对比。 */
    public String sortedNamesJoined(long taskId) {
        List<TaskLabel> links = taskLabelMapper.selectList(
                new LambdaQueryWrapper<TaskLabel>().eq(TaskLabel::getTaskId, taskId));
        if (links.isEmpty()) {
            return "";
        }
        Set<Long> labelIdSet = links.stream().map(TaskLabel::getLabelId).collect(Collectors.toSet());
        List<Label> labels = labelMapper.selectList(
                new LambdaQueryWrapper<Label>().in(Label::getId, labelIdSet));
        return labels.stream()
                .map(Label::getName)
                .sorted()
                .collect(Collectors.joining(","));
    }

    public void deleteLinksForTaskIds(List<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return;
        }
        taskLabelMapper.delete(new LambdaQueryWrapper<TaskLabel>().in(TaskLabel::getTaskId, taskIds));
    }
}
