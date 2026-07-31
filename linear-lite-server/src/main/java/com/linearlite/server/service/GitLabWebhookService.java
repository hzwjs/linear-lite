package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.entity.ProjectCodexBinding;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskComment;
import com.linearlite.server.entity.User;
import com.linearlite.server.exception.UnauthorizedException;
import com.linearlite.server.mapper.ProjectCodexBindingMapper;
import com.linearlite.server.mapper.TaskCommentMapper;
import com.linearlite.server.mapper.TaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitLab Push Webhook：解析推送事件，把提交消息中引用任务编号（如 ENG-1）的提交
 * 以评论形式写入对应任务。以 (source_type, external_ref, task_id) 唯一键幂等去重。
 */
@Service
public class GitLabWebhookService {

    private static final Logger log = LoggerFactory.getLogger(GitLabWebhookService.class);
    private static final Pattern TASK_KEY_PATTERN = Pattern.compile("\\b[A-Z][A-Z0-9]{0,15}-\\d{1,9}\\b");
    private static final String SOURCE_GITLAB_COMMIT = "gitlab_commit";
    private static final String PUSH_KIND = "push";

    private final ProjectCodexBindingMapper bindingMapper;
    private final TaskMapper taskMapper;
    private final TaskCommentMapper taskCommentMapper;
    private final ObjectMapper objectMapper;
    private final CodexDispatchService codexDispatchService;

    public GitLabWebhookService(ProjectCodexBindingMapper bindingMapper, TaskMapper taskMapper,
                                TaskCommentMapper taskCommentMapper, ObjectMapper objectMapper,
                                CodexDispatchService codexDispatchService) {
        this.bindingMapper = bindingMapper;
        this.taskMapper = taskMapper;
        this.taskCommentMapper = taskCommentMapper;
        this.objectMapper = objectMapper;
        this.codexDispatchService = codexDispatchService;
    }

    /** 校验 token 并消费一次 Push 事件；无法解析或无关事件静默返回，避免 GitLab 重试。 */
    @Transactional(rollbackFor = Exception.class)
    public void handlePush(String token, String rawBody) {
        ProjectCodexBinding binding = resolveBinding(token);
        GitLabPushEvent event = parse(rawBody);
        if (event == null || !PUSH_KIND.equals(event.objectKind())) {
            return;
        }
        // 仓库身份一致性：token 与 path_with_namespace 共同确定来源仓库；首条事件落库，后续必须一致。
        if (binding.getWebhookPath() != null && !binding.getWebhookPath().equals(event.projectPath())) {
            log.warn("GitLab Webhook 仓库路径不匹配，已忽略：绑定 {}，事件 {}，期望 {}", binding.getId(), event.projectPath(), binding.getWebhookPath());
            return;
        }
        if (!Objects.equals(event.projectPath(), binding.getWebhookPath()) || !Objects.equals(event.projectWebUrl(), binding.getWebhookBaseUrl())) {
            binding.setWebhookPath(event.projectPath());
            binding.setWebhookBaseUrl(event.projectWebUrl());
            bindingMapper.updateById(binding);
        }
        List<GitLabCommit> commits = event.commits() == null ? List.of() : event.commits();
        if (commits.isEmpty()) {
            return;
        }
        User codexUser = codexDispatchService.requireCodexUser();
        for (GitLabCommit commit : commits) {
            Set<String> keys = extractTaskKeys(commit.title(), commit.message());
            if (keys.isEmpty()) {
                continue;
            }
            String commitUrl = joinCommitUrl(event.projectWebUrl(), commit.id());
            String branch = shortBranch(event.ref());
            for (String key : keys) {
                createCommentForCommit(key, commit, commitUrl, branch, codexUser, binding);
            }
        }
    }

