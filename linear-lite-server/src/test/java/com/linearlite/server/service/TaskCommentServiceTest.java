package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.CreateTaskCommentRequest;
import com.linearlite.server.dto.TaskCommentResponse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskCommentServiceTest {

    @Mock
    private TaskPermissionGuard taskPermissionGuard;
    @Mock
    private TaskCommentMapper taskCommentMapper;
    @Mock
    private CommentMentionMapper commentMentionMapper;
    @Mock
    private InAppNotificationMapper inAppNotificationMapper;
    @Mock
    private ProjectMemberMapper projectMemberMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private NotificationSseBroadcaster notificationSseBroadcaster;

    private TaskCommentService taskCommentService;

    @BeforeEach
    void setUp() {
        taskCommentService = new TaskCommentService(
                taskPermissionGuard,
                taskCommentMapper,
                commentMentionMapper,
                inAppNotificationMapper,
                projectMemberMapper,
                userMapper,
                notificationSseBroadcaster);
    }

    @Test
    void createPersistsMentionsWhenMembers() {
        Task task = new Task();
        task.setId(1L);
        task.setProjectId(10L);
        task.setTaskKey("ENG-1");

        when(taskPermissionGuard.requireTaskAccessByKey("ENG-1", 5L)).thenReturn(task);
        ProjectMember mentionMember = new ProjectMember();
        mentionMember.setProjectId(10L);
        mentionMember.setUserId(7L);
        when(projectMemberMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(mentionMember));
        User author = new User();
        author.setId(5L);
        author.setUsername("alice");
        when(userMapper.selectById(5L)).thenReturn(author);
        doAnswer(invocation -> {
            TaskComment c = invocation.getArgument(0);
            c.setId(100L);
            return 1;
        }).when(taskCommentMapper).insert(any(TaskComment.class));
        doAnswer(invocation -> {
            com.linearlite.server.entity.InAppNotification n = invocation.getArgument(0);
            n.setId(200L);
            return 1;
        }).when(inAppNotificationMapper).insert(any(com.linearlite.server.entity.InAppNotification.class));

        CreateTaskCommentRequest req = new CreateTaskCommentRequest();
        req.setBody("hello **world**");
        req.setParentId(88L);
        req.setMentionedUserIds(List.of(7L));

        TaskCommentResponse res = taskCommentService.create("ENG-1", 5L, req);

        assertEquals(100L, res.getId());
        assertEquals(88L, res.getParentId());
        assertEquals(88L, res.getRootId());
        assertEquals(1, res.getDepth());
        ArgumentCaptor<TaskComment> commentCaptor = ArgumentCaptor.forClass(TaskComment.class);
        verify(taskCommentMapper).insert(commentCaptor.capture());
        TaskComment inserted = commentCaptor.getValue();
        assertEquals(88L, inserted.getParentId());
        assertEquals(88L, inserted.getRootId());
        assertEquals(1, inserted.getDepth());
        verify(commentMentionMapper).insert(any());
        verify(inAppNotificationMapper).insert(any());
        verify(notificationSseBroadcaster).sendToUser(eq(7L), eq("notification"), any());
    }

    @Test
    void createRejectsNonMemberMention() {
        Task task = new Task();
        task.setId(1L);
        task.setProjectId(10L);
        task.setTaskKey("ENG-1");
        when(taskPermissionGuard.requireTaskAccessByKey("ENG-1", 5L)).thenReturn(task);
        when(projectMemberMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        CreateTaskCommentRequest req = new CreateTaskCommentRequest();
        req.setBody("hi");
        req.setMentionedUserIds(List.of(99L));

        assertThrows(UnprocessableEntityException.class, () -> taskCommentService.create("ENG-1", 5L, req));
        verify(taskCommentMapper, never()).insert(any());
    }

    @Test
    void deleteSucceedsWithinWindowAndCleansRelatedRows() {
        Task task = new Task();
        task.setId(1L);
        task.setProjectId(10L);
        task.setTaskKey("ENG-1");
        when(taskPermissionGuard.requireTaskAccessByKey("ENG-1", 5L)).thenReturn(task);

        TaskComment c = new TaskComment();
        c.setId(50L);
        c.setTaskId(1L);
        c.setAuthorId(5L);
        c.setCreatedAt(LocalDateTime.now().minusSeconds(10));
        when(taskCommentMapper.selectById(50L)).thenReturn(c);

        taskCommentService.delete("ENG-1", 50L, 5L);

        verify(inAppNotificationMapper).delete(any(LambdaQueryWrapper.class));
        verify(commentMentionMapper).delete(any(LambdaQueryWrapper.class));
        verify(taskCommentMapper).deleteById(50L);
    }

    @Test
    void deleteNotFoundWhenCommentOnOtherTask() {
        Task task = new Task();
        task.setId(1L);
        task.setProjectId(10L);
        task.setTaskKey("ENG-1");
        when(taskPermissionGuard.requireTaskAccessByKey("ENG-1", 5L)).thenReturn(task);

        TaskComment c = new TaskComment();
        c.setId(50L);
        c.setTaskId(99L);
        c.setAuthorId(5L);
        c.setCreatedAt(LocalDateTime.now());
        when(taskCommentMapper.selectById(50L)).thenReturn(c);

        assertThrows(ResourceNotFoundException.class, () -> taskCommentService.delete("ENG-1", 50L, 5L));
        verify(taskCommentMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteRejectsAfterWindow() {
        Task task = new Task();
        task.setId(1L);
        task.setProjectId(10L);
        task.setTaskKey("ENG-1");
        when(taskPermissionGuard.requireTaskAccessByKey("ENG-1", 5L)).thenReturn(task);

        TaskComment c = new TaskComment();
        c.setId(50L);
        c.setTaskId(1L);
        c.setAuthorId(5L);
        c.setCreatedAt(LocalDateTime.now().minusSeconds(400));
        when(taskCommentMapper.selectById(50L)).thenReturn(c);

        assertThrows(ConflictOperationException.class, () -> taskCommentService.delete("ENG-1", 50L, 5L));
    }

    @Test
    void deleteForbiddenForNonAuthor() {
        Task task = new Task();
        task.setId(1L);
        task.setProjectId(10L);
        task.setTaskKey("ENG-1");
        when(taskPermissionGuard.requireTaskAccessByKey("ENG-1", 8L)).thenReturn(task);

        TaskComment c = new TaskComment();
        c.setId(50L);
        c.setTaskId(1L);
        c.setAuthorId(5L);
        c.setCreatedAt(LocalDateTime.now());
        when(taskCommentMapper.selectById(50L)).thenReturn(c);

        assertThrows(ForbiddenOperationException.class, () -> taskCommentService.delete("ENG-1", 50L, 8L));
    }

    @Test
    void listMarksDeletableWithinWindow() {
        Task task = new Task();
        task.setId(1L);
        task.setProjectId(10L);
        task.setTaskKey("ENG-1");
        when(taskPermissionGuard.requireTaskAccessByKey("ENG-1", 5L)).thenReturn(task);

        TaskComment c = new TaskComment();
        c.setId(1L);
        c.setTaskId(1L);
        c.setAuthorId(5L);
        c.setBody("x");
        c.setParentId(8L);
        c.setRootId(2L);
        c.setDepth(3);
        c.setCreatedAt(LocalDateTime.now().minusSeconds(10));
        when(taskCommentMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(c));
        User u = new User();
        u.setId(5L);
        u.setUsername("bob");
        when(userMapper.selectBatchIds(ArgumentMatchers.<java.util.Collection<Long>>any())).thenReturn(List.of(u));

        List<TaskCommentResponse> list = taskCommentService.listByTaskKey("ENG-1", 5L);
        assertEquals(1, list.size());
        assertTrue(list.get(0).isDeletable());
        assertEquals(8L, list.get(0).getParentId());
        assertEquals(2L, list.get(0).getRootId());
        assertEquals(3, list.get(0).getDepth());
    }

    @Test
    void commentThreadingFieldsHaveExpectedDefaults() {
        CreateTaskCommentRequest req = new CreateTaskCommentRequest();
        assertNull(req.getParentId());
        assertNull(req.getRootId());
        assertEquals(0, req.getDepth());

        TaskComment comment = new TaskComment();
        assertNull(comment.getParentId());
        assertNull(comment.getRootId());
        assertEquals(0, comment.getDepth());
    }

    @Test
    void commentThreadingFieldsAreReadableAndWritable() {
        CreateTaskCommentRequest req = new CreateTaskCommentRequest();
        req.setParentId(11L);
        req.setRootId(10L);
        req.setDepth(1);
        assertEquals(11L, req.getParentId());
        assertEquals(10L, req.getRootId());
        assertEquals(1, req.getDepth());

        TaskComment comment = new TaskComment();
        comment.setParentId(11L);
        comment.setRootId(10L);
        comment.setDepth(1);
        assertEquals(11L, comment.getParentId());
        assertEquals(10L, comment.getRootId());
        assertEquals(1, comment.getDepth());

        TaskCommentResponse response = new TaskCommentResponse();
        response.setParentId(11L);
        response.setRootId(10L);
        response.setDepth(1);
        assertEquals(11L, response.getParentId());
        assertEquals(10L, response.getRootId());
        assertEquals(1, response.getDepth());
    }
}
