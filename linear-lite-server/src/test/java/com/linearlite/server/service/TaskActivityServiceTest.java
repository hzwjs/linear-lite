package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linearlite.server.dto.TaskActivityResponse;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskActivity;
import com.linearlite.server.entity.User;
import com.linearlite.server.mapper.TaskActivityMapper;
import com.linearlite.server.mapper.TaskMapper;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskActivityServiceTest {

    @Mock
    private TaskActivityMapper taskActivityMapper;
    @Mock
    private TaskMapper taskMapper;
    @Mock
    private UserMapper userMapper;

    private TaskActivityService taskActivityService;

    @BeforeEach
    void setUp() {
        taskActivityService = new TaskActivityService(taskActivityMapper, taskMapper, userMapper);
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

        when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);
        Page<TaskActivity> page = new Page<>(1, 50);
        page.setRecords(List.of(newer, older));
        when(taskActivityMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);
        when(userMapper.selectBatchIds(List.of(2L))).thenReturn(List.of(actor));

        List<TaskActivityResponse> result = taskActivityService.listByTaskKey("ENG-5", 50);

        assertEquals(2, result.size());
        assertEquals("alice", result.get(0).getActorName());
        assertEquals("favorited", result.get(0).getActionType());
        assertEquals(LocalDateTime.of(2026, 3, 14, 10, 5), result.get(0).getCreatedAt());
        assertEquals("changed", result.get(1).getActionType());
        assertEquals("status", result.get(1).getFieldName());
        assertEquals(LocalDateTime.of(2026, 3, 14, 10, 0), result.get(1).getCreatedAt());
    }

    @Test
    void recordDescriptionChangeInsertsWhenNoRecentActivity() {
        when(taskActivityMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(new Page<TaskActivity>(1, 1).setRecords(List.of()));

        taskActivityService.recordDescriptionChange(1L, 2L, "old", "new");

        ArgumentCaptor<TaskActivity> captor = ArgumentCaptor.forClass(TaskActivity.class);
        verify(taskActivityMapper).insert(captor.capture());
        assertEquals("changed", captor.getValue().getActionType());
        assertEquals("description", captor.getValue().getFieldName());
        assertEquals("old", captor.getValue().getOldValue());
        assertEquals("new", captor.getValue().getNewValue());
    }

    @Test
    void recordDescriptionChangeUpdatesWhenRecentActivityExists() {
        TaskActivity recent = new TaskActivity();
        recent.setId(99L);
        recent.setTaskId(1L);
        recent.setUserId(2L);
        recent.setActionType("changed");
        recent.setFieldName("description");
        recent.setOldValue("old");
        recent.setNewValue("mid");
        recent.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        Page<TaskActivity> page = new Page<>(1, 1);
        page.setRecords(List.of(recent));
        when(taskActivityMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);

        taskActivityService.recordDescriptionChange(1L, 2L, "mid", "new");

        verify(taskActivityMapper).updateById(recent);
        assertEquals("new", recent.getNewValue());
        verify(taskActivityMapper, never()).insert(any());
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
