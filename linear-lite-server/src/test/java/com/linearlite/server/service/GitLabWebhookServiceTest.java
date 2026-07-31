package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.entity.ProjectCodexBinding;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskComment;
import com.linearlite.server.entity.User;
import com.linearlite.server.exception.UnauthorizedException;
import com.linearlite.server.mapper.ProjectCodexBindingMapper;
import com.linearlite.server.mapper.TaskCommentMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitLabWebhookServiceTest {

    private static final String VALID_TOKEN = "valid-token";
    private static final String TOKEN_HASH = "OXoqnFv14szsOMJZa2grsb0F/m5OzqbBDPQnVf8iVAM=";

    @Mock
    private ProjectCodexBindingMapper bindingMapper;
    @Mock
    private TaskMapper taskMapper;
    @Mock
    private TaskCommentMapper taskCommentMapper;
    @Mock
    private CodexDispatchService codexDispatchService;
    private GitLabWebhookService webhookService;

    @BeforeEach
    void setUp() {
        webhookService = new GitLabWebhookService(
                bindingMapper, taskMapper, taskCommentMapper, new ObjectMapper(), codexDispatchService);
    }

    private ProjectCodexBinding binding(Long repositoryId) {
        ProjectCodexBinding binding = new ProjectCodexBinding();
        binding.setId(1L);
        binding.setProjectId(10L);
        binding.setRepositoryId(repositoryId);
        binding.setWebhookTokenHash(TOKEN_HASH);
        binding.setWebhookPath("group/repo");
        binding.setWebhookBaseUrl("https://gitlab.example.com/group/repo");
        return binding;
    }

    private String pushPayload(String message) {
        return "{\"object_kind\":\"push\",\"ref\":\"refs/heads/main\",\"project\":{\"path_with_namespace\":\"group/repo\",\"web_url\":\"https://gitlab.example.com/group/repo\"},"
                + "\"commits\":[{\"id\":\"0123456789abcdef\",\"title\":\"" + message + "\",\"message\":\"" + message + "\",\"author\":{\"name\":\"张三\"}}]}";
    }

    @Test
    void invalidTokenRejectsWith401() {
        when(bindingMapper.selectList(null)).thenReturn(List.of(binding(100L)));
        assertThrows(UnauthorizedException.class, () -> webhookService.handlePush("wrong-token", pushPayload("feat: 支持 ENG-1")));
    }

    @Test
    void commitReferencingBoundTaskCreatesComment() {
        when(bindingMapper.selectList(null)).thenReturn(List.of(binding(100L)));
        Task task = new Task();
        task.setId(7L);
        task.setProjectId(10L);
        task.setTaskKey("ENG-1");
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(bindingMapper.selectOne(any())).thenReturn(binding(100L));
        when(taskCommentMapper.selectCount(any())).thenReturn(0L);
        User codex = new User();
        codex.setId(99L);
        when(codexDispatchService.requireCodexUser()).thenReturn(codex);

        webhookService.handlePush(VALID_TOKEN, pushPayload("feat: 支持 ENG-1 导出"));

        ArgumentCaptor<TaskComment> captor = ArgumentCaptor.forClass(TaskComment.class);
        verify(taskCommentMapper).insert(captor.capture());
        TaskComment comment = captor.getValue();
        assertEquals(7L, comment.getTaskId());
        assertEquals(99L, comment.getAuthorId());
        assertEquals("gitlab_commit", comment.getSourceType());
        assertEquals("0123456789abcdef", comment.getExternalRef());
        assertTrue(comment.getBody().contains("**GitLab 提交**"));
        assertTrue(comment.getBody().contains("[01234567](https://gitlab.example.com/group/repo/-/commit/0123456789abcdef)"));
        assertTrue(comment.getBody().contains("feat: 支持 ENG-1 导出"));
        assertTrue(comment.getBody().contains("作者：张三"));
        assertTrue(comment.getBody().contains("分支：main"));
    }

    @Test
    void firstEventBackfillsWebhookPathAndBaseUrl() {
        ProjectCodexBinding fresh = binding(100L);
        fresh.setWebhookPath(null);
        fresh.setWebhookBaseUrl(null);
        when(bindingMapper.selectList(null)).thenReturn(List.of(fresh));
        Task task = new Task();
        task.setId(7L);
        task.setProjectId(10L);
        task.setTaskKey("ENG-1");
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(bindingMapper.selectOne(any())).thenReturn(binding(100L));
        when(taskCommentMapper.selectCount(any())).thenReturn(0L);
        when(codexDispatchService.requireCodexUser()).thenReturn(new User());

        webhookService.handlePush(VALID_TOKEN, pushPayload("fix(ENG-1): 修复统计口径"));

        verify(bindingMapper).updateById(fresh);
        assertEquals("group/repo", fresh.getWebhookPath());
        assertEquals("https://gitlab.example.com/group/repo", fresh.getWebhookBaseUrl());
    }

    @Test
    void duplicatePushSkipsComment() {
        when(bindingMapper.selectList(null)).thenReturn(List.of(binding(100L)));
        Task task = new Task();
        task.setId(7L);
        task.setProjectId(10L);
        task.setTaskKey("ENG-1");
        when(taskMapper.selectOne(any())).thenReturn(task);
        when(bindingMapper.selectOne(any())).thenReturn(binding(100L));
        when(taskCommentMapper.selectCount(any())).thenReturn(1L);

        webhookService.handlePush(VALID_TOKEN, pushPayload("feat: 支持 ENG-1 导出"));

        verify(taskCommentMapper, never()).insert(any());
    }

    @Test
    void commitWithoutTaskKeyIsIgnored() {
        when(bindingMapper.selectList(null)).thenReturn(List.of(binding(100L)));
        webhookService.handlePush(VALID_TOKEN, pushPayload("chore: 更新依赖"));
        verify(taskCommentMapper, never()).insert(any());
    }

    @Test
    void taskBoundToAnotherRepositoryIsIgnored() {
        when(bindingMapper.selectList(null)).thenReturn(List.of(binding(100L)));
        Task task = new Task();
        task.setId(7L);
        task.setProjectId(10L);
        task.setTaskKey("ENG-1");
        when(taskMapper.selectOne(any())).thenReturn(task);
        ProjectCodexBinding otherRepoBinding = binding(200L);
        otherRepoBinding.setId(2L);
        when(bindingMapper.selectOne(any())).thenReturn(otherRepoBinding);

        webhookService.handlePush(VALID_TOKEN, pushPayload("feat: 支持 ENG-1 导出"));

        verify(taskCommentMapper, never()).insert(any());
    }

    @Test
    void nonPushEventIsIgnored() {
        when(bindingMapper.selectList(null)).thenReturn(List.of(binding(100L)));
        String payload = "{\"object_kind\":\"note\"}";
        webhookService.handlePush(VALID_TOKEN, payload);
        verify(taskCommentMapper, never()).insert(any());
    }
}
