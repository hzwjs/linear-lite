package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.entity.ProjectGitLabRepository;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskComment;
import com.linearlite.server.exception.UnauthorizedException;
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

    @Mock private GitLabProjectRepositoryService repositoryService;
    @Mock private TaskMapper taskMapper;
    @Mock private TaskCommentMapper taskCommentMapper;
    private GitLabWebhookService webhookService;

    @BeforeEach
    void setUp() {
        webhookService = new GitLabWebhookService(repositoryService, taskMapper, taskCommentMapper, new ObjectMapper());
    }

    @Test
    void twoRepositoriesOfOneProjectKeepCommitIdempotencyIndependent() {
        ProjectGitLabRepository api = repository(1L, "https://gitlab.example.com/jlnx/api", "jlnx/api");
        ProjectGitLabRepository web = repository(2L, "https://gitlab.example.com/jlnx/web", "jlnx/web");
        Task task = task();
        when(repositoryService.resolveWebhookToken("api-token")).thenReturn(api);
        when(repositoryService.resolveWebhookToken("web-token")).thenReturn(web);
        when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(task);
        when(taskCommentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        webhookService.handlePush("api-token", pushPayload("jlnx/api", "https://gitlab.example.com/jlnx/api", "JLNX-12"));
        webhookService.handlePush("web-token", pushPayload("jlnx/web", "https://gitlab.example.com/jlnx/web", "JLNX-12"));

        ArgumentCaptor<TaskComment> comments = ArgumentCaptor.forClass(TaskComment.class);
        verify(taskCommentMapper, org.mockito.Mockito.times(2)).insert(comments.capture());
        assertEquals(List.of("1:0123456789abcdef", "2:0123456789abcdef"),
                comments.getAllValues().stream().map(TaskComment::getExternalRef).toList());
        assertTrue(comments.getAllValues().get(0).getBody().contains("[01234567](https://gitlab.example.com/jlnx/api/-/commit/0123456789abcdef)"));
    }

    @Test
    void repositoryIdentityMismatchIsIgnored() {
        ProjectGitLabRepository repository = repository(1L, "https://gitlab.example.com/jlnx/api", "jlnx/api");
        when(repositoryService.resolveWebhookToken("token")).thenReturn(repository);

        webhookService.handlePush("token", pushPayload("jlnx/api", "https://other.example.com/jlnx/api", "JLNX-12"));

        verify(taskMapper, never()).selectOne(any(LambdaQueryWrapper.class));
        verify(taskCommentMapper, never()).insert(any());
    }

    @Test
    void taskFromAnotherProjectIsIgnored() {
        ProjectGitLabRepository repository = repository(1L, "https://gitlab.example.com/jlnx/api", "jlnx/api");
        Task otherProjectTask = task();
        otherProjectTask.setProjectId(99L);
        when(repositoryService.resolveWebhookToken("token")).thenReturn(repository);
        when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(otherProjectTask);

        webhookService.handlePush("token", pushPayload("jlnx/api", "https://gitlab.example.com/jlnx/api", "JLNX-12"));

        verify(taskCommentMapper, never()).insert(any());
    }

    @Test
    void invalidTokenIsRejected() {
        when(repositoryService.resolveWebhookToken("bad-token")).thenReturn(null);

        assertThrows(UnauthorizedException.class, () -> webhookService.handlePush("bad-token", "{}"));
    }

    private static ProjectGitLabRepository repository(Long id, String url, String path) {
        ProjectGitLabRepository repository = new ProjectGitLabRepository();
        repository.setId(id);
        repository.setProjectId(10L);
        repository.setRepositoryUrl(url);
        repository.setRepositoryPath(path);
        repository.setCreatedBy(7L);
        return repository;
    }

    private static Task task() {
        Task task = new Task();
        task.setId(20L);
        task.setProjectId(10L);
        task.setTaskKey("JLNX-12");
        return task;
    }

    private static String pushPayload(String path, String webUrl, String taskKey) {
        return "{\"object_kind\":\"push\",\"ref\":\"refs/heads/main\",\"project\":{\"path_with_namespace\":\"" + path
                + "\",\"web_url\":\"" + webUrl + "\"},\"commits\":[{\"id\":\"0123456789abcdef\",\"title\":\"feat: " + taskKey
                + "\",\"message\":\"feat: " + taskKey + "\",\"author\":{\"name\":\"张三\"}}]}";
    }
}
