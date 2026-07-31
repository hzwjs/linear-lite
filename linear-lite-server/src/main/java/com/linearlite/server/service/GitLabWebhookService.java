package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.entity.ProjectGitLabRepository;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskComment;
import com.linearlite.server.exception.UnauthorizedException;
import com.linearlite.server.mapper.TaskCommentMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GitLab Push Webhook：按仓库配置定位项目，并将提交中的任务编号写入任务评论。 */
@Service
public class GitLabWebhookService {

    private static final Logger log = LoggerFactory.getLogger(GitLabWebhookService.class);
    private static final Pattern TASK_KEY_PATTERN = Pattern.compile("\\b[A-Z][A-Z0-9]{0,15}-\\d{1,9}\\b");
    private static final String SOURCE_GITLAB_COMMIT = "gitlab_commit";

    private final GitLabProjectRepositoryService repositoryService;
    private final TaskMapper taskMapper;
    private final TaskCommentMapper taskCommentMapper;
    private final ObjectMapper objectMapper;

    public GitLabWebhookService(GitLabProjectRepositoryService repositoryService, TaskMapper taskMapper,
                                TaskCommentMapper taskCommentMapper, ObjectMapper objectMapper) {
        this.repositoryService = repositoryService;
        this.taskMapper = taskMapper;
        this.taskCommentMapper = taskCommentMapper;
        this.objectMapper = objectMapper;
    }

    /** 无关或无效的 GitLab 事件返回成功，避免 GitLab 对同一投递反复重试。 */
    @Transactional(rollbackFor = Exception.class)
    public void handlePush(String webhookToken, String rawBody) {
        ProjectGitLabRepository repository = repositoryService.resolveWebhookToken(webhookToken);
        if (repository == null) {
            throw new UnauthorizedException("GitLab Webhook Token 无效");
        }
        GitLabPushEvent event = parse(rawBody);
        if (event == null || !"push".equals(event.objectKind()) || event.project() == null) {
            return;
        }
        GitLabProjectRepositoryService.RepositoryIdentity eventIdentity;
        try {
            eventIdentity = GitLabProjectRepositoryService.normalizeRepositoryUrl(event.project().webUrl());
        } catch (IllegalArgumentException ignored) {
            return;
        }
        // URL 和 path 均为预先配置的固定仓库身份，Webhook 不能借首条请求回填或覆盖身份。
        if (!repository.getRepositoryUrl().equals(eventIdentity.url())
                || !repository.getRepositoryPath().equals(event.project().pathWithNamespace())) {
            log.warn("[GITLAB-DIAG] 仓库身份不匹配，已忽略：repositoryId={} configuredUrl={} eventUrl={} configuredPath={} eventPath={}",
                    repository.getId(), repository.getRepositoryUrl(), eventIdentity.url(),
                    repository.getRepositoryPath(), event.project().pathWithNamespace());
            return;
        }
        if (event.commits() == null || event.commits().isEmpty()) {
            return;
        }
        for (GitLabCommit commit : event.commits()) {
            if (commit == null || blank(commit.id()) || commit.id().length() > 64) {
                continue;
            }
            Set<String> taskKeys = extractTaskKeys(commit.title(), commit.message());
            for (String taskKey : taskKeys) {
                createComment(repository, taskKey, commit, shortBranch(event.ref()));
            }
        }
    }

    private void createComment(ProjectGitLabRepository repository, String taskKey, GitLabCommit commit, String branch) {
        Task task = taskMapper.selectOne(new LambdaQueryWrapper<Task>().eq(Task::getTaskKey, taskKey));
        // 任务编号和仓库都必须属于同一项目，避免一个项目的仓库影响另一个项目的任务。
        if (task == null || !repository.getProjectId().equals(task.getProjectId())) {
            return;
        }
        String externalRef = repository.getId() + ":" + commit.id();
        Long existing = taskCommentMapper.selectCount(new LambdaQueryWrapper<TaskComment>()
                .eq(TaskComment::getSourceType, SOURCE_GITLAB_COMMIT)
                .eq(TaskComment::getExternalRef, externalRef)
                .eq(TaskComment::getTaskId, task.getId()));
        if (existing != null && existing > 0) {
            return;
        }
        TaskComment comment = new TaskComment();
        comment.setTaskId(task.getId());
        // GitLab 用户不与 Linear Lite 用户做映射，评论作者固定为该仓库配置的创建者。
        comment.setAuthorId(repository.getCreatedBy());
        comment.setBody(buildCommentBody(repository.getRepositoryUrl(), commit, branch));
        comment.setDepth(0);
        comment.setSourceType(SOURCE_GITLAB_COMMIT);
        comment.setExternalRef(externalRef);
        comment.setCreatedAt(LocalDateTime.now());
        try {
            taskCommentMapper.insert(comment);
        } catch (DuplicateKeyException ignored) {
            log.debug("GitLab 提交评论已存在，跳过：task={} ref={}", taskKey, externalRef);
        }
    }

    private static String buildCommentBody(String repositoryUrl, GitLabCommit commit, String branch) {
        String summary = blank(commit.title()) ? firstLine(commit.message()) : commit.title().trim();
        String shortSha = commit.id().length() > 8 ? commit.id().substring(0, 8) : commit.id();
        String commitUrl = repositoryUrl + "/-/commit/" + commit.id();
        String author = commit.author() == null || commit.author().name() == null ? "" : commit.author().name().trim();
        StringBuilder body = new StringBuilder("**GitLab 提交**\n\n")
                .append("[").append(shortSha).append("](").append(commitUrl).append(") ").append(summary);
        if (!author.isBlank() || !branch.isBlank()) {
            body.append("\n\n");
            if (!author.isBlank()) {
                body.append("作者：").append(author);
            }
            if (!branch.isBlank()) {
                if (!author.isBlank()) {
                    body.append(" ｜ ");
                }
                body.append("分支：").append(branch);
            }
        }
        return body.toString();
    }

    private GitLabPushEvent parse(String rawBody) {
        if (blank(rawBody)) {
            return null;
        }
        try {
            return objectMapper.readValue(rawBody, GitLabPushEvent.class);
        } catch (JsonProcessingException e) {
            log.warn("GitLab Webhook 请求体无法解析，已忽略");
            return null;
        }
    }

    private static Set<String> extractTaskKeys(String... texts) {
        Set<String> keys = new LinkedHashSet<>();
        for (String text : texts) {
            if (blank(text)) {
                continue;
            }
            Matcher matcher = TASK_KEY_PATTERN.matcher(text);
            while (matcher.find()) {
                keys.add(matcher.group());
            }
        }
        return keys;
    }

    private static String shortBranch(String ref) {
        return ref != null && ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : "";
    }

    private static String firstLine(String value) {
        if (blank(value)) {
            return "";
        }
        int newline = value.indexOf('\n');
        return (newline < 0 ? value : value.substring(0, newline)).trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record GitLabPushEvent(
            @JsonProperty("object_kind") String objectKind,
            String ref,
            GitLabProject project,
            List<GitLabCommit> commits) {
    }

    public record GitLabProject(
            @JsonProperty("path_with_namespace") String pathWithNamespace,
            @JsonProperty("web_url") String webUrl) {
    }

    public record GitLabCommit(String id, String title, String message, GitLabAuthor author) {
    }

    public record GitLabAuthor(String name) {
    }
}
