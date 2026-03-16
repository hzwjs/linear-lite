package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.Project;
import com.linearlite.server.entity.Task;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.ProjectMapper;
import com.linearlite.server.mapper.TaskActivityMapper;
import com.linearlite.server.mapper.TaskFavoriteMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private TaskMapper taskMapper;
    @Mock
    private TaskFavoriteMapper taskFavoriteMapper;
    @Mock
    private TaskActivityMapper taskActivityMapper;

    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        projectService = new ProjectService(projectMapper, taskMapper, taskFavoriteMapper, taskActivityMapper);
    }

    @Test
    void deleteRemovesOwnedProjectAndRelatedTasks() {
        Project project = new Project();
        project.setId(3L);
        project.setCreatorId(7L);

        Task task = new Task();
        task.setId(11L);
        task.setProjectId(3L);

        when(projectMapper.selectById(3L)).thenReturn(project);
        when(taskMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(task));

        projectService.delete(3L, 7L);

        verify(taskActivityMapper).delete(any(LambdaQueryWrapper.class));
        verify(taskFavoriteMapper).delete(any(LambdaQueryWrapper.class));
        verify(taskMapper).delete(any(LambdaQueryWrapper.class));
        verify(projectMapper).deleteById(3L);
    }

    @Test
    void deleteRejectsNonOwner() {
        Project project = new Project();
        project.setId(3L);
        project.setCreatorId(7L);

        when(projectMapper.selectById(3L)).thenReturn(project);

        assertThrows(ForbiddenOperationException.class, () -> projectService.delete(3L, 8L));
    }
}
