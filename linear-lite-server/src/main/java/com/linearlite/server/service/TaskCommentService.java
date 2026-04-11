package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.CreateTaskCommentRequest;
import com.linearlite.server.dto.NotificationCreatedEventPayload;
import com.linearlite.server.dto.TaskCommentResponse;
import com.linearlite.server.entity.CommentMention;
import com.linearlite.server.entity.InAppNotification;
import com.linearlite.server.entity.ProjectMember;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskComment;
import com.linearlite.server.entity.User;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.exception.UnprocessableEntityException;
import com.linearlite.server.mapper.CommentMentionMapper;
import com.linearlite.server.mapper.InAppNotificationMapper;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.TaskCommentMapper;
import com.linearlite.server.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TaskCommentService {

    public static final long DELETE_WINDOW_SECONDS = 180;

    private final TaskPermissionGuard taskPermissionGuard;
    private final TaskCommentMapper taskCommentMapper;
    private final CommentMentionMapper commentMentionMapper;
    private final InAppNotificationMapper inAppNotificationMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final UserMapper userMapper;
    private final NotificationSseBroadcaster notificationSseBroadcaster;

    public TaskCommentService(
            TaskPermissionGuard taskPermissionGuard,
            TaskCommentMapper taskCommentMapper,
            CommentMentionMapper commentMentionMapper,
            InAppNotificationMapper inAppNotificationMapper,
            ProjectMemberMapper projectMemberMapper,
            UserMapper userMapper,
            NotificationSseBroadcaster notificationSseBroadcaster) {
        this.taskPermissionGuard = taskPermissionGuard;
        this.taskCommentMapper = taskCommentMapper;
        this.commentMentionMapper = commentMentionMapper;
        this.inAppNotificationMapper = inAppNotificationMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.userMapper = userMapper;
        this.notificationSseBroadcaster = notificationSseBroadcaster;
    }

    public List<TaskCommentResponse> listByTaskKey(String taskKey, Long currentUserId) {
        Task task = requireTaskForMember(taskKey, currentUserId);
        List<TaskComment> rows = taskCommentMapper.selectList(
                new LambdaQueryWrapper<TaskComment>()
                        .eq(TaskComment::getTaskId, task.getId())
                        .orderByAsc(TaskComment::getCreatedAt, TaskComment::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> authorIds = rows.stream().map(TaskComment::getAuthorId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, String> namesById = loadUsernames(authorIds);
        LocalDateTime now = LocalDateTime.now();
        List<TaskCommentResponse> out = new ArrayList<>();
        for (TaskComment c : rows) {
            out.add(toResponse(c, currentUserId, namesById.getOrDefault(c.getAuthorId(), "?"), now));
        }
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public TaskCommentResponse create(String taskKey, Long userId, CreateTaskCommentRequest req) {
        Task task = requireTaskForMember(taskKey, userId);
        String body = req.getBody();
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("评论内容不能为空");
        }
        List<Long> mentionIds = req.getMentionedUserIds() == null
                ? List.of()
                : req.getMentionedUserIds().stream().filter(Objects::nonNull).distinct().toList();
        Set<Long> projectMemberIds = loadProjectMemberIds(task.getProjectId(), mentionIds);
        for (Long mid : mentionIds) {
            if (!projectMemberIds.contains(mid)) {
                throw new UnprocessableEntityException("@ 的用户不是项目成员: " + mid);
            }
        }
        LocalDateTime now = LocalDateTime.now();
        TaskComment c = new TaskComment();
        Long parentId = req.getParentId();
        Long rootId = req.getRootId() != null ? req.getRootId() : parentId;
        int depth = req.getDepth() > 0 ? req.getDepth() : (parentId != null ? 1 : 0);
        c.setTaskId(task.getId());
        c.setAuthorId(userId);
        c.setBody(body.trim());
        c.setParentId(parentId);
        c.setRootId(rootId);
        c.setDepth(depth);
        c.setCreatedAt(now);
        taskCommentMapper.insert(c);
        for (Long mid : mentionIds) {
            CommentMention m = new CommentMention();
            m.setCommentId(c.getId());
            m.setMentionedUserId(mid);
            commentMentionMapper.insert(m);
        }
        User author = userMapper.selectById(userId);
        String authorName = author != null ? author.getUsername() : "?";
        String summary = summarize(body);
        String tk = task.getTaskKey();
        for (Long mid : mentionIds) {
            if (mid.equals(userId)) {
                continue;
            }
            InAppNotification n = new InAppNotification();
            n.setUserId(mid);
            n.setType("mention");
            n.setTaskId(task.getId());
            n.setCommentId(c.getId());
            n.setSummary(summary);
            n.setCreatedAt(now);
            inAppNotificationMapper.insert(n);
            NotificationCreatedEventPayload p = new NotificationCreatedEventPayload();
            p.setNotificationId(n.getId());
            p.setType("mention");
            p.setTaskKey(tk);
            p.setCommentId(c.getId());
            p.setCreatedAt(now);
            notificationSseBroadcaster.sendToUser(mid, "notification", p);
        }
        return toResponse(c, userId, authorName, now);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String taskKey, Long commentId, Long currentUserId) {
        Task task = requireTaskForMember(taskKey, currentUserId);
        TaskComment c = taskCommentMapper.selectById(commentId);
        if (c == null || !c.getTaskId().equals(task.getId())) {
            throw new ResourceNotFoundException("评论不存在");
        }
        if (!c.getAuthorId().equals(currentUserId)) {
            throw new ForbiddenOperationException("只能删除自己的评论");
        }
        long seconds = ChronoUnit.SECONDS.between(c.getCreatedAt(), LocalDateTime.now());
        if (seconds > DELETE_WINDOW_SECONDS) {
            throw new ConflictOperationException("评论已超过 3 分钟，不能删除");
        }
        inAppNotificationMapper.delete(
                new LambdaQueryWrapper<InAppNotification>().eq(InAppNotification::getCommentId, commentId));
        commentMentionMapper.delete(
                new LambdaQueryWrapper<CommentMention>().eq(CommentMention::getCommentId, commentId));
        taskCommentMapper.deleteById(commentId);
    }

    private Task requireTaskForMember(String taskKey, Long userId) {
        return taskPermissionGuard.requireTaskAccessByKey(taskKey, userId);
    }

    private Set<Long> loadProjectMemberIds(Long projectId, List<Long> targetUserIds) {
        if (targetUserIds == null || targetUserIds.isEmpty()) {
            return Set.of();
        }
        List<ProjectMember> rows = projectMemberMapper.selectList(
                new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, projectId)
                        .in(ProjectMember::getUserId, targetUserIds));
        return rows.stream().map(ProjectMember::getUserId).collect(Collectors.toSet());
    }

    private Map<Long, String> loadUsernames(Set<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<User> users = userMapper.selectBatchIds(ids);
        return users.stream().collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
    }

    private TaskCommentResponse toResponse(TaskComment c, Long currentUserId, String authorName, LocalDateTime now) {
        TaskCommentResponse r = new TaskCommentResponse();
        r.setId(c.getId());
        r.setAuthorId(c.getAuthorId());
        r.setAuthorName(authorName);
        r.setBody(c.getBody());
        r.setParentId(c.getParentId());
        r.setRootId(c.getRootId());
        r.setDepth(c.getDepth());
        r.setCreatedAt(c.getCreatedAt());
        long seconds = ChronoUnit.SECONDS.between(c.getCreatedAt(), now);
        r.setDeletable(c.getAuthorId().equals(currentUserId) && seconds <= DELETE_WINDOW_SECONDS);
        return r;
    }

    private static String summarize(String body) {
        if (body == null) {
            return "";
        }
        String s = body.replaceAll("#+\\s*", "")
                .replaceAll("[*_`]+", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (s.length() > 200) {
            return s.substring(0, 200) + "…";
        }
        return s;
    }
}
