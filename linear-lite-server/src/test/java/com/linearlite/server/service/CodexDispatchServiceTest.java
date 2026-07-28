package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.codex.CodexDtos;
import com.linearlite.server.entity.CodexRepository;
import com.linearlite.server.entity.CodexRun;
import com.linearlite.server.entity.CodexRunner;
import com.linearlite.server.entity.Project;
import com.linearlite.server.entity.ProjectMember;
import com.linearlite.server.entity.TaskComment;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.User;
import com.linearlite.server.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class CodexDispatchServiceTest {
    @Mock private CodexRunnerMapper runnerMapper;
    @Mock private CodexRunnerEnrollmentCodeMapper enrollmentMapper;
    @Mock private CodexRepositoryMapper repositoryMapper;
    @Mock private ProjectCodexBindingMapper bindingMapper;
    @Mock private CodexRunMapper runMapper;
    @Mock private CodexRunEventMapper eventMapper;
    @Mock private CodexRunMessageMapper messageMapper;
    @Mock private TaskMapper taskMapper;
    @Mock private TaskCommentMapper taskCommentMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private UserMapper userMapper;
    @Mock private LabelService labelService;
    @Mock private ObjectMapper objectMapper;
    @Mock private TaskStatusService taskStatusService;
    @Mock private TaskHierarchyCompletionService taskHierarchyCompletionService;
    private CodexDispatchService service;

    @BeforeEach
    void setUp() {
        if (TableInfoHelper.getTableInfo(CodexRun.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "codex-test"), CodexRun.class);
        }
        service = new CodexDispatchService(runnerMapper, enrollmentMapper, repositoryMapper, bindingMapper,
                runMapper, eventMapper, messageMapper, taskMapper, taskCommentMapper, projectMapper,
                projectMemberMapper, userMapper, labelService, objectMapper, taskStatusService,
                taskHierarchyCompletionService);
    }

    @Test
    void completedRunCreatesOneCommentAndRetryIsIdempotent() {
        CodexRun run = new CodexRun();
        run.setId("run-1");
        run.setRunnerId(7L);
        run.setTaskId(11L);
        run.setCreatedBy(3L);
        run.setStatus("running");
        when(runMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(run);
        User codex = new User();
        codex.setId(42L);
        codex.setUserType(User.TYPE_CODEX);
        when(userMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of(codex));
        CodexDtos.CompleteRequest request = new CodexDtos.CompleteRequest(
                "completed", "thread-1", "最终结果", "{\"turnId\":\"turn-1\"}", null, null);

        service.complete("run-1", 7L, request);
        service.complete("run-1", 7L, request);

        ArgumentCaptor<TaskComment> comment = ArgumentCaptor.forClass(TaskComment.class);
        verify(taskCommentMapper, times(1)).insert(comment.capture());
        assertEquals(11L, comment.getValue().getTaskId());
        assertEquals(42L, comment.getValue().getAuthorId());
        assertEquals("**Codex 执行结果**\n\n最终结果", comment.getValue().getBody());
        assertEquals(0, comment.getValue().getDepth());
        verify(runMapper, times(1)).updateById(run);
        verify(taskStatusService).updateState(
                org.mockito.ArgumentMatchers.eq(11L), org.mockito.ArgumentMatchers.eq("done"),
                org.mockito.ArgumentMatchers.eq(100), org.mockito.ArgumentMatchers.eq(42L), any());
        verify(taskHierarchyCompletionService).completeEligibleAncestors(
                org.mockito.ArgumentMatchers.eq(11L), org.mockito.ArgumentMatchers.eq(42L), any());
        assertEquals("completed", run.getStatus());
        assertEquals("thread-1", run.getCodexThreadId());
    }

    @Test
    void claimLimitsBothCandidateQueriesBeforeRowLock() {
        CodexRunner runner = new CodexRunner();
        runner.setId(7L);
        runner.setStatus("active");
        when(runnerMapper.selectById(7L)).thenReturn(runner);
        CodexRun queued = new CodexRun();
        queued.setId("run-queued");
        queued.setRunnerId(7L);
        queued.setRepositoryId(9L);
        queued.setStatus("queued");
        when(runMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null, queued);
        CodexRepository repository = new CodexRepository();
        repository.setId(9L);
        repository.setRepositoryKey("linear-lite");
        when(repositoryMapper.selectById(9L)).thenReturn(repository);

        CodexRun claimed = service.claim(7L);

        assertEquals("claimed", claimed.getStatus());
        ArgumentCaptor<LambdaQueryWrapper<CodexRun>> queries = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(runMapper, times(2)).selectOne(queries.capture());
        assertEquals(2, queries.getAllValues().size());
        for (LambdaQueryWrapper<CodexRun> query : queries.getAllValues()) {
            assertTrue(query.getCustomSqlSegment().endsWith("LIMIT 1 FOR UPDATE"), query.getCustomSqlSegment());
        }
    }

    @Test
    void saveBindingAddsTheUniqueCodexUserAsProjectMember() {
        Project project = new Project();
        project.setId(1L);
        project.setCreatorId(3L);
        CodexRunner runner = new CodexRunner();
        runner.setId(7L);
        runner.setUserId(3L);
        runner.setStatus("active");
        CodexRepository repository = new CodexRepository();
        repository.setId(9L);
        repository.setRunnerId(7L);
        User codex = new User();
        codex.setId(42L);
        codex.setUserType(User.TYPE_CODEX);
        when(projectMapper.selectById(1L)).thenReturn(project);
        when(runnerMapper.selectById(7L)).thenReturn(runner);
        when(repositoryMapper.selectById(9L)).thenReturn(repository);
        when(userMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(java.util.List.of(codex));
        when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        service.saveBinding(1L, 3L, new CodexDtos.BindingRequest(7L, 9L, "main"));

        ArgumentCaptor<ProjectMember> member = ArgumentCaptor.forClass(ProjectMember.class);
        verify(projectMemberMapper).insert(member.capture());
        assertEquals(1L, member.getValue().getProjectId());
        assertEquals(42L, member.getValue().getUserId());
        verify(bindingMapper).insert(any());
    }

    @Test
    void assigningCodexReusesExistingActiveRun() {
        Task task = new Task();
        task.setId(11L);
        task.setTaskKey("ENG-11");
        CodexRun active = new CodexRun();
        active.setId("run-active");
        active.setTaskId(11L);
        active.setStatus("queued");
        when(runMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(active);

        CodexRun result = service.dispatchAssignedTask(task, 3L);

        assertEquals("run-active", result.getId());
        verify(runMapper, never()).insert(any());
        verify(bindingMapper, never()).selectOne(any());
    }
}
