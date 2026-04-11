package com.linearlite.server.service;

import com.linearlite.server.dto.TaskImportRequest;
import com.linearlite.server.dto.TaskImportResponse;
import org.springframework.stereotype.Service;

@Service
public class TaskImportService {

    private final TaskService taskService;

    public TaskImportService(TaskService taskService) {
        this.taskService = taskService;
    }

    public TaskImportResponse importTasks(TaskImportRequest request, Long creatorId) {
        return taskService.importTasks(request, creatorId);
    }
}
