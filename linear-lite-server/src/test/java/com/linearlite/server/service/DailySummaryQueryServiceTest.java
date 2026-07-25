package com.linearlite.server.service;

import com.linearlite.server.dto.DailySummaryTaskDto;
import com.linearlite.server.mapper.TaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailySummaryQueryServiceTest {

    @Mock
    private TaskMapper taskMapper;

    private DailySummaryQueryService service;

    @BeforeEach
    void setUp() {
        service = new DailySummaryQueryService(taskMapper);
    }

    @Test
    void findDueTasksPreservesProgressPercentAndClassifiesTodayAndOverdue() {
        LocalDateTime startOfToday = LocalDate.of(2026, 7, 24).atStartOfDay();
        LocalDateTime endOfToday = LocalDate.of(2026, 7, 25).atStartOfDay();

        DailySummaryTaskDto today = new DailySummaryTaskDto();
        today.setTaskId(1L);
        today.setTaskKey("ENG-1");
        today.setTitle("今天到期");
        today.setProjectId(10L);
        today.setAssigneeId(7L);
        today.setAssigneeEmail("a@example.com");
        today.setDueDate(LocalDateTime.of(2026, 7, 24, 18, 0));
        today.setProgressPercent(65);

        DailySummaryTaskDto overdue = new DailySummaryTaskDto();
        overdue.setTaskId(2L);
        overdue.setTaskKey("ENG-2");
        overdue.setTitle("已逾期");
        overdue.setProjectId(10L);
        overdue.setAssigneeId(7L);
        overdue.setAssigneeEmail("a@example.com");
        overdue.setDueDate(LocalDateTime.of(2026, 7, 20, 12, 0));
        overdue.setProgressPercent(null);

        DailySummaryTaskDto completed = new DailySummaryTaskDto();
        completed.setTaskId(3L);
        completed.setTaskKey("ENG-3");
        completed.setTitle("当天完成");
        completed.setProjectId(10L);
        completed.setAssigneeId(7L);
        completed.setStatus("done");
        completed.setCompletedAt(LocalDateTime.of(2026, 7, 24, 16, 0));

        when(taskMapper.selectDueForDigest(anyList(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(today, overdue, completed));

        List<DailySummaryTaskDto> result = service.findDueTasks(List.of(10L), startOfToday, endOfToday);

        assertEquals(3, result.size());
        assertEquals(65, result.stream().filter(t -> t.getTaskId().equals(1L)).findFirst().orElseThrow().getProgressPercent());
        assertEquals(null, result.stream().filter(t -> t.getTaskId().equals(2L)).findFirst().orElseThrow().getProgressPercent());
        assertTrue(result.stream().anyMatch(t -> t.getTaskId().equals(1L) && !t.getOverdue()));
        assertTrue(result.stream().anyMatch(t -> t.getTaskId().equals(2L) && t.getOverdue()));
        assertTrue(result.stream().anyMatch(t -> t.getTaskId().equals(3L) && !t.getOverdue()));
    }
}
