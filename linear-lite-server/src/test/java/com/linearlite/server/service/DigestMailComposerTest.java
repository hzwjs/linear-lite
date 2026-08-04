package com.linearlite.server.service;

import com.linearlite.server.dto.DailySummaryTaskDto;
import com.linearlite.server.dto.DigestMailContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
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
    void composesTranslatedStatusProgressAndLinks() {
        DailySummaryTaskDto task = new DailySummaryTaskDto();
        task.setTaskId(1L);
        task.setTaskKey("ENG-1");
        task.setTitle("修复登录");
        task.setStatus("in_progress");
        task.setPriority("high");
        task.setProjectId(10L);
        task.setProjectName("Engineering");
        task.setProgressPercent(65);
        task.setDueDate(LocalDateTime.of(2026, 7, 24, 18, 0));
        task.setOverdue(false);

        DigestMailContent content = composer.compose("alice", LocalDate.of(2026, 7, 24),
                LocalDateTime.of(2026, 7, 23, 16, 30), LocalDateTime.of(2026, 7, 24, 16, 30), List.of(task));

        assertTrue(content.getSubject().contains("2026-07-24"));
        assertTrue(content.getHtmlBody().contains("修复登录"));
        assertTrue(content.getHtmlBody().contains("2026-07-24"));
        assertTrue(content.getHtmlBody().contains("进行中"));
        assertTrue(content.getHtmlBody().contains("项目 Engineering"));
        assertTrue(content.getHtmlBody().contains("进度 65%"));
        assertTrue(content.getHtmlBody().contains("href=\"https://app.example.com/\""));
        assertTrue(content.getHtmlBody().contains("https://app.example.com/projects/10/tasks/ENG-1"));
        assertTrue(content.getTextBody().contains("ENG-1"));
        assertTrue(content.getTextBody().contains("2026-07-24"));
        assertTrue(content.getTextBody().contains("状态 进行中"));
        assertTrue(content.getTextBody().contains("进度 65%"));
        assertTrue(content.getTextBody().contains("https://app.example.com/projects/10/tasks/ENG-1"));
        assertEquals(1, content.getTaskCount());
    }

    @Test
    void degradesSafelyWhenBaseUrlIsBlankAndProgressIsMissing() {
        DigestMailComposer blankBaseUrlComposer = new DigestMailComposer("");

        DailySummaryTaskDto task = new DailySummaryTaskDto();
        task.setTaskId(1L);
        task.setTaskKey("ENG-1");
        task.setTitle("修复登录");
        task.setStatus(null);
        task.setProjectId(10L);
        task.setProjectName("Engineering");
        task.setProgressPercent(null);
        task.setDueDate(LocalDateTime.of(2026, 7, 24, 18, 0));
        task.setOverdue(false);

        DigestMailContent content = blankBaseUrlComposer.compose("alice", LocalDate.of(2026, 7, 24),
                LocalDateTime.of(2026, 7, 23, 16, 30), LocalDateTime.of(2026, 7, 24, 16, 30), List.of(task));

        assertTrue(content.getHtmlBody().contains("状态 未设置"));
        assertTrue(content.getHtmlBody().contains("进度 --"));
        assertTrue(content.getHtmlBody().contains("请联系管理员配置访问地址"));
        assertTrue(!content.getHtmlBody().contains("href=\"/\""));
        assertTrue(content.getTextBody().contains("打开 Linear Lite：请联系管理员配置访问地址"));
    }

    @Test
    void htmlEscapesTaskTitle() {
        DailySummaryTaskDto task = new DailySummaryTaskDto();
        task.setTaskId(1L);
        task.setTaskKey("ENG-1");
        task.setTitle("<script>alert(1)</script>");
        task.setProjectId(10L);
        task.setProjectName("Engineering");
        task.setProgressPercent(20);
        task.setDueDate(LocalDateTime.of(2026, 7, 24, 18, 0));
        task.setOverdue(false);

        DigestMailContent content = composer.compose("alice", LocalDate.of(2026, 7, 24),
                LocalDateTime.of(2026, 7, 23, 16, 30), LocalDateTime.of(2026, 7, 24, 16, 30), List.of(task));

        assertTrue(content.getHtmlBody().contains("&lt;script&gt;"));
        assertTrue(!content.getHtmlBody().contains("<script>alert"));
    }

    @Test
    void includesTasksCompletedOnBusinessDateInSummary() {
        DailySummaryTaskDto task = new DailySummaryTaskDto();
        task.setTaskKey("ENG-2");
        task.setTitle("当天完成的任务");
        task.setStatus("done");
        task.setProjectId(10L);
        task.setProjectName("Engineering");
        task.setCompletedAt(LocalDateTime.of(2026, 7, 24, 15, 30));
        task.setProgressPercent(100);

        DigestMailContent content = composer.compose("alice", LocalDate.of(2026, 7, 24),
                LocalDateTime.of(2026, 7, 23, 16, 30), LocalDateTime.of(2026, 7, 24, 16, 30), List.of(task));


        assertTrue(content.getHtmlBody().contains("今日完成 · 1"));
        assertTrue(content.getHtmlBody().contains("当天完成的任务"));
        assertTrue(content.getTextBody().contains("今日完成 1"));
        assertTrue(content.getTextBody().contains("今日完成 · 1"));
        assertEquals(1, content.getTaskCount());
    }

    @Test
    void includesLatePreviousDayCompletionInTheNextDigestWindow() {
        DailySummaryTaskDto task = new DailySummaryTaskDto();
        task.setTaskKey("ENG-3");
        task.setTitle("昨日晚间完成的任务");
        task.setStatus("done");
        task.setCompletedAt(LocalDateTime.of(2026, 7, 24, 23, 45));

        DigestMailContent content = composer.compose("alice", LocalDate.of(2026, 7, 25),
                LocalDateTime.of(2026, 7, 24, 16, 30), LocalDateTime.of(2026, 7, 25, 16, 30), List.of(task));

        assertTrue(content.getHtmlBody().contains("今日完成 · 1"));
        assertTrue(content.getHtmlBody().contains("昨日晚间完成的任务"));
    }
}
