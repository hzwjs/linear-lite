package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linearlite.server.dto.TaskActivityResponse;
import com.linearlite.server.dto.TaskChangeRecord;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskActivity;
import com.linearlite.server.entity.User;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.TaskActivityMapper;
import com.linearlite.server.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class TaskActivityServiceTest {

    @Mock
    private TaskActivityMapper taskActivityMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private TaskPermissionGuard taskPermissionGuard;

    private TaskActivityService taskActivityService;

    @BeforeEach
    void setUp() {
        taskActivityService = new TaskActivityService(taskActivityMapper, userMapper, taskPermissionGuard);
    }

    @Test
    void recordFieldChangePersistsStructuredActivity() {
        taskActivityService.recordFieldChange(3L, 8L, "status", "todo", "done");

        ArgumentCaptor<TaskActivity> captor = ArgumentCaptor.forClass(TaskActivity.class);
        verify(taskActivityMapper).insert(captor.capture());
        assertEquals("changed", captor.getValue().getActionType());
        assertEquals("status", captor.getValue().getFieldName());
        assertEquals("todo", captor.getValue().getOldValue());
        assertEquals("done", captor.getValue().getNewValue());
        // 不能依赖数据库 CURRENT_TIMESTAMP：本地数据库可能使用 UTC，接口的 LocalDateTime
        // 序列化后会被浏览器按本地时区解释，导致活动显示为 8 小时前。
        assertNotNull(captor.getValue().getCreatedAt());
    }

    @Test
    void recordFieldChangePersistsEachProgressUpdate() {
        taskActivityService.recordFieldChange(3L, 8L, "progressPercent", "30", "40");

        ArgumentCaptor<TaskActivity> captor = ArgumentCaptor.forClass(TaskActivity.class);
        verify(taskActivityMapper).insert(captor.capture());
        assertEquals("progressPercent", captor.getValue().getFieldName());
        assertEquals("30", captor.getValue().getOldValue());
        assertEquals("40", captor.getValue().getNewValue());
        verify(taskActivityMapper, never()).selectPage(any(Page.class), any(LambdaQueryWrapper.class));
        verify(taskActivityMapper, never()).updateById(any());
    }

    @Test
    void listByTaskKeyResolvesActorNames() {
        Task task = new Task();
        task.setId(5L);
        task.setTaskKey("ENG-5");

        TaskActivity older = new TaskActivity();
        older.setId(10L);
        older.setTaskId(5L);
        older.setUserId(2L);
        older.setActionType("changed");
        older.setFieldName("status");
        older.setOldValue("todo");
        older.setNewValue("in_progress");
        older.setCreatedAt(LocalDateTime.of(2026, 3, 14, 10, 0));

        TaskActivity newer = new TaskActivity();
        newer.setId(11L);
        newer.setTaskId(5L);
        newer.setUserId(2L);
        newer.setActionType("favorited");
        newer.setCreatedAt(LocalDateTime.of(2026, 3, 14, 10, 5));

        User actor = new User();
        actor.setId(2L);
        actor.setUsername("alice");

        when(taskPermissionGuard.requireTaskAccessByKey("ENG-5", 7L)).thenReturn(task);
        Page<TaskActivity> page = new Page<>(1, 50);
        page.setRecords(List.of(newer, older));
        when(taskActivityMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);
        when(userMapper.selectBatchIds(List.of(2L))).thenReturn(List.of(actor));

        List<TaskActivityResponse> result = taskActivityService.listByTaskKey("ENG-5", 7L, 50);

        assertEquals(2, result.size());
        assertEquals("alice", result.get(0).getActorName());
        assertEquals("favorited", result.get(0).getActionType());
        assertEquals(LocalDateTime.of(2026, 3, 14, 10, 5), result.get(0).getCreatedAt());
        assertEquals("changed", result.get(1).getActionType());
        assertEquals("status", result.get(1).getFieldName());
        assertEquals(LocalDateTime.of(2026, 3, 14, 10, 0), result.get(1).getCreatedAt());
    }

    @Test
    void listProjectTaskChangesUsesProjectRangeFieldsAndKeysetCursor() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 14, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 10, 15, 0, 0);
        TaskChangeRecord change = new TaskChangeRecord();
        change.setId(51L);
        change.setTaskId(3L);
        change.setTaskKey("ENG-3");
        change.setField("status");
        change.setOldValue("todo");
        change.setNewValue("in_progress");
        change.setChangedAt(from);
        change.setActorId(8L);
        change.setActorDisplayName("alice");
        when(taskActivityMapper.selectMcpProjectTaskChanges(
                7L, from, to, List.of("progressPercent", "status"), 25L, 4))
                .thenReturn(List.of(change));

        List<TaskChangeRecord> result = taskActivityService.listProjectTaskChangesForMcp(
                7L, from, to, List.of("progressPercent", "status"), 25L, 3, 9L);

        assertEquals(1, result.size());
        assertEquals("status", result.get(0).getField());
        assertEquals("alice", result.get(0).getActorDisplayName());
        verify(taskPermissionGuard).requireProjectMember(7L, 9L);
        verify(taskActivityMapper).selectMcpProjectTaskChanges(
                7L, from, to, List.of("progressPercent", "status"), 25L, 4);
    }

    @Test
    void listProjectTaskChangesRejectsInvalidRangesAndChecksProjectPermission() {
        LocalDateTime from = LocalDateTime.of(2026, 10, 15, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 10, 14, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> taskActivityService.listProjectTaskChangesForMcp(
                7L, from, to, List.of("status"), null, 20, 9L));
        assertThrows(IllegalArgumentException.class, () -> taskActivityService.listProjectTaskChangesForMcp(
                7L, from, from.plusDays(1), List.of("description"), null, 20, 9L));

        doThrow(new ForbiddenOperationException("not a member"))
                .when(taskPermissionGuard).requireProjectMember(7L, 9L);
        assertThrows(ForbiddenOperationException.class, () -> taskActivityService.listProjectTaskChangesForMcp(
                7L, from, from.plusDays(1), List.of("status"), null, 20, 9L));
        verifyNoInteractions(taskActivityMapper);
    }

    @Test
    void recordAssigneeChangeStoresUsernamesInsteadOfIds() {
        User oldAssignee = new User();
        oldAssignee.setId(4L);
        oldAssignee.setUsername("alice");

        User newAssignee = new User();
        newAssignee.setId(5L);
        newAssignee.setUsername("bob");

        when(userMapper.selectBatchIds(List.of(4L, 5L))).thenReturn(List.of(oldAssignee, newAssignee));

        taskActivityService.recordAssigneeChange(9L, 2L, 4L, 5L);

        ArgumentCaptor<TaskActivity> captor = ArgumentCaptor.forClass(TaskActivity.class);
        verify(taskActivityMapper).insert(captor.capture());
        assertEquals("assigneeId", captor.getValue().getFieldName());
        assertEquals("alice", captor.getValue().getOldValue());
        assertEquals("bob", captor.getValue().getNewValue());
    }
}