    private void createCommentForCommit(String taskKey, GitLabCommit commit, String commitUrl, String branch,
                                        User codexUser, ProjectCodexBinding webhookBinding) {
        Task task = taskMapper.selectOne(new LambdaQueryWrapper<Task>().eq(Task::getTaskKey, taskKey));
        if (task == null) {
            return;
        }
        // 单一路径：只有任务项目绑定仓库与推送仓库一致时才写入评论。
        ProjectCodexBinding taskBinding = bindingMapper.selectOne(
                new LambdaQueryWrapper<ProjectCodexBinding>().eq(ProjectCodexBinding::getProjectId, task.getProjectId()));
        if (taskBinding == null || !taskBinding.getRepositoryId().equals(webhookBinding.getRepositoryId())) {
            return;
        }
        Long exists = taskCommentMapper.selectCount(new LambdaQueryWrapper<TaskComment>()
                .eq(TaskComment::getSourceType, SOURCE_GITLAB_COMMIT)
                .eq(TaskComment::getExternalRef, commit.id())
                .eq(TaskComment::getTaskId, task.getId()));
        if (exists != null && exists > 0) {
            return;
        }
        TaskComment comment = new TaskComment();
        comment.setTaskId(task.getId());
        comment.setAuthorId(codexUser.getId());
        comment.setBody(buildBody(commit, commitUrl, branch));
        comment.setDepth(0);
        comment.setSourceType(SOURCE_GITLAB_COMMIT);
        comment.setExternalRef(commit.id());
        comment.setCreatedAt(LocalDateTime.now());
        try {
            taskCommentMapper.insert(comment);
        } catch (DuplicateKeyException e) {
            // 并发重投时由唯一键兜底，保证同一提交只产生一条评论。
            log.debug("GitLab 提交评论已存在，跳过：task={} commit={}", taskKey, commit.id());
        }
    }

    private String buildBody(GitLabCommit commit, String commitUrl, String branch) {
        String firstLine = blank(commit.title()) ? firstLineOf(commit.message()) : commit.title();
        String shortSha = commit.id() != null && commit.id().length() > 8 ? commit.id().substring(0, 8) : commit.id();
        String author = commit.authorName() == null ? "" : commit.authorName();
        StringBuilder body = new StringBuilder();
        body.append("**GitLab 提交**\n\n");
        if (commit.id() != null && commitUrl != null) {
            body.append("[").append(shortSha).append("](").append(commitUrl).append(") ").append(firstLine);
        } else {
            body.append(firstLine);
        }
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

    private ProjectCodexBinding resolveBinding(String token) {
        if (blank(token)) {
            throw new UnauthorizedException("缺少 GitLab Webhook Token");
        }
        // 与 CodexDispatchService.hash 使用完全相同的 base64(SHA-256) 编码，常量时间比较。
        byte[] expected = CodexDispatchService.hash(token).getBytes(StandardCharsets.UTF_8);
        for (ProjectCodexBinding binding : bindingMapper.selectList(null)) {
            String stored = binding.getWebhookTokenHash();
            if (stored != null && MessageDigest.isEqual(expected, stored.getBytes(StandardCharsets.UTF_8))) {
                return binding;
            }
        }
        throw new UnauthorizedException("GitLab Webhook Token 无效");
    }

    private GitLabPushEvent parse(String rawBody) {
        if (blank(rawBody)) {
            return null;
        }
        try {
            return objectMapper.readValue(rawBody, GitLabPushEvent.class);
        } catch (JsonProcessingException e) {
            log.warn("GitLab Webhook 请求体无法解析，已忽略", e);
            return null;
        }
    }

    private Set<String> extractTaskKeys(String... texts) {
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

    private static String joinCommitUrl(String webUrl, String sha) {
        if (blank(webUrl) || blank(sha)) {
            return null;
        }
        return webUrl.replaceAll("/$", "") + "/-/commit/" + sha;
    }

    private static String shortBranch(String ref) {
        if (blank(ref)) {
            return "";
        }
        return ref.startsWith("refs/heads/") ? ref.substring("refs/heads/".length()) : ref;
    }

    private static String firstLineOf(String message) {
        if (blank(message)) {
            return "";
        }
        int newline = message.indexOf('\n');
        return (newline >= 0 ? message.substring(0, newline) : message).trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** GitLab Push 事件固定字段；其余字段不参与数据路径。 */
    public record GitLabPushEvent(
            @JsonProperty("object_kind") String objectKind,
            String ref,
            GitLabProject project,
            List<GitLabCommit> commits) {
        public String projectPath() {
            return project == null ? null : project.pathWithNamespace();
        }
        public String projectWebUrl() {
            return project == null ? null : project.webUrl();
        }
    }

    public record GitLabProject(
            @JsonProperty("path_with_namespace") String pathWithNamespace,
            @JsonProperty("web_url") String webUrl) {
    }

    public record GitLabCommit(String id, String title, String message, GitLabAuthor author) {
        public String authorName() {
            return author == null ? null : author.name();
        }
    }

    public record GitLabAuthor(String name) {
    }
}
