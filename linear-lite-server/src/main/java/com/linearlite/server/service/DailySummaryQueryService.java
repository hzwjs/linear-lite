package com.linearlite.server.service;

import com.linearlite.server.dto.DailySummaryTaskDto;
import com.linearlite.server.mapper.TaskMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class DailySummaryQueryService {

    private final TaskMapper taskMapper;

    public DailySummaryQueryService(TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    public List<DailySummaryTaskDto> findDueTasks(List<Long> projectIds, LocalDateTime startOfToday, LocalDateTime endOfToday) {
        List<DailySummaryTaskDto> tasks = taskMapper.selectDueForDigest(projectIds, startOfToday, endOfToday);
        for (DailySummaryTaskDto task : tasks) {
            boolean completedToday = "done".equalsIgnoreCase(task.getStatus())
                    && task.getCompletedAt() != null
                    && !task.getCompletedAt().isBefore(startOfToday)
                    && task.getCompletedAt().isBefore(endOfToday);
            task.setOverdue(!completedToday && task.getDueDate() != null && task.getDueDate().isBefore(startOfToday));
        }
        return tasks;
    }
}
