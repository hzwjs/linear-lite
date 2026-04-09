package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linearlite.server.dto.InAppNotificationResponse;
import com.linearlite.server.entity.InAppNotification;
import com.linearlite.server.entity.Task;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.InAppNotificationMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class InAppNotificationService {

    private final InAppNotificationMapper inAppNotificationMapper;
    private final TaskMapper taskMapper;

    public InAppNotificationService(InAppNotificationMapper inAppNotificationMapper, TaskMapper taskMapper) {
        this.inAppNotificationMapper = inAppNotificationMapper;
        this.taskMapper = taskMapper;
    }

    public List<InAppNotificationResponse> list(Long userId, Long beforeId, int limit, Boolean unreadOnly) {
        int capped = Math.min(Math.max(1, limit), 100);
        LambdaQueryWrapper<InAppNotification> q = new LambdaQueryWrapper<InAppNotification>()
                .eq(InAppNotification::getUserId, userId)
                .orderByDesc(InAppNotification::getId);
        if (Boolean.TRUE.equals(unreadOnly)) {
            q.isNull(InAppNotification::getReadAt);
        }
        if (beforeId != null && beforeId > 0) {
            q.lt(InAppNotification::getId, beforeId);
        }
        Page<InAppNotification> page = new Page<>(1, capped);
        List<InAppNotification> rows = inAppNotificationMapper.selectPage(page, q).getRecords();
        return toResponses(rows);
    }

    public long countUnread(Long userId) {
        Long c = inAppNotificationMapper.selectCount(
                new LambdaQueryWrapper<InAppNotification>()
                        .eq(InAppNotification::getUserId, userId)
                        .isNull(InAppNotification::getReadAt));
        return c == null ? 0 : c;
    }

    @Transactional(rollbackFor = Exception.class)
    public void markRead(Long userId, Long notificationId) {
        InAppNotification n = inAppNotificationMapper.selectById(notificationId);
        if (n == null) {
            throw new ResourceNotFoundException("通知不存在");
        }
        if (!n.getUserId().equals(userId)) {
            throw new ForbiddenOperationException("无权操作该通知");
        }
        if (n.getReadAt() == null) {
            n.setReadAt(LocalDateTime.now());
            inAppNotificationMapper.updateById(n);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void markAllRead(Long userId) {
        InAppNotification patch = new InAppNotification();
        patch.setReadAt(LocalDateTime.now());
        inAppNotificationMapper.update(
                patch,
                new LambdaQueryWrapper<InAppNotification>()
                        .eq(InAppNotification::getUserId, userId)
                        .isNull(InAppNotification::getReadAt));
    }

    private List<InAppNotificationResponse> toResponses(List<InAppNotification> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> taskIds = rows.stream().map(InAppNotification::getTaskId).collect(Collectors.toSet());
        List<Task> tasks = taskMapper.selectBatchIds(taskIds);
        Map<Long, String> taskKeyById = tasks.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Task::getId, Task::getTaskKey, (a, b) -> a));
        List<InAppNotificationResponse> out = new ArrayList<>();
        for (InAppNotification n : rows) {
            InAppNotificationResponse r = new InAppNotificationResponse();
            r.setId(n.getId());
            r.setType(n.getType());
            r.setTaskKey(taskKeyById.get(n.getTaskId()));
            r.setCommentId(n.getCommentId());
            r.setSummary(n.getSummary());
            r.setReadAt(n.getReadAt());
            r.setCreatedAt(n.getCreatedAt());
            out.add(r);
        }
        return out;
    }
}
