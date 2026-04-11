package com.linearlite.server.service;

import com.linearlite.server.entity.Task;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TaskQueryService {

    private final TaskService taskService;

    public TaskQueryService(TaskService taskService) {
        this.taskService = taskService;
    }

    public List<Task> listByProjectId(Long projectId, Boolean topLevelOnly, Long parentId, Long userId) {
        return taskService.listByProjectId(projectId, topLevelOnly, parentId, userId);
    }

    public List<Task> listFavorites(Long userId) {
        return taskService.listFavorites(userId);
    }

    public Task getByKeyOrThrow(String taskKey, Long userId) {
        return taskService.getByKeyOrThrow(taskKey, userId);
    }
}
