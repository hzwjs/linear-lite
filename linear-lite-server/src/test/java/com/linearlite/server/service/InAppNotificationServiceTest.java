package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linearlite.server.entity.InAppNotification;
import com.linearlite.server.entity.Task;
import com.linearlite.server.dto.NotificationCreatedEventPayload;
import com.linearlite.server.dto.InAppNotificationResponse;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.InAppNotificationMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
class InAppNotificationServiceTest {

    @Mock
    private InAppNotificationMapper inAppNotificationMapper;
    @Mock
    private TaskMapper taskMapper;
    @Mock
    private NotificationSseBroadcaster notificationSseBroadcaster;

    private InAppNotificationService service;

    @BeforeEach
    void setUp() {
        service = new InAppNotificationService(inAppNotificationMapper, taskMapper, notificationSseBroadcaster);
    }

    @Test
    void markReadRejectsOtherUserNotification() {
        InAppNotification n = new InAppNotification();
        n.setId(1L);
        n.setUserId(10L);
        when(inAppNotificationMapper.selectById(1L)).thenReturn(n);

        assertThrows(ForbiddenOperationException.class, () -> service.markRead(99L, 1L));
    }

    @Test
    void markReadUpdatesWhenUnread() {
        InAppNotification n = new InAppNotification();
        n.setId(1L);
        n.setUserId(10L);
        n.setReadAt(null);
        when(inAppNotificationMapper.selectById(1L)).thenReturn(n);

        service.markRead(10L, 1L);

        verify(inAppNotificationMapper).updateById(any(InAppNotification.class));
    }

    @Test
    void countUnreadDelegatesToMapper() {
        when(inAppNotificationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(3L);
        assertEquals(3L, service.countUnread(10L));
    }

    @Test
    void assignmentPersistsForOwnerAndPushesAfterCommit() {
        Task task = assignedTask("user:10", "  任务标题  ");
        doAnswer(invocation -> {
            InAppNotification notification = invocation.getArgument(0);
            notification.setId(55L);
            return 1;
        }).when(inAppNotificationMapper).insert(any(InAppNotification.class));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.notifyTaskAssignment(task);

            ArgumentCaptor<InAppNotification> notification = ArgumentCaptor.forClass(InAppNotification.class);
            verify(inAppNotificationMapper).insert(notification.capture());
            assertEquals(10L, notification.getValue().getUserId());
            assertEquals(7L, notification.getValue().getTaskId());
            assertEquals("assignment", notification.getValue().getType());
            assertEquals("任务标题", notification.getValue().getSummary());
            assertNull(notification.getValue().getCommentId());
            assertNull(notification.getValue().getReadAt());
            verifyNoInteractions(notificationSseBroadcaster);

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            ArgumentCaptor<NotificationCreatedEventPayload> payload = ArgumentCaptor.forClass(NotificationCreatedEventPayload.class);
            verify(notificationSseBroadcaster).sendToUser(org.mockito.ArgumentMatchers.eq(10L),
                    org.mockito.ArgumentMatchers.eq("notification"), payload.capture());
            assertEquals(55L, payload.getValue().getNotificationId());
            assertEquals("assignment", payload.getValue().getType());
            assertEquals("ENG-7", payload.getValue().getTaskKey());
            assertNull(payload.getValue().getCommentId());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rolledBackAssignmentDoesNotPush() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.notifyTaskAssignment(assignedTask("user:10", "任务标题"));
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            verifyNoInteractions(notificationSseBroadcaster);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void unassignedOrExternalOwnerDoesNotReceiveInAppNotification() {
        service.notifyTaskAssignment(assignedTask(null, "任务标题"));
        service.notifyTaskAssignment(assignedTask("external:10", "任务标题"));

        verifyNoInteractions(inAppNotificationMapper, notificationSseBroadcaster);
    }

    @Test
    void assignmentWithMissingOrBlankTitleDoesNotWriteOrPush() {
        for (String title : new String[]{null, "", " \t\n"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.notifyTaskAssignment(assignedTask("user:10", title)));
        }
        verifyNoInteractions(inAppNotificationMapper, notificationSseBroadcaster);
    }

    @Test
    void listReturnsAssignmentTitleAndTaskLinkWithoutComment() {
        InAppNotification notification = new InAppNotification();
        notification.setId(55L);
        notification.setUserId(10L);
        notification.setType("assignment");
        notification.setTaskId(7L);
        notification.setSummary("任务标题");
        doAnswer(invocation -> {
            Page<InAppNotification> page = invocation.getArgument(0);
            return page.setRecords(List.of(notification));
        }).when(inAppNotificationMapper).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
        when(taskMapper.selectBatchIds(any())).thenReturn(List.of(assignedTask("user:10", "任务标题")));

        List<InAppNotificationResponse> notifications = service.list(10L, null, 20, true);

        assertEquals(1, notifications.size());
        assertEquals(55L, notifications.get(0).getId());
        assertEquals("assignment", notifications.get(0).getType());
        assertEquals("任务标题", notifications.get(0).getSummary());
        assertEquals("ENG-7", notifications.get(0).getTaskKey());
        assertNull(notifications.get(0).getCommentId());
    }

    private static Task assignedTask(String assigneeKey, String title) {
        Task task = new Task();
        task.setId(7L);
        task.setTaskKey("ENG-7");
        task.setTitle(title);
        task.setAssigneeKey(assigneeKey);
        return task;
    }
}
