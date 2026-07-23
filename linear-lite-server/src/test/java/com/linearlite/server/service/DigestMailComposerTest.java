package com.linearlite.server.service;

import com.linearlite.server.dto.DailySummaryTaskDto;
import com.linearlite.server.dto.DigestMailContent;
import com.linearlite.server.entity.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DigestMailComposerTest {

    private DigestMailComposer composer;

    @BeforeEach
    void setUp() {
        composer = new DigestMailComposer("https://app.example.com");
    }

    @Test
    void composesSubjectAndBodiesWithTaskLink() {
        Project project = new Project();
        project.setId(10L);
        project.setName("Engineering");
        project.setIdentifier("ENG");

        DailySummaryTaskDto task = new DailySummaryTaskDto();
        task.setTaskId(1L);
        task.setTaskKey("ENG-1");
        task.setTitle("修复登录");
        task.setStatus("in_progress");
        task.setPriority("high");
        task.setProjectId(10L);
        task.setDueDate(LocalDateTime.of(2026, 7, 24, 18, 0));
        task.setOverdue(false);

        DigestMailContent content = composer.compose(project, "alice", List.of(task));

        assertTrue(content.getSubject().contains("Engineering"));
        assertTrue(content.getHtmlBody().contains("修复登录"));
        assertTrue(content.getHtmlBody().contains("https://app.example.com/projects/10/tasks/ENG-1"));
        assertTrue(content.getTextBody().contains("ENG-1"));
        assertTrue(content.getTextBody().contains("https://app.example.com/projects/10/tasks/ENG-1"));
        assertEquals(1, content.getTaskCount());
    }

    @Test
    void htmlEscapesTaskTitle() {
        Project project = new Project();
        project.setId(10L);
        project.setName("Engineering");
        project.setIdentifier("ENG");

        DailySummaryTaskDto task = new DailySummaryTaskDto();
        task.setTaskId(1L);
        task.setTaskKey("ENG-1");
        task.setTitle("<script>alert(1)</script>");
        task.setProjectId(10L);
        task.setDueDate(LocalDateTime.of(2026, 7, 24, 18, 0));
        task.setOverdue(false);

        DigestMailContent content = composer.compose(project, "alice", List.of(task));

        assertTrue(content.getHtmlBody().contains("&lt;script&gt;"));
        assertTrue(!content.getHtmlBody().contains("<script>alert"));
    }
}
