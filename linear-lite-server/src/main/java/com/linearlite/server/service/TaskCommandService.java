package com.linearlite.server.service;

import com.linearlite.server.dto.TaskLabelItemRequest;
import com.linearlite.server.dto.UpdateTaskRequest;
import com.linearlite.server.entity.Task;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TaskCommandService {

    private final TaskService taskService;

    public TaskCommandService(TaskService taskService) {
        this.taskService = taskService;
    }

    public Task create(Long projectId, Long creatorId, Long parentId, String title, String description,
                       String status, String priority, Long assigneeId, LocalDateTime dueDate,
                       LocalDateTime plannedStartDate, Integer progressPercent, List<TaskLabelItemRequest> labels) {
        return taskService.create(projectId, creatorId, parentId, title, description, status, priority,
                assigneeId, dueDate, plannedStartDate, progressPercent, labels);
    }

    public Task update(String taskKey, UpdateTaskRequest request, Long userId) {
        return taskService.update(taskKey, request, userId);
    }

    public Task addFavorite(String taskKey, Long userId) {
        return taskService.addFavorite(taskKey, userId);
    }

    public Task removeFavorite(String taskKey, Long userId) {
        return taskService.removeFavorite(taskKey, userId);
    }
}
